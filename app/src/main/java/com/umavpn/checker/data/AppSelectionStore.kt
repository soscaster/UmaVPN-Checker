package com.umavpn.checker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSelectionDataStore by preferencesDataStore(name = "app_selection")

class AppSelectionStore(private val context: Context) {

    val selectedPackagesFlow: Flow<Set<String>> = context.appSelectionDataStore.data
        .map { prefs -> prefs[SELECTED_PACKAGES] ?: emptySet() }

    suspend fun setSelectedPackages(packages: Set<String>) {
        context.appSelectionDataStore.edit { prefs ->
            prefs[SELECTED_PACKAGES] = packages
        }
    }

    companion object {
        private val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
    }
}
