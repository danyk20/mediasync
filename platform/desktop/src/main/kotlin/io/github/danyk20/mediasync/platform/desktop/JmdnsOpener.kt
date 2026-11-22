/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import java.io.Closeable
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** [MdnsOpener] over JmDNS: one instance bound to the given address. */
object JmdnsOpener : MdnsOpener {
    override fun open(address: InetAddress): MdnsSession {
        // The name is the host name in the A record; a fixed one avoids a (slow) reverse lookup of the address.
        val name = "mediasync-" + (address.hostAddress ?: "").substringBefore('%').replace(Regex("[.:]"), "-")
        return JmdnsSession(JmDNS.create(address, name))
    }
}

internal class JmdnsSession(private val jmdns: JmDNS) : MdnsSession {
    override fun register(service: MdnsService) {
        // JmDNS renames the service ("MediaSync (2)") if the name is taken; phones match the id record, not the name.
        jmdns.registerService(ServiceInfo.create(service.type, service.name, service.port, 0, 0, service.txt))
    }

    override fun browse(type: String, found: (MdnsPeer) -> Unit): Closeable {
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Added carries no addresses or TXT yet: ask for them, and serviceResolved delivers.
                jmdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                runCatching { found(event.info.toPeer()) }
            }
        }
        jmdns.addServiceListener(type, listener)
        return Closeable { jmdns.removeServiceListener(type, listener) }
    }

    override fun close() {
        runCatching { jmdns.unregisterAllServices() }
        jmdns.close()
    }
}

internal fun ServiceInfo.toPeer() = MdnsPeer(
    txt = propertyNames.toList().associateWith { getPropertyString(it).orEmpty() },
    port = port,
    addresses = inet4Addresses.toList() + inet6Addresses.toList(),
)
