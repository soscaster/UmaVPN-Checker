package com.umavpn.checker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vpnPreferencesDataStore by preferencesDataStore(name = "vpn_prefs")

data class LastProfile(
    val ip: String,
    val variantApiValue: String
)

class VpnPreferencesStore(private val context: Context) {

    val autoConnectFlow: Flow<Boolean> = context.vpnPreferencesDataStore.data
        .map { prefs -> prefs[AUTO_CONNECT] ?: false }

    val lastProfileFlow: Flow<LastProfile?> = context.vpnPreferencesDataStore.data
        .map { prefs ->
            val ip = prefs[LAST_IP]
            val variant = prefs[LAST_VARIANT]
            if (ip.isNullOrBlank() || variant.isNullOrBlank()) {
                null
            } else {
                LastProfile(ip = ip, variantApiValue = variant)
            }
        }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.vpnPreferencesDataStore.edit { prefs ->
            prefs[AUTO_CONNECT] = enabled
        }
    }

    suspend fun setLastProfile(ip: String, variantApiValue: String) {
        context.vpnPreferencesDataStore.edit { prefs ->
            prefs[LAST_IP] = ip
            prefs[LAST_VARIANT] = variantApiValue
        }
    }

    companion object {
        private val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val LAST_IP = stringPreferencesKey("last_ip")
        private val LAST_VARIANT = stringPreferencesKey("last_variant")
    }
}
