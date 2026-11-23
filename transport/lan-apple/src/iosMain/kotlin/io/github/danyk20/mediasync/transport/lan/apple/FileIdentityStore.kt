/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.open

/**
 * Keeps the identity in one file: the [IdentityFile] layout, written atomically with complete file protection
 * (`NSFileProtectionCompleteUntilFirstUserAuthentication`), excluded from iCloud and device backups, and readable by
 * the owner only (directory 0700, file 0600).
 *
 * [directory] is injectable so tests use a temporary directory; the app uses [applicationSupport].
 */
internal class FileIdentityStore(private val directory: String) : AppleIdentityStore {
    override val name: String get() = "file"

    private val path: String get() = "$directory/$FILE_NAME"

    override fun load(): AppleIdentityMaterial? {
        if (!FileSystem.SYSTEM.exists(path.toPath())) return null
        return IdentityFile.decode(FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
    }

    override fun save(material: AppleIdentityMaterial) {
        ensureDirectory()
        // Written beside the target and moved over it, so a reader sees the old file or the new one, never half of one.
        // The temporary file is created empty and protected before any key byte is written into it.
        val temporary = "$path.tmp"
        FileSystem.SYSTEM.delete(temporary.toPath(), mustExist = false)
        try {
            createEmpty(temporary)
            setProtection(temporary)
            excludeFromBackup(temporary)
            val encoded = IdentityFile.encode(material)
            try {
                FileSystem.SYSTEM.write(temporary.toPath()) { write(encoded) }
            } finally {
                encoded.fill(0)
            }
            FileSystem.SYSTEM.atomicMove(temporary.toPath(), path.toPath())
        } finally {
            // After a successful move the temporary name is gone already; after a failure it is removed here.
            FileSystem.SYSTEM.delete(temporary.toPath(), mustExist = false)
        }
    }

    override fun delete() {
        FileSystem.SYSTEM.delete(path.toPath(), mustExist = false)
    }

    private fun ensureDirectory() {
        FileSystem.SYSTEM.createDirectories(directory.toPath(), mustCreate = false)
        ownerOnly(directory, DIRECTORY_MODE)
    }

    /** Creates an empty file the owner alone can read and write: open sets the mode before any byte is written. */
    private fun createEmpty(itemPath: String) {
        val descriptor = open(itemPath, O_WRONLY or O_CREAT or O_EXCL, FILE_MODE.toUShort())
        if (descriptor < 0) throw AppleLanException("the identity file could not be created")
        close(descriptor)
    }

    /** Complete protection: the file is unreadable while the device is locked, until the first unlock after a boot. */
    private fun setProtection(itemPath: String) {
        val attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
        if (!NSFileManager.defaultManager.setAttributes(attributes, ofItemAtPath = itemPath, error = null)) {
            throw AppleLanException("the identity file's protection could not be set")
        }
    }

    private fun excludeFromBackup(filePath: String) {
        val url = NSURL.fileURLWithPath(filePath)
        val excluded = url.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
        if (!excluded) throw AppleLanException("the identity file could not be excluded from backup")
    }

    private fun ownerOnly(itemPath: String, mode: Int) {
        val status = chmod(itemPath, mode.toUShort())
        if (status != 0) throw AppleLanException("the identity file's permissions could not be set")
    }

    companion object {
        private const val FILE_NAME = "identity-v1"
        private const val FILE_MODE = 0x180 // 0600: the owner reads and writes
        private const val DIRECTORY_MODE = 0x1C0 // 0700: the owner only

        /** The store in this app's Application Support folder, under `MediaSync`. */
        fun applicationSupport(): FileIdentityStore {
            val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: throw AppleLanException("no Application Support directory")
            return FileIdentityStore("$support/MediaSync")
        }
    }
}
