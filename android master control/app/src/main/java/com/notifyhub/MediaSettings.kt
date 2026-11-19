package com.notifyhub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

object MediaSettings {

    private const val PREFS = "master_control_media"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_LAST_MEDIA_SCAN = "last_media_scan"
    private const val KEY_LAST_ALL_FILES_SCAN = "last_all_files_scan"
    private const val KEY_DOWNLOADS_TREE = "downloads_tree"
    private const val KEY_LAST_DOWNLOADS_SCAN = "last_downloads_scan"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isWifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, false)

    fun setWifiOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    fun hasMediaPermission(context: Context): Boolean =
        canRead(context, isVideo = false) || canRead(context, isVideo = true)

    fun hasFullMediaAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ||
            hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
    }

    fun isLimitedMediaAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            !hasFullMediaAccess(context) &&
            hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun canRead(context: Context, isVideo: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val specific = if (isVideo) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
        return hasPermission(context, specific) ||
            (Build.VERSION.SDK_INT >= 34 &&
                hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }

    fun hasAllFilesAccess(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun hasDownloadsFolder(context: Context): Boolean = downloadsTree(context) != null

    fun downloadsTree(context: Context): Uri? =
        prefs(context).getString(KEY_DOWNLOADS_TREE, null)?.let(Uri::parse)

    fun setDownloadsTree(context: Context, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_DOWNLOADS_TREE) else putString(KEY_DOWNLOADS_TREE, uri.toString())
        }.apply()
    }

    fun lastMediaScan(context: Context): Long =
        prefs(context).getLong(KEY_LAST_MEDIA_SCAN, 0L)

    fun setLastMediaScan(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_LAST_MEDIA_SCAN, value).apply()
    }

    fun lastAllFilesScan(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ALL_FILES_SCAN, 0L)

    fun setLastAllFilesScan(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_LAST_ALL_FILES_SCAN, value).apply()
    }

    fun lastDownloadsScan(context: Context): Long =
        prefs(context).getLong(KEY_LAST_DOWNLOADS_SCAN, 0L)

    fun setLastDownloadsScan(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_LAST_DOWNLOADS_SCAN, value).apply()
    }

    fun ensureBaseline(context: Context) {
        if (lastMediaScan(context) == 0L) {
            setLastMediaScan(context, System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        }
    }

    fun resetBaseline(context: Context) {
        setLastMediaScan(context, System.currentTimeMillis())
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
