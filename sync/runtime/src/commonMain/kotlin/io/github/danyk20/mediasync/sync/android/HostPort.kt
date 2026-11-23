/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

/** A literal IP address and a port: `192.168.1.20:47420` or `[fd00::1]:47420`. Never a name: no DNS lookups. */
data class HostPort(val host: String, val port: Int) {
    /** [parseHostPort] reads this back. */
    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"
}

/**
 * Parses `host:port` or `[v6]:port`. `null` if it's malformed, if the port isn't 1..65535, or if the host isn't a
 * literal IPv4 or IPv6 address. Names are never looked up. Scoped (link-local with interface, `fe80::1%wlan0`)
 * addresses are not accepted either; [formatAddress] never produces them.
 */
fun parseHostPort(text: String): HostPort? {
    val separator = text.lastIndexOf(':').takeIf { it > 0 } ?: return null
    val host = text.substring(0, separator).removePrefix("[").removeSuffix("]")
    val port = text.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
    return if (isIpv4(host) || isIpv6(host)) HostPort(host, port) else null
}

private fun isIpv4(host: String): Boolean {
    val parts = host.split('.')
    return parts.size == IPV4_PARTS && parts.all { part ->
        part.length in 1..IPV4_PART_DIGITS && part.all { it in '0'..'9' } && part.toInt() <= MAX_IPV4_PART
    }
}

private fun isIpv6(host: String): Boolean {
    val colons = host.count { it == ':' }
    return colons >= 2 && host.all { it in IPV6_CHARS } && hasIpv6Groups(host)
}

/** Whether [address] has the groups of an IPv6 address: at most one `::`, and 8 groups in all. */
private fun hasIpv6Groups(address: String): Boolean {
    val halves = address.split("::")
    if (halves.size > 2) return false
    val groups = halves.flatMap { half -> if (half.isEmpty()) emptyList() else half.split(':') }
    // An IPv4 tail (`::ffff:192.168.1.1`) is the last group only, and counts as two.
    val hasTail = '.' in address
    val tailOk = !hasTail || (isIpv4(groups.last()) && !(halves.size > 1 && '.' in halves.first()))
    val hexGroups = if (hasTail) groups.dropLast(1) else groups
    val count = hexGroups.size + if (hasTail) 2 else 0
    val countOk = if (halves.size == 2) count < IPV6_GROUPS else count == IPV6_GROUPS
    return tailOk && countOk && hexGroups.all(::isHexGroup)
}

private fun isHexGroup(group: String) = group.length in 1..HEX_GROUP_DIGITS && group.all { it in HEX_DIGITS }

private const val MAX_PORT = 65_535
private const val MAX_IPV4_PART = 255
private const val IPV4_PARTS = 4
private const val IPV4_PART_DIGITS = 3
private const val IPV6_GROUPS = 8
private const val HEX_GROUP_DIGITS = 4
private const val HEX_DIGITS = "0123456789abcdefABCDEF"
private const val IPV6_CHARS = "0123456789abcdefABCDEF:."
