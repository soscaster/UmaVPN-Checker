package com.umavpn.checker.vpn

import com.umavpn.checker.data.OpenVpnVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnRuntime {
    private const val MAX_LOG_LINES = 400

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _fallbackRequest = MutableStateFlow<FallbackRequest?>(null)
    val fallbackRequest: StateFlow<FallbackRequest?> = _fallbackRequest.asStateFlow()

    fun setState(newState: VpnState) {
        _state.value = newState
        appendLog("STATE -> ${newState.asReadableText()}")
    }

    fun appendLog(message: String) {
        val line = "${timestamp()} $message"
        _logs.update { current ->
            val next = if (current.size >= MAX_LOG_LINES) {
                current.drop(current.size - MAX_LOG_LINES + 1)
            } else {
                current
            }
            next + line
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        appendLog("Logs cleared")
    }

    fun requestExternalFallback(ip: String, variant: OpenVpnVariant, reason: String) {
        appendLog("FALLBACK_REQUEST engine=OPVN2_EXTERNAL ip=$ip variant=${variant.apiValue} reason=$reason")
        _fallbackRequest.value = FallbackRequest(ip = ip, variant = variant, reason = reason)
    }

    fun consumeFallbackRequest() {
        _fallbackRequest.value = null
    }

    private fun timestamp(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun VpnState.asReadableText(): String {
        return when (this) {
            VpnState.Disconnected -> "Disconnected"
            VpnState.Connecting -> "Connecting"
            is VpnState.Connected -> "Connected($endpoint)"
            is VpnState.Error -> "Error($message)"
        }
    }
}

data class FallbackRequest(
    val ip: String,
    val variant: OpenVpnVariant,
    val reason: String
)
