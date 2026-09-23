package com.shanshui.performance.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** 提供不依赖具体业务功能的当前网络类型判断。 */
class AndroidNetworkTypeProvider(
    /** 用于读取系统网络服务的宿主上下文。 */
    context: Context,
) : () -> String? {
    /** 应用级网络管理器，避免持有 Activity。 */
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 返回 wifi、cellular、ethernet 或 null。 */
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
