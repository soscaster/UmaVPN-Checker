package com.umavpn.checker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favouritesDataStore by preferencesDataStore(name = "favourites")

class FavouritesStore(private val context: Context) {

    val favouriteIpsFlow: Flow<Set<String>> = context.favouritesDataStore.data
        .map { prefs -> prefs[FAVOURITE_IPS] ?: emptySet() }

    /** Last-known ServerSummary for each favourite IP, keyed by IP. */
    val favouriteSummariesFlow: Flow<Map<String, ServerSummary>> = context.favouritesDataStore.data
        .map { prefs ->
            (prefs[FAVOURITE_DATA] ?: emptySet())
                .mapNotNull { parseSummary(it) }
                .associateBy { it.ip }
        }

    /** Toggle favourite. Removing a favourite also deletes its cached summary. */
    suspend fun toggle(ip: String) {
        context.favouritesDataStore.edit { prefs ->
            val current = prefs[FAVOURITE_IPS] ?: emptySet()
            if (ip in current) {
                prefs[FAVOURITE_IPS] = current - ip
                prefs[FAVOURITE_DATA] = (prefs[FAVOURITE_DATA] ?: emptySet())
                    .filter { !it.startsWith("$ip|") }.toSet()
            } else {
                prefs[FAVOURITE_IPS] = current + ip
            }
        }
    }

    /** Persist fresh summary data for a favourite IP (called after each successful fetch). */
    suspend fun updateSummary(summary: ServerSummary) {
        context.favouritesDataStore.edit { prefs ->
            if (summary.ip !in (prefs[FAVOURITE_IPS] ?: emptySet())) return@edit
            prefs[FAVOURITE_DATA] = (prefs[FAVOURITE_DATA] ?: emptySet())
                .filter { !it.startsWith("${summary.ip}|") }.toSet() + encodeSummary(summary)
        }
    }

    companion object {
        private val FAVOURITE_IPS = stringSetPreferencesKey("favourite_ips")
        private val FAVOURITE_DATA = stringSetPreferencesKey("favourite_data")

        private fun encodeSummary(s: ServerSummary): String =
            "${s.ip}|${s.country}|${s.timestamp}|${s.duration}|${s.speed}"

        private fun parseSummary(encoded: String): ServerSummary? = runCatching {
            val parts = encoded.split("|", limit = 5)
            if (parts.size < 5) return null
            ServerSummary(
                ip = parts[0],
                country = parts[1],
                timestamp = parts[2],
                duration = parts[3].toInt(),
                speed = parts[4].toDouble()
            )
        }.getOrNull()
    }
}
