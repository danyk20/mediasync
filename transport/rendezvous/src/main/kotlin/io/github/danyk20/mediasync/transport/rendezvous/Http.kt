/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** One HTTP exchange, kept small so the client is tested without a network. */
class HttpRequest(val method: String, val url: URI, val headers: Map<String, String>, val body: ByteArray?)

/** [headers] have lower-case names. */
class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/** Plain `HttpURLConnection`: the Worker's few small JSON requests need nothing more. */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = TIMEOUT_MILLIS,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = request.url.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >=
                HttpURLConnection.HTTP_BAD_REQUEST
            ) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { (name, values) -> name.lowercase() to values.joinToString(",") }
            return HttpResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}
