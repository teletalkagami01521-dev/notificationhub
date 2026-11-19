package com.notifyhub

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkState {

    data class Profile(
        val metered: Boolean,
        val imageMax: Int,
        val imageQuality: Int,
        val videoMax: Int,
        val videoQuality: Int
    )

    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun canUpload(context: Context): Boolean =
        !MediaSettings.isWifiOnly(context) || !isMetered(context)

    fun profile(context: Context): Profile {
        return if (isMetered(context)) {
            Profile(
                metered = true,
                imageMax = 1280,
                imageQuality = 70,
                videoMax = 540,
                videoQuality = 70
            )
        } else {
            Profile(
                metered = false,
                imageMax = 1600,
                imageQuality = 82,
                videoMax = 720,
                videoQuality = 82
            )
        }
    }
}
