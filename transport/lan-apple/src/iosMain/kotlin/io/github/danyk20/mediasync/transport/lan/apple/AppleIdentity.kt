/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Security.sec_identity_t

/**
 * This device's TLS identity on iPhone: a self-signed certificate and the key it was made with, packed into the
 * `sec_identity_t` that Network.framework takes for mutual TLS.
 *
 * @property certificateDer the certificate, DER encoded; [id] is its SHA-256.
 * @property secIdentity the Security identity; Network.framework retains it for as long as it is used.
 */
class AppleIdentity internal constructor(val certificateDer: ByteArray, internal val secIdentity: sec_identity_t) {
    /** This device's id as the peers see it: the SHA-256 of [certificateDer], the same as Android and desktop. */
    val id: DeviceId = DeviceId.of(certificateDer.sha256())
}
