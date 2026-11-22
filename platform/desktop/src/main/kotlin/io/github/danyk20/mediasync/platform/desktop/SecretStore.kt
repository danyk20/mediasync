/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import com.github.javakeyring.Keyring
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Where the secret that unlocks the identity file is kept. */
interface SecretStore {
    /** Shown in diagnostics: which protection the identity file has. */
    val protection: SecretProtection

    /** The stored secret, or null when there is none (or the store cannot be read). */
    fun read(): String?

    /** Stores [secret]. Throws [IOException] when this store is not usable here. */
    fun write(secret: String)
}

/** How the identity file's password is protected, for the diagnostics screen. */
enum class SecretProtection(val description: String) {
    OS_CREDENTIAL_STORE("protected by the operating system's credential store"),
    FILE_PERMISSIONS("protected by file permissions"),
}

/** The operating system's credential store: macOS Keychain, Windows Credential Manager, Linux Secret Service. */
class KeyringSecretStore(
    private val service: String,
    private val account: String,
    private val keyring: () -> Keyring = { Keyring.create() },
) : SecretStore {
    override val protection = SecretProtection.OS_CREDENTIAL_STORE

    // Throws (for example BackendNotSupportedException on a headless Linux) when there is no store.
    private val backend: Keyring by lazy(keyring)

    @Suppress("TooGenericExceptionCaught") // The library throws its own checked and runtime types; all mean "no".
    override fun read(): String? = try {
        backend.getPassword(service, account)
    } catch (_: Exception) {
        null
    }

    @Suppress("TooGenericExceptionCaught")
    override fun write(secret: String) {
        try {
            backend.setPassword(service, account, secret)
        } catch (e: Exception) {
            throw IOException("The credential store is not available: ${e.message}", e)
        }
    }
}

/**
 * A file next to the identity, readable by its owner only (POSIX 0600; on Windows the user's profile
 * already limits access to that user and administrators).
 */
class FileSecretStore(private val file: Path) : SecretStore {
    override val protection = SecretProtection.FILE_PERMISSIONS

    override fun read(): String? = if (Files.isRegularFile(file)) {
        String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim().ifEmpty {
            null
        }
    } else {
        null
    }

    override fun write(secret: String) {
        writeOwnerOnly(file, secret.toByteArray(StandardCharsets.UTF_8))
    }
}

/** Writes [bytes] to [target] through a temporary file that is owner-only from its first byte, then moves it over. */
internal fun writeOwnerOnly(target: Path, bytes: ByteArray) {
    val directory = target.toAbsolutePath().parent
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, target.fileName.toString(), ".tmp") // POSIX: created as 0600
    try {
        Files.write(temporary, bytes)
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
