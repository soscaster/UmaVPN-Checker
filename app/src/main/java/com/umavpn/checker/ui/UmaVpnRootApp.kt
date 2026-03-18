package com.umavpn.checker.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.umavpn.checker.data.AppCatalogRepository
import com.umavpn.checker.data.AppSelectionStore
import com.umavpn.checker.data.FavouritesStore
import com.umavpn.checker.data.OpenVpnVariant
import com.umavpn.checker.data.VpnPreferencesStore
import com.umavpn.checker.vpn.VpnController
import com.umavpn.checker.vpn.VpnRuntime
import com.umavpn.checker.vpn.VpnState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class RootTab(val label: String) {
    SERVERS("Servers"),
    CONNECTION("Connection"),
    SETTINGS("Settings")
}

@Composable
fun UmaVpnRootApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val appSelectionStore = remember { AppSelectionStore(context) }
    val appCatalogRepository = remember { AppCatalogRepository(context) }
    val vpnPreferencesStore = remember { VpnPreferencesStore(context) }
    val favouritesStore = remember { FavouritesStore(context) }
    val installedApps = remember { appCatalogRepository.getInstalledUserApps() }

    val selectedPackages by appSelectionStore.selectedPackagesFlow.collectAsState(initial = emptySet())
    val favouriteIps by favouritesStore.favouriteIpsFlow.collectAsState(initial = emptySet())
    val favouriteSummaries by favouritesStore.favouriteSummariesFlow.collectAsState(initial = emptyMap())
    val autoConnect by vpnPreferencesStore.autoConnectFlow.collectAsState(initial = false)
    val vpnState by VpnRuntime.state.collectAsState()
    val vpnLogs by VpnRuntime.logs.collectAsState()
    val fallbackRequest by VpnRuntime.fallbackRequest.collectAsState()

    var tab by rememberSaveable { mutableStateOf(RootTab.SERVERS) }
    var pendingConnect by remember { mutableStateOf<PendingConnect?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val request = pendingConnect
            if (request != null) {
                VpnController.connect(
                    context = context,
                    ip = request.ip,
                    variant = request.variant,
                    allowedPackages = selectedPackages
                )
            }
        } else {
            Toast.makeText(context, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingConnect = null
    }

    LaunchedEffect(Unit) {
        val stored = appSelectionStore.selectedPackagesFlow.first()
        if (stored.isEmpty()) {
            val pkg = "jp.co.cygames.umamusume"
            if (installedApps.any { it.packageName == pkg }) {
                appSelectionStore.setSelectedPackages(setOf(pkg))
            }
        }
    }

    LaunchedEffect(fallbackRequest) {
        val request = fallbackRequest ?: return@LaunchedEffect
        val uri = "openvpn://import-profile/https://api.umavpn.top/api/server/${request.ip}/config?variant=${request.variant.apiValue}"
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            VpnRuntime.appendLog("FALLBACK_LAUNCHED engine=OPVN2_EXTERNAL uri=$uri")
            VpnRuntime.setState(VpnState.Disconnected)
        }.onFailure {
            VpnRuntime.appendLog("FALLBACK_FAILED reason=${it.message ?: "openvpn-client-not-installed"}")
            VpnRuntime.setState(VpnState.Error("OpenVPN Client not installed or cannot handle fallback URI"))
            if (it is ActivityNotFoundException) {
                Toast.makeText(context, "OpenVPN client is not installed", Toast.LENGTH_SHORT).show()
            }
        }
        VpnRuntime.consumeFallbackRequest()
        tab = RootTab.CONNECTION
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == RootTab.SERVERS,
                    onClick = { tab = RootTab.SERVERS },
                    icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                    label = { Text(RootTab.SERVERS.label) }
                )
                NavigationBarItem(
                    selected = tab == RootTab.CONNECTION,
                    onClick = { tab = RootTab.CONNECTION },
                    icon = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null) },
                    label = { Text(RootTab.CONNECTION.label) }
                )
                NavigationBarItem(
                    selected = tab == RootTab.SETTINGS,
                    onClick = { tab = RootTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(RootTab.SETTINGS.label) }
                )
            }
        }
    ) { innerPadding ->
        when (tab) {
            RootTab.SERVERS -> UmaVpnCheckerApp(
                bottomInsetPadding = innerPadding,
                favouriteIps = favouriteIps,
                favouriteSummaries = favouriteSummaries,
                onToggleFavourite = { ip -> scope.launch { favouritesStore.toggle(ip) } },
                onUpdateFavouriteSummary = { summary -> scope.launch { favouritesStore.updateSummary(summary) } },
                onConnectInApp = { ip, variant ->
                    scope.launch {
                        vpnPreferencesStore.setLastProfile(ip = ip, variantApiValue = variant.apiValue)
                    }
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        pendingConnect = PendingConnect(ip = ip, variant = variant)
                        vpnPermissionLauncher.launch(prepareIntent)
                    } else {
                        VpnController.connect(
                            context = context,
                            ip = ip,
                            variant = variant,
                            allowedPackages = selectedPackages
                        )
                    }
                    tab = RootTab.CONNECTION
                }
            )

            RootTab.CONNECTION -> Box(modifier = Modifier.padding(innerPadding)) {
                ConnectionScreen(
                    state = vpnState,
                    logs = vpnLogs,
                    onDisconnect = {
                        VpnController.disconnect(context)
                    },
                    onClearLogs = {
                        VpnRuntime.clearLogs()
                    }
                )
            }

            RootTab.SETTINGS -> Box(modifier = Modifier.padding(innerPadding)) {
                SettingsScreen(
                    installedApps = installedApps,
                    selectedPackages = selectedPackages,
                    autoConnect = autoConnect,
                    onAutoConnectChanged = { enabled ->
                        scope.launch {
                            vpnPreferencesStore.setAutoConnect(enabled)
                        }
                    },
                    onToggleApp = { packageName, checked ->
                        val next = if (checked) {
                            selectedPackages + packageName
                        } else {
                            selectedPackages - packageName
                        }
                        scope.launch {
                            appSelectionStore.setSelectedPackages(next)
                        }
                    }
                )
            }
        }
    }
}

private data class PendingConnect(
    val ip: String,
    val variant: OpenVpnVariant
)
