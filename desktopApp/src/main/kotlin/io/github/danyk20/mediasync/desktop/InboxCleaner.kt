/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.core.database.ReceivedItemDao
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Clock

/**
 * Deletes a received file from the inbox once its copy is proven byte-identical to it, and marks the receipt
 * [BackupState.VERIFIED].
 *
 * The copy is read at the place its receipt recorded ([AbsoluteFolderConnectorFactory]), not in whichever folder is
 * chosen now. It must be a regular file there, and each inbox file still present must match it in size and SHA-256
 * (both are streamed). Anything else (a copy that differs in one byte or is truncated, missing, unreadable, or on a
 * folder that is away) keeps the inbox file, and the next pass tries again. Only files inside the inbox are deleted.
 */
class InboxCleaner(
    private val ledger: BackupLedger,
    private val received: ReceivedItemDao,
    inbox: Path,
    private val clock: Clock,
) {
    private val inbox: Path = inbox.toAbsolutePath().normalize()

    /** Returns how many inbox files were deleted. */
    suspend fun clean(account: AccountId): Int {
        val receipts = ledger.receipts(account, BackupState.BACKED_UP)
        if (receipts.isEmpty()) return 0
        val verified = receipts.mapNotNull { removeFromInbox(it) }
        val deleted = verified.sumOf { it.second }
        if (verified.isNotEmpty()) {
            ledger.record(verified.map { it.first })
            Diagnostics.log(Area.BACKUP, "$deleted copies removed from the inbox")
        }
        return deleted
    }

    /**
     * The receipt as verified, with how many inbox files went; `null` if the inbox file is kept (the receipt stays as
     * it is, so the next pass tries again).
     */
    private suspend fun removeFromInbox(receipt: BackupReceipt): Pair<BackupReceipt, Int>? {
        val locations = received.locationsOf(receipt.contentId.hex)
        val copy = fileOf(receipt.remoteId)?.takeIf { it.isAbsolute && Files.isRegularFile(it, NOFOLLOW) }
        val proven = copy != null && locations.mapNotNull(::inboxFile).all {
            !Files.exists(it, NOFOLLOW) || identical(copy, it)
        }
        if (!proven) {
            Diagnostics.log(Area.BACKUP, "Inbox file kept: its copy is missing, differs or cannot be read")
            return null
        }
        if (!locations.all(::deleteFromInbox)) return null
        return receipt.copy(state = BackupState.VERIFIED, checkedAtMillis = clock.millis()) to locations.size
    }

    /** Whether [copy] is byte-for-byte [original]: the same size, then the same SHA-256. */
    private fun identical(copy: Path, original: Path): Boolean = try {
        Files.isRegularFile(original, NOFOLLOW) &&
            Files.size(copy) == Files.size(original) &&
            MessageDigest.isEqual(sha256(copy), sha256(original))
    } catch (_: IOException) {
        false
    }

    /** Streams the file through the digest; nothing is held in memory whole. */
    private fun sha256(file: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        return digest.digest()
    }

    private fun inboxFile(location: String): Path? = fileOf(location)?.toAbsolutePath()?.normalize()

    /** `true` if nothing of it is left in the inbox: deleted now, gone already, or not ours to delete. */
    private fun deleteFromInbox(location: String): Boolean {
        val file = inboxFile(location) ?: return true
        if (!file.startsWith(inbox) || file == inbox) return true
        return try {
            Files.deleteIfExists(file)
            true
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        val NOFOLLOW: LinkOption = LinkOption.NOFOLLOW_LINKS
    }
}
