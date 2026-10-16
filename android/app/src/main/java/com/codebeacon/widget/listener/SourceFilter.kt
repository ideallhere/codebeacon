package com.codebeacon.widget.listener

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat

/**
 * 监听来源过滤。
 *
 * 默认监听：系统短信 + 微信 + 支付宝（覆盖绝大多数取件通知场景）。
 * 用户可在设置页调整。
 */
object SourceFilter {

    /** 系统短信应用候选包名（各家 ROM 不同） */
    private val SMS_PACKAGES = setOf(
        "com.google.android.apps.messaging",   // Google Messages
        "com.android.mms",                      // AOSP
        "com.samsung.android.messaging",        // 三星
        "com.miui.msgs",                        // 小米短信
        "com.coloros.sms",                      // OPPO
        "com.huawei.message",                   // 华为
        "com.tencent.mm"                        // 微信（顺带，因为取件通知常在微信）
    )

    /** 微信与支付宝 */
    val DEFAULT_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",          // 微信
        "com.eg.android.AlipayGphone", // 支付宝
    ) + SMS_PACKAGES

    private const val PREFS = "codebeacon_sources"
    private const val KEY_DISABLED = "disabled_pkgs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否正在监听该包 */
    fun isWatching(context: Context, pkg: String): Boolean {
        // 显式关闭过的，不监听
        if (prefs(context).getStringSet(KEY_DISABLED, emptySet())?.contains(pkg) == true) {
            return false
        }
        return true
    }

    /** 用户手动关闭某应用 */
    fun disable(context: Context, pkg: String) {
        val current = prefs(context).getStringSet(KEY_DISABLED, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        current.add(pkg)
        prefs(context).edit().putStringSet(KEY_DISABLED, current).apply()
    }

    /** 重新开启某应用 */
    fun enable(context: Context, pkg: String) {
        val current = prefs(context).getStringSet(KEY_DISABLED, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        current.remove(pkg)
        prefs(context).edit().putStringSet(KEY_DISABLED, current).apply()
    }

    /** 获取被关闭的集合，供 UI 显示 */
    fun disabledPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()

    /**
     * **通知使用权是否已授予** —— 这是本 App 最关键的权限。
     *
     * 未授予时 onNotificationPosted 永远不会回调，所以每次进主界面都要检查。
     */
    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }

    /** 展示已识别条数的友好名称 */
    fun displayName(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}