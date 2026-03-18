package com.umavpn.checker.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.umavpn.checker.data.AppSelectionStore
import com.umavpn.checker.data.VpnPreferencesStore
import com.umavpn.checker.data.OpenVpnVariant
import com.umavpn.checker.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DeviceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val prefs = VpnPreferencesStore(context)
                val shouldAutoConnect = prefs.autoConnectFlow.first()
                if (!shouldAutoConnect) return@runCatching

                val profile = prefs.lastProfileFlow.first() ?: return@runCatching
                val variant = OpenVpnVariant.entries.firstOrNull { it.apiValue == profile.variantApiValue }
                    ?: OpenVpnVariant.CURRENT

                val allowedPackages = AppSelectionStore(context).selectedPackagesFlow.first()
                VpnController.connect(
                    context = context,
                    ip = profile.ip,
                    variant = variant,
                    allowedPackages = allowedPackages
                )
            }
            pendingResult.finish()
        }
    }
}
