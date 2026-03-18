package com.umavpn.checker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.umavpn.checker.MainActivity
import com.umavpn.checker.R
import com.umavpn.checker.data.OpenVpnVariant
import com.umavpn.checker.data.UmaVpnRepository
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UmaVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Master TUN fd — kept open so OPVN2 can dup() it if OPVN3 fails.
    private var masterTunPfd: ParcelFileDescriptor? = null
    // Legacy alias — still set for compatibility with notification/disconnect path.
    private var tunnel: ParcelFileDescriptor? = null

    @Volatile private var isDisconnecting: Boolean = false
    @Volatile private var isNotificationObserverStarted: Boolean = false
    @Volatile private var activeEngine: EngineType = EngineType.NONE
    @Volatile private var opvn3FallbackAttempted: Boolean = false

    private var currentIp: String? = null
    private var currentVariant: OpenVpnVariant = OpenVpnVariant.CURRENT
    private var currentConfig: String? = null
    private var currentAllowedPackages: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        VpnRuntime.appendLog("SERVICE onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            VpnController.isConnectAction(intent?.action) -> {
                VpnRuntime.appendLog("SERVICE connect action received")
                val ip = intent?.getStringExtra(VpnController.EXTRA_IP)
                val variant = intent?.getStringExtra(VpnController.EXTRA_VARIANT)
                val allowedPackages = intent?.getStringArrayListExtra(VpnController.EXTRA_ALLOWED_PACKAGES)
                    ?.toSet()
                    ?: emptySet()

                if (ip.isNullOrBlank() || variant.isNullOrBlank()) {
                    VpnRuntime.appendLog("SERVICE invalid profile in intent")
                    VpnRuntime.setState(VpnState.Error("Invalid VPN profile"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                startNotificationObserver()
                connect(ip = ip, variant = variant, allowedPackages = allowedPackages)
            }

            VpnController.isDisconnectAction(intent?.action) -> {
                VpnRuntime.appendLog("SERVICE disconnect action received")
                disconnect()
            }
        }
        return START_STICKY
    }

    private fun connect(ip: String, variant: String, allowedPackages: Set<String>) {
        val priorEngine = activeEngine
        currentIp = ip
        currentVariant = OpenVpnVariant.entries.firstOrNull { it.apiValue == variant } ?: OpenVpnVariant.CURRENT
        currentAllowedPackages = allowedPackages
        opvn3FallbackAttempted = false
        activeEngine = EngineType.NONE
        currentConfig = null
        // Keep isDisconnecting=true while we tear down the prior session so that
        // the old session's DISCONNECTED callback cannot call disconnect() and
        // stop the service underneath us.
        isDisconnecting = priorEngine != EngineType.NONE

        scope.launch {
            // If switching servers while a session is active, stop it first.
            // stopSession() is a blocking JNI call that joins the native thread.
            if (priorEngine != EngineType.NONE) {
                VpnRuntime.appendLog("SERVICE switching server: stopping ${priorEngine.name} first")
                when (priorEngine) {
                    EngineType.OPVN2 -> OpenVpn2NativeBridge.stopSession()
                    EngineType.OPVN3 -> OpenVpnNativeBridge.stopSession()
                    EngineType.NONE  -> Unit
                }
                runCatching { masterTunPfd?.close(); masterTunPfd = null }
                runCatching { tunnel?.close(); tunnel = null }
                isDisconnecting = false
            }

            runCatching {
                VpnRuntime.appendLog("SERVICE [OPVN2] connecting ip=$ip variant=$variant allowedApps=${allowedPackages.size}")
                VpnRuntime.setState(VpnState.Connecting)

                val ovpnConfig = UmaVpnRepository.create().fetchRawConfig(ip = ip, variant = variant).getOrThrow()
                currentConfig = ovpnConfig
                val parsed = VpnConfigParser.parse(ovpnConfig)

                if (!OpenVpn2NativeBridge.isNativeAvailable()) {
                    error("[OPVN2] Native engine is not available.")
                }

                if (!OpenVpn2NativeBridge.initialize()) {
                    error("[OPVN2] Failed to initialize: ${OpenVpn2NativeBridge.lastError()}")
                }

                masterTunPfd?.close()
                val establishedTunnel = Builder()
                    .setSession("UmaVPN")
                    .addAddress("10.10.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(parsed.dnsServers.first())
                    .applyAllowedApps(allowedPackages)
                    .establish()
                    ?: error("Failed to establish VPN interface")

                masterTunPfd = establishedTunnel
                tunnel = establishedTunnel

                val nativeTunFd = ParcelFileDescriptor.dup(establishedTunnel.fileDescriptor).detachFd()
                activeEngine = EngineType.OPVN2

                val sessionStarted = OpenVpn2NativeBridge.startSession(
                    configText = preprocessForOpvn2(ovpnConfig),
                    tunFd = nativeTunFd,
                    cacheDirPath = cacheDir.absolutePath
                )
                VpnRuntime.appendLog("SERVICE [OPVN2] sessionStarted=$sessionStarted")

                if (!sessionStarted) {
                    activeEngine = EngineType.NONE
                    error("[OPVN2] Start failed: ${OpenVpn2NativeBridge.lastError()}")
                }

                val endpoint = "${parsed.remoteHost}:${parsed.remotePort}/${parsed.protocol}"
                VpnRuntime.appendLog("SERVICE [OPVN2] tunnel established endpoint=$endpoint")
                updateNotification("Starting tunnel [OPVN2]: $endpoint")

            }.onFailure { throwable ->
                val reason = throwable.message ?: "unknown"
                VpnRuntime.appendLog("SERVICE [OPVN2] connect failure=$reason")
                activeEngine = EngineType.NONE

                if (!tryFallbackToOpvn3(reason)) {
                    disconnect(finalState = VpnState.Error(reason.ifBlank { "VPN connection failed" }))
                }
            }
        }
    }

    private fun tryFallbackToOpvn3(opvn2Reason: String): Boolean {
        if (opvn3FallbackAttempted) return false
        if (!OpenVpnNativeBridge.isNativeAvailable()) {
            VpnRuntime.appendLog("SERVICE [OPVN3] not available, no fallback possible")
            return false
        }
        val config = currentConfig ?: run {
            VpnRuntime.appendLog("SERVICE [OPVN3] no cached config, cannot fallback")
            return false
        }
        val masterPfd = masterTunPfd ?: run {
            VpnRuntime.appendLog("SERVICE [OPVN3] masterTunPfd is null, cannot fallback")
            return false
        }

        opvn3FallbackAttempted = true
        VpnRuntime.appendLog("SERVICE [OPVN3] falling back, opvn2-reason=$opvn2Reason")
        VpnRuntime.setState(VpnState.Connecting)

        scope.launch {
            runCatching {
                if (!OpenVpnNativeBridge.initialize()) {
                    error("[OPVN3] Initialize failed: ${OpenVpnNativeBridge.lastError()}")
                }

                val nativeTunFd = ParcelFileDescriptor.dup(masterPfd.fileDescriptor).detachFd()
                activeEngine = EngineType.OPVN3

                val sessionStarted = OpenVpnNativeBridge.startSession(
                    configText = config,
                    tunFd = nativeTunFd
                )
                VpnRuntime.appendLog("SERVICE [OPVN3] sessionStarted=$sessionStarted")

                if (!sessionStarted) {
                    activeEngine = EngineType.NONE
                    error("[OPVN3] Start failed: ${OpenVpnNativeBridge.lastError()}")
                }

                updateNotification("Starting tunnel [OPVN3]...")

            }.onFailure { throwable ->
                val reason2 = throwable.message ?: "unknown"
                VpnRuntime.appendLog("SERVICE [OPVN3] connect failure=$reason2")
                activeEngine = EngineType.NONE
                disconnect(
                    finalState = VpnState.Error(
                        "All engines failed. OPVN2: $opvn2Reason | OPVN3: $reason2"
                    )
                )
            }
        }
        return true
    }

    private fun preprocessForOpvn2(config: String): String {
        val filtered = config.lines().mapNotNull { line ->
            val lower = line.trimStart().lowercase()
            when {
                // data-ciphers is OpenVPN 2.5+; map to legacy "cipher <first>" for OPVN2.
                lower.startsWith("data-ciphers ") || lower.startsWith("data-ciphers\t") -> {
                    val cipherList = line.trim().drop("data-ciphers".length).trim()
                    val first = cipherList.split(":").first().trim()
                    if (first.isNotEmpty()) "cipher $first" else null
                }
                // data-ciphers-fallback is also 2.5+; drop it entirely.
                lower.startsWith("data-ciphers-fallback ") ||
                lower.startsWith("data-ciphers-fallback\t") -> null
                else -> line
            }
        }.joinToString("\n")
        // OpenVPN 2.4.x NCP validates any server-pushed "cipher X" against the
        // client-side ncp-ciphers list (default: AES-256-GCM:AES-128-GCM).
        // VPNGate/legacy servers push "cipher AES-128-CBC" which is NOT in that
        // default list → process-push-msg-failed. Extend ncp-ciphers to accept it.
        // Also filter 2.5+/2.6+ pushed directives that OPVN2 (2.4.x) doesn't know.
        return filtered +
            "\nncp-ciphers AES-256-GCM:AES-128-GCM:AES-128-CBC:AES-256-CBC" +
            "\npull-filter ignore \"data-ciphers\"" +
            "\npull-filter ignore \"data-ciphers-fallback\"" +
            "\npull-filter ignore \"tls-groups\"" +
            "\npull-filter ignore \"peer-fingerprint\"" +
            "\npull-filter ignore \"key-derivation\"" +
            "\npull-filter ignore \"tls-cert-profile\""
    }

    private fun doOpenTunnelForOpvn2(ip: String, prefix: Int, dns: String): Int {
        return runCatching {
            masterTunPfd?.close()
            val dnsToUse = dns.ifBlank { "8.8.8.8" }
            val tun = Builder()
                .setSession("UmaVPN")
                .addAddress(ip, prefix)
                .addRoute("0.0.0.0", 0)
                .apply { runCatching { addDnsServer(dnsToUse) } }
                .applyAllowedApps(currentAllowedPackages)
                .establish()
                ?: return -1
            masterTunPfd = tun
            tunnel = tun
            VpnRuntime.appendLog("SERVICE doOpenTunnelForOpvn2: TUN ip=$ip/$prefix dns=$dnsToUse")
            ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
        }.getOrElse { e ->
            VpnRuntime.appendLog("SERVICE doOpenTunnelForOpvn2: error=${e.message}")
            -1
        }
    }

    private fun Builder.applyAllowedApps(packages: Set<String>): Builder {
        if (packages.isEmpty()) return this
        packages.forEach { packageName ->
            runCatching { addAllowedApplication(packageName) }
        }
        return this
    }

    private fun disconnect(finalState: VpnState = VpnState.Disconnected, stopNative: Boolean = true) {
        if (isDisconnecting) return
        isDisconnecting = true
        VpnRuntime.appendLog("SERVICE disconnect engine=$activeEngine stopNative=$stopNative")

        if (stopNative) {
            when (activeEngine) {
                EngineType.OPVN3 -> OpenVpnNativeBridge.stopSession()
                EngineType.OPVN2 -> OpenVpn2NativeBridge.stopSession()
                EngineType.NONE -> {
                    runCatching { OpenVpnNativeBridge.stopSession() }
                    runCatching { OpenVpn2NativeBridge.stopSession() }
                }
            }
        }
        activeEngine = EngineType.NONE

        runCatching { masterTunPfd?.close(); masterTunPfd = null }
        runCatching { tunnel?.close(); tunnel = null }
        VpnRuntime.setState(finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onNativeSessionTerminated(engine: EngineType, message: String?, isError: Boolean) {
        VpnRuntime.appendLog("SERVICE [${engine.name}] native session ended isError=$isError message=${message ?: "<empty>"}")

        if (isError && engine == EngineType.OPVN2 && !opvn3FallbackAttempted) {
            if (tryFallbackToOpvn3(message ?: "opvn2-error")) return
        }

        val state = if (isError) {
            VpnState.Error(message ?: "${engine.name} session terminated with error")
        } else {
            VpnState.Disconnected
        }

        disconnect(finalState = state, stopNative = false)
    }

    override fun onDestroy() {
        VpnRuntime.appendLog("SERVICE onDestroy")
        disconnect(stopNative = false)
        scope.cancel()
        serviceRef = null
        super.onDestroy()
    }

    private fun startNotificationObserver() {
        if (isNotificationObserverStarted) return
        isNotificationObserverStarted = true
        scope.launch {
            VpnRuntime.state.collect { state ->
                when (state) {
                    is VpnState.Connected ->
                        updateNotification("Connected · ${parseNotifEndpoint(state.endpoint)}")
                    is VpnState.Error ->
                        updateNotification("Error: ${state.message}")
                    VpnState.Connecting, VpnState.Disconnected -> Unit
                }
            }
        }
    }

    private fun parseNotifEndpoint(endpoint: String): String {
        if (endpoint.startsWith("SUCCESS,")) {
            val p = endpoint.split(",")
            if (p.size >= 4) return "${p[2]}:${p[3]}"
        }
        return endpoint.substringBefore(' ')
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        ensureChannel()
        val launchPi = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectPi = PendingIntent.getService(
            this,
            12,
            VpnController.buildDisconnectIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UmaVPN")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(launchPi)
            .addAction(0, "Disconnect", disconnectPi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active UmaVPN connection status"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "umavpn_vpn_status"
        private const val NOTIFICATION_ID = 1201
        private var serviceRef: WeakReference<UmaVpnService>? = null

        @JvmStatic
        fun openTunnelForOpvn2(ip: String, prefix: Int, dns: String): Int {
            val service = serviceRef?.get() ?: run {
                VpnRuntime.appendLog("SERVICE openTunnelForOpvn2: no service instance")
                return -1
            }
            return service.doOpenTunnelForOpvn2(ip, prefix, dns)
        }

        @JvmStatic
        fun protectSocket(fd: Int): Boolean {
            val service = serviceRef?.get() ?: return false
            val result = runCatching { service.protect(fd) }.getOrDefault(false)
            VpnRuntime.appendLog("SERVICE protectSocket fd=$fd result=$result")
            return result
        }

        @JvmStatic
        fun onNativeSessionEnded(engine: EngineType, message: String?, isError: Boolean) {
            val service = serviceRef?.get()
            if (service == null) {
                if (isError) {
                    VpnRuntime.setState(VpnState.Error(message ?: "Native VPN session terminated"))
                } else {
                    VpnRuntime.setState(VpnState.Disconnected)
                }
                return
            }

            service.scope.launch {
                service.onNativeSessionTerminated(engine = engine, message = message, isError = isError)
            }
        }
    }
}
