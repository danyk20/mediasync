/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import okio.ByteString.Companion.toByteString
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8

/** The value, or an [AppleLanException] with [message] when it is null. */
internal fun <T : Any> T?.orFail(message: String): T = this ?: throw AppleLanException(message)

/** SHA-256 of these bytes. */
internal fun ByteArray.sha256(): ByteArray = toByteString().sha256().toByteArray()

/** A new CFData copy of these bytes. The caller releases it. */
internal fun ByteArray.toCFData(): CFDataRef {
    require(isNotEmpty()) { "CFData of zero bytes" }
    return usePinned { pinned -> CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), size.toLong()) }
        ?: throw AppleLanException("CFDataCreate failed")
}

/** The bytes of a CFData, copied out. */
internal fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    val pointer = CFDataGetBytePtr(this) ?: throw AppleLanException("CFDataGetBytePtr returned NULL")
    return pointer.reinterpret<ByteVar>().readBytes(length)
}

/** The text of a CFString (UTF-8), or `null` if it does not fit the buffer. */
internal fun CFStringRef.toKotlinString(): String? {
    val capacity = CFStringGetLength(this).toInt() * MAX_UTF8_BYTES_PER_UNIT + 1
    val buffer = ByteArray(capacity)
    val ok = buffer.usePinned { pinned ->
        CFStringGetCString(this, pinned.addressOf(0), capacity.toLong(), kCFStringEncodingUTF8)
    }
    return if (ok) buffer.decodeToString().substringBefore('\u0000') else null
}

private const val MAX_UTF8_BYTES_PER_UNIT = 4

/** Releases a Core Foundation or Security object this code owns (a `Create`, `Copy` or retained result). */
internal fun release(reference: platform.CoreFoundation.CFTypeRef?) {
    if (reference != null) CFRelease(reference)
}
