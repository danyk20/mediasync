/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Encodes Okio [ByteString]s as protobuf `bytes` fields. Unlike `ByteArray`, they compare by content. */
@OptIn(ExperimentalSerializationApi::class)
internal object ByteStringSerializer : KSerializer<ByteString> {
    private val delegate = ByteArraySerializer()

    // Must differ from ByteArray's own descriptor: the protobuf encoder special-cases that one and would
    // cast the ByteString straight to ByteArray. Delegating in serialize() still produces a `bytes` field.
    override val descriptor: SerialDescriptor = SerialDescriptor("okio.ByteString", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: ByteString) =
        encoder.encodeSerializableValue(delegate, value.toByteArray())

    override fun deserialize(decoder: Decoder): ByteString = decoder.decodeSerializableValue(delegate).toByteString()
}
