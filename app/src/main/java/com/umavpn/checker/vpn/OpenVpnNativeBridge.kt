package com.umavpn.checker.vpn

import android.util.Log

object OpenVpnNativeBridge {
    private const val TAG = "OpenVpnNativeBridge"
    @Volatile private var suppressNextDisconnectEvent: Boolean = false
    // Counts consecutive KEEPALIVE_TIMEOUTs within one session attempt.
    // Reset on startSession(). On the 2nd occurrence we trigger OPVN2 fallback
    // because repeated keepalive failures indicate a data-channel cipher mismatch
    // (server requires AES-128-CBC which OPVN3 does not support).
    @Volatile private var keepaliveTimeoutCount: Int = 0

    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("umavpn_openvpn")
        true
    }.onFailure {
        Log.w(TAG, "Native OpenVPN bridge not loaded yet", it)
    }.getOrDefault(false)

    fun isNativeAvailable(): Boolean = nativeLoaded

    fun initialize(): Boolean {
        if (!nativeLoaded) return false
        return nativeInitialize()
    }

    fun startSession(configText: String, tunFd: Int): Boolean {
        keepaliveTimeoutCount = 0
        suppressNextDisconnectEvent = false
        if (!nativeLoaded) return false
        return nativeStartSession(configText, tunFd)
    }

    fun stopSession() {
        if (!nativeLoaded) return
        nativeStopSession()
    }

    fun isSessionRunning(): Boolean {
        if (!nativeLoaded) return false
        return nativeIsSessionRunning()
    }

    fun lastError(): String {
        if (!nativeLoaded) return "Native bridge library is not loaded"
        return nativeGetLastError()
    }

    @JvmStatic
    fun onNativeEvent(name: String, info: String, isError: Boolean, isFatal: Boolean) {
        val infoText = info.ifBlank { "<empty>" }
        VpnRuntime.appendLog(
            "[OPVN3] NATIVE_EVENT name=$name info=$infoText error=$isError fatal=$isFatal"
        )

        if (name == "LOG" && info.contains("Session invalidated: DECRYPT_ERROR", ignoreCase = true)) {
            suppressNextDisconnectEvent = true
            Thread {
                runCatching { stopSession() }
            }.start()
            UmaVpnService.onNativeSessionEnded(
                engine = EngineType.OPVN3,
                message = "Data channel decrypt error from server/profile. Try another server or variant.",
                isError = true
            )
            return
        }

        // KEEPALIVE_TIMEOUT means data-channel packets are not flowing — server requires
        // AES-128-CBC which OPVN3 cannot negotiate. After the 2nd consecutive timeout
        // stop OPVN3 and report the failure. (OPVN2 handles this server directly via
        // config preprocessing; OPVN3 is only tried when OPVN2 fails first.)
        if (name == "LOG" && info.contains("Session invalidated: KEEPALIVE_TIMEOUT", ignoreCase = true)) {
            keepaliveTimeoutCount++
            if (keepaliveTimeoutCount >= 2) {
                keepaliveTimeoutCount = 0
                suppressNextDisconnectEvent = true
                Thread { runCatching { stopSession() } }.start()
                UmaVpnService.onNativeSessionEnded(
                    engine = EngineType.OPVN3,
                    message = "Server requires AES-128-CBC cipher which OPVN3 cannot negotiate. Please try a different server.",
                    isError = true
                )
            }
            return
        }

        when {
            name == "CONNECTED" -> {
                suppressNextDisconnectEvent = false
                VpnRuntime.setState(
                    VpnState.Connected(
                        endpoint = info.ifBlank { "connected" }
                    )
                )
            }

            isError || isFatal -> {
                val message = if (info.isBlank()) name else "$name: $info"
                UmaVpnService.onNativeSessionEnded(
                    engine = EngineType.OPVN3,
                    message = message,
                    isError = true
                )
            }

            name == "DISCONNECTED" -> {
                if (suppressNextDisconnectEvent) {
                    suppressNextDisconnectEvent = false
                    return
                }
                UmaVpnService.onNativeSessionEnded(
                    engine = EngineType.OPVN3,
                    message = info.takeIf { it.isNotBlank() },
                    isError = false
                )
            }

            name == "RECONNECTING" || name == "RESOLVE" || name == "WAIT" -> {
                VpnRuntime.setState(VpnState.Connecting)
            }
        }
    }

    private external fun nativeInitialize(): Boolean
    private external fun nativeStartSession(configText: String, tunFd: Int): Boolean
    private external fun nativeStopSession()
    private external fun nativeIsSessionRunning(): Boolean
    private external fun nativeGetLastError(): String

    @JvmStatic
    fun protectSocket(fd: Int): Boolean {
        return UmaVpnService.protectSocket(fd)
    }
}
