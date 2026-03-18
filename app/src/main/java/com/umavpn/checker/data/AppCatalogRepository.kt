package com.umavpn.checker.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

data class InstalledApp(
    val packageName: String,
    val label: String
)

class AppCatalogRepository(private val context: Context) {

    fun getInstalledUserApps(): List<InstalledApp> {
        val packageManager = context.packageManager

        // On Android 11+, package visibility can hide most apps from getInstalledApplications().
        // Querying launcher activities provides a stable, user-visible app list for allow-list selection.
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launcherApps = packageManager
            .queryLauncherActivitiesCompat(launcherIntent)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (appInfo.packageName == context.packageName) return@mapNotNull null

                InstalledApp(
                    packageName = appInfo.packageName,
                    label = resolveInfo.loadLabel(packageManager).toString().ifBlank { appInfo.packageName }
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()

        if (launcherApps.isNotEmpty()) {
            return launcherApps
        }

        // Fallback for devices/ROMs where launcher query may return empty unexpectedly.
        return packageManager.getInstalledApplicationsCompat()
            .asSequence()
            .filter { appInfo -> appInfo.packageName != context.packageName }
            .map { appInfo ->
                InstalledApp(
                    packageName = appInfo.packageName,
                    label = packageManager.getApplicationLabel(appInfo).toString().ifBlank { appInfo.packageName }
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun PackageManager.queryLauncherActivitiesCompat(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    private fun PackageManager.getInstalledApplicationsCompat(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getInstalledApplications(PackageManager.MATCH_ALL)
        }
}
