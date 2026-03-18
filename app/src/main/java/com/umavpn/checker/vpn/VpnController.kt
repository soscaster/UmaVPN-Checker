package com.umavpn.checker.vpn

import android.content.Context
import android.content.Intent
import com.umavpn.checker.data.OpenVpnVariant

object VpnController {
    private const val ACTION_CONNECT = "com.umavpn.checker.vpn.CONNECT"
    private const val ACTION_DISCONNECT = "com.umavpn.checker.vpn.DISCONNECT"

    const val EXTRA_IP = "extra_ip"
    const val EXTRA_VARIANT = "extra_variant"
    const val EXTRA_ALLOWED_PACKAGES = "extra_allowed_packages"

    fun connect(context: Context, ip: String, variant: OpenVpnVariant, allowedPackages: Set<String>) {
        val intent = Intent(context, UmaVpnService::class.java).apply {
            action = ACTION_CONNECT
            putExtra(EXTRA_IP, ip)
            putExtra(EXTRA_VARIANT, variant.apiValue)
            putStringArrayListExtra(EXTRA_ALLOWED_PACKAGES, ArrayList(allowedPackages))
        }
        context.startService(intent)
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, UmaVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun isConnectAction(action: String?): Boolean = action == ACTION_CONNECT

    fun isDisconnectAction(action: String?): Boolean = action == ACTION_DISCONNECT

    internal fun buildDisconnectIntent(context: Context): Intent =
        Intent(context, UmaVpnService::class.java).apply { action = ACTION_DISCONNECT }
}
