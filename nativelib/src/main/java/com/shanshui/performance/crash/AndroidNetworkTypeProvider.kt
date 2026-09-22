package com.shanshui.performance.crash

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

internal class AndroidNetworkTypeProvider(
    context: Context,
) : () -> String? {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun invoke(): String? {
        val manager = connectivityManager ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = manager.activeNetwork ?: return@runCatching null
                val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching null
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> null
                }
            } else {
                @Suppress("DEPRECATION")
                manager.activeNetworkInfo?.typeName?.lowercase()
            }
        }.getOrNull()
    }
}
