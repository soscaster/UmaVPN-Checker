package com.umavpn.checker.vpn

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data class Connected(val endpoint: String) : VpnState
    data class Error(val message: String) : VpnState
}
