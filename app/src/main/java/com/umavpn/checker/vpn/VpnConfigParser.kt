package com.umavpn.checker.vpn

data class ParsedVpnConfig(
    val remoteHost: String,
    val remotePort: Int,
    val protocol: String,
    val dnsServers: List<String>
)

object VpnConfigParser {
    fun parse(ovpnConfig: String): ParsedVpnConfig {
        val remoteMatch = "remote\\s+([^\\s]+)(?:\\s+(\\d+))?".toRegex(RegexOption.IGNORE_CASE)
            .find(ovpnConfig)
            ?: error("Cannot parse remote endpoint from config")

        val remoteHost = remoteMatch.groupValues.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: error("Cannot parse remote host from config")

        val remotePort = remoteMatch.groupValues.getOrNull(2)
            ?.toIntOrNull()
            ?: 1194

        val protocol = "^proto\\s+([^\\s]+)".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .find(ovpnConfig)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?: "udp"

        val dnsServers = "dhcp-option\\s+DNS\\s+([^\\s]+)".toRegex(RegexOption.IGNORE_CASE)
            .findAll(ovpnConfig)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .toList()

        return ParsedVpnConfig(
            remoteHost = remoteHost,
            remotePort = remotePort,
            protocol = protocol,
            dnsServers = if (dnsServers.isEmpty()) listOf("1.1.1.1", "8.8.8.8") else dnsServers
        )
    }
}
