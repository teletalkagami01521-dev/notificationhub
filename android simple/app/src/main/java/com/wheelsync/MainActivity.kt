package com.wheelsync

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var miuiCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        miuiCard = findViewById(R.id.card_miui)

        // Android 13+: request POST_NOTIFICATIONS so the foreground service notification is visible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        miuiCard.visibility = if (isMiui()) View.VISIBLE else View.GONE

        requestBatteryExemption(null)

        // Start the keep-alive foreground service
        ContextCompat.startForegroundService(
            this,
            Intent(this, SyncForegroundService::class.java)
        )
    }

    override fun onResume() {
        super.onResume()
        AppStatusReporter.report(this)
        refreshStatus()
    }

    private fun isMiui(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    private fun refreshStatus() {
        val listener = ServiceHealth.isListenerEnabled(this)
        val battery = ServiceHealth.isBatteryExempt(this)
        statusText.text = buildString {
            append("Notification access ${if (listener) "✅" else "❌"}   ")
            append("Battery exempt ${if (battery) "✅" else "❌"}")
            if (isMiui()) append("\nMIUI: also set Autostart + Battery below")
        }
        statusText.setTextColor(
            if (listener) 0xFF8BC34A.toInt() else 0xFFFFB74D.toInt()
        )
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun openAppDetails() {
        tryStart(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    fun openNotificationListenerSettings(@Suppress("UNUSED_PARAMETER") view: View?) {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun requestBatteryExemption(@Suppress("UNUSED_PARAMETER") view: View?) {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            tryStart(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    fun openAutostartSettings(@Suppress("UNUSED_PARAMETER") view: View?) {
        val opened = tryStart(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
        )
        if (!opened) openAppDetails()
    }

    fun openBatterySaverSettings(@Suppress("UNUSED_PARAMETER") view: View?) {
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            "Storage Recycle Settings"
        }
        val opened = tryStart(
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
                .putExtra("package_name", packageName)
                .putExtra("package_label", label)
        )
        if (!opened) openAppDetails()
    }

    fun openMiuiAppDetails(@Suppress("UNUSED_PARAMETER") view: View?) {
        openAppDetails()
    }

    /**
     * Opens the notification settings of the system Permission Controller so the
     * "Review app with full device access" reminders can be muted at the source.
     */
    fun hideSecurityAlerts(@Suppress("UNUSED_PARAMETER") view: View?) {
        val controllerPackage =
            if (isMiui()) "com.lbe.security.miui" else "com.google.android.permissioncontroller"
        val opened = tryStart(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, controllerPackage)
        )
        if (!opened) openAppDetails()
    }

    fun finishSetup(@Suppress("UNUSED_PARAMETER") view: View?) {
        if (!ServiceHealth.isListenerEnabled(this)) {
            Toast.makeText(this, "Grant Notification Access first", Toast.LENGTH_LONG).show()
            openNotificationListenerSettings(view)
            return
        }
        // Disable launcher icon so app disappears from app drawer
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        finish()
    }
}
