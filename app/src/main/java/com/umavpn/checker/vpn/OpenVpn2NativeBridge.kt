package com.umavpn.checker.vpn

import android.util.Log

object OpenVpn2NativeBridge {
    private const val TAG = "OpenVpn2NativeBridge"

    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("umavpn_openvpn2")
        true
    }.onFailure {
        Log.w(TAG, "[OPVN2] Native OpenVPN2 bridge not loaded", it)
    }.getOrDefault(false)

    fun isNativeAvailable(): Boolean = nativeLoaded

    fun initialize(): Boolean {
        if (!nativeLoaded) return false
        return nativeInitialize2()
    }

    fun startSession(configText: String, tunFd: Int, cacheDirPath: String): Boolean {
        if (!nativeLoaded) return false
        return nativeStartSession2(configText, tunFd, cacheDirPath)
    }

    fun stopSession() {
        if (!nativeLoaded) return
        nativeStopSession2()
    }

    fun isSessionRunning(): Boolean {
        if (!nativeLoaded) return false
        return nativeIsSessionRunning2()
    }

    fun lastError(): String {
        if (!nativeLoaded) return "[OPVN2] Native bridge library is not loaded"
        return nativeGetLastError2()
    }

    @JvmStatic
    fun onNativeEvent2(name: String, info: String, isError: Boolean, isFatal: Boolean) {
        val infoText = info.ifBlank { "<empty>" }
        VpnRuntime.appendLog("[OPVN2] NATIVE_EVENT name=$name info=$infoText error=$isError fatal=$isFatal")

        when {
            name == "CONNECTED" -> {
                VpnRuntime.setState(VpnState.Connected(endpoint = info.ifBlank { "connected" }))
            }

            isError || isFatal -> {
                val message = if (info.isBlank()) name else "$name: $info"
                UmaVpnService.onNativeSessionEnded(
                    engine = EngineType.OPVN2,
                    message = message,
                    isError = true
                )
            }

            name == "DISCONNECTED" -> {
                UmaVpnService.onNativeSessionEnded(
                    engine = EngineType.OPVN2,
                    message = info.takeIf { it.isNotBlank() },
                    isError = false
                )
            }

            name == "RECONNECTING" || name == "RESOLVE" || name == "WAIT" -> {
                VpnRuntime.setState(VpnState.Connecting)
            }
        }
    }

    @JvmStatic
    fun openTunnelForIp(ip: String, prefix: Int, dns: String): Int {
        VpnRuntime.appendLog("[OPVN2] openTunnelForIp ip=$ip/$prefix dns=$dns")
        return UmaVpnService.openTunnelForOpvn2(ip, prefix, dns)
    }

    @JvmStatic
    fun protectSocket2(fd: Int): Boolean = UmaVpnService.protectSocket(fd)

    private external fun nativeInitialize2(): Boolean
    private external fun nativeStartSession2(configText: String, tunFd: Int, cacheDirPath: String): Boolean
    private external fun nativeStopSession2()
    private external fun nativeIsSessionRunning2(): Boolean
    private external fun nativeGetLastError2(): String
}
