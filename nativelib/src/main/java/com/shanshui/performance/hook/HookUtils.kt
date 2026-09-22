package com.shanshui.performance.hook

import com.bytedance.shadowhook.ShadowHook
import com.bytedance.shadowhook.ShadowHook.ConfigBuilder


/**
 * @author mashanshui
 * @since 2025/12/23
 */
object HookUtils {
    fun initHook() {
//        ByteHook.init()
        ShadowHook.init(
            ConfigBuilder()
                .setMode(ShadowHook.Mode.UNIQUE)
                .setDebuggable(true)
                .build()
        )
    }
}