package com.codebeacon.widget.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.codebeacon.widget.core.PickupCodeExtractor
import com.codebeacon.widget.data.PickupCodeStore
import com.codebeacon.widget.widget.CodeWidgetProvider

/**
 * 通知监听服务 —— 取件码提取的触发入口。
 *
 * ## 为什么用通知监听而不是短信权限（重要）
 *
 * Android 13+ `READ_SMS` 仅限"默认短信应用"，Android 14+ **完全禁止**非默认应用读取短信。
 * `SmsRetriever` 依赖 Google Play 服务，国产 ROM（含 MIUI）不可用。
 *
 * 但取件码短信**必然会发通知**，通知正文里就有码。所以通知监听是唯一可行方案：
 *  - 权限由用户在设置页手动授予，无需运行时弹窗
 *  - 不受 Google Play 短信政策限制
 *  - 同一套机制顺带覆盖**微信、支付宝**里的取件通知
 *
 * ## Android 15 的 OTP 脱敏
 *
 * Android 15 起，系统检测到通知含 OTP（一次性密码）时，会把内容替换成
 * "sensitive notification content hidden"（各语言文案不同）。
 *
 * 取件码语义上**不是 OTP**，多数情况能正常读取。但必须做检测与降级，
 * 否则用户会遇到"没任何错误提示就是不工作"，是最难排查的体验。
 */
class CodeListenerService : NotificationListenerService() {

    private val store by lazy { PickupCodeStore(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // 来源过滤：只关心配置过的应用
        val pkg = sbn.packageName ?: return
        if (!SourceFilter.isWatching(this, pkg)) return

        // 跳过自己的通知，避免自触发
        if (pkg == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 拼接标题、正文、大文本 —— 取件码可能在任意一处
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        // 非标准通知（自定义 RemoteViews）需要遍历 View 才能取到文本
        val custom = extractFromCustomView(notification)

        val fullText = listOf(title, text, bigText, subText, custom)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullText.isBlank()) return

        // ── 脱敏检测（Android 15+）──────────────────────────
        if (RedactedDetector.isRedacted(fullText)) {
            Log.w(TAG, "通知内容被系统脱敏（疑似 OTP），无法提取：pkg=$pkg")
            // 不静默失败 —— 记录下来，让 UI 能告知用户原因
            store.insert(
                PickupCodeStore.PickupCode(
                    code = REDACTED_PLACEHOLDER,
                    courierName = "",
                    confidence = 0,
                    sourcePackage = pkg,
                    createdAt = sbn.postTime,
                    rawSnippet = null
                )
            ) { CodeWidgetProvider.refreshAll(applicationContext) }
            return
        }

        // ── 提取取件码 ─────────────────────────────────────
        val result = PickupCodeExtractor.extract(fullText)
        if (result == null) {
            Log.d(TAG, "未提取到取件码：pkg=$pkg, text=${fullText.take(60)}")
            return
        }

        Log.i(TAG, "提取成功：${result.courier.displayName} ${result.code} (${result.confidence}分)")

        store.insert(
            PickupCodeStore.PickupCode(
                code = result.code,
                courierName = result.courier.displayName,
                confidence = result.confidence,
                sourcePackage = pkg,
                createdAt = sbn.postTime,
                rawSnippet = result.matchedSnippet
            )
        ) { id ->
            if (id > 0) CodeWidgetProvider.refreshAll(applicationContext)
        }
    }

    /**
     * 从自定义 RemoteViews 通知中提取文本。
     *
     * 很多 App（尤其微信早期版本、部分电商）用 `setCustomContentView` 发通知，
     * 标准 extras 里取不到正文，必须把 RemoteViews 反序列化成 View 再遍历。
     *
     * 注意：RemoteViews.apply() 是重操作，只在标准字段全空时才调用。
     */
    private fun extractFromCustomView(notification: android.app.Notification): String {
        val sb = StringBuilder()
        try {
            val views = arrayOf(
                notification.contentView,
                notification.bigContentView
            )
            for (rv in views) {
                if (rv == null) continue
                val view = try {
                    rv.apply(this, null)
                } catch (t: Throwable) {
                    // apply 可能因版本不兼容抛异常，必须兜住
                    Log.d(TAG, "RemoteViews.apply 失败：${t.message}")
                    null
                } ?: continue

                collectText(view, sb)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "提取自定义通知失败：${t.message}")
        }
        return sb.toString()
    }

    /** 递归遍历 View 收集所有 TextView 文本 */
    private fun collectText(view: android.view.View, sb: StringBuilder) {
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                collectText(view.getChildAt(i), sb)
            }
        } else if (view is android.widget.TextView) {
            view.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                sb.append(it).append(" ")
            }
        }
    }

    companion object {
        private const val TAG = "CodeListener"

        /** 脱敏时的占位码 */
        const val REDACTED_PLACEHOLDER = "__REDACTED__"
    }
}