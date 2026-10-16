package com.codebeacon.widget.listener

import android.content.res.Resources

/**
 * Android 15+ OTP 脱敏检测。
 *
 * ## 背景
 *
 * Android 15 起，系统检测到通知含 OTP（一次性密码）时，会把内容替换成
 * 一句"内容已隐藏"的提示。对应系统资源 `redacted_notification_message`，
 * **各语言文案不同**（中文是"敏感通知内容已隐藏"之类）。
 *
 * 取件码语义上不是 OTP，绝大多数情况能正常读到。但一旦命中，若不做检测，
 * 用户会遇到"没报错但就是不工作"—— 这是最难排查的体验问题。
 *
 * ## 检测方式
 *
 * 不硬编码中英文文案（会漏掉其他语言），而是用反射读取**当前系统语言**下的
 * 实际文案，再做比对。这样在任何语言环境下都能正确识别。
 *
 * 局限：Android 未公开该机制，资源名可能变化。已做多资源名兜底。
 */
object RedactedDetector {

    /**
     * 系统里可能存在的脱敏提示资源名。
     * `redacted_notification_message` 是社区广泛验证的主资源名，
     * 其余为可能的变体，逐一尝试。
     */
    private val CANDIDATE_RES_NAMES = listOf(
        "redacted_notification_message",
        "notification_redacted_message",
        "otp_redacted_message"
    )

    /** 兜底的关键特征串：命中任一即认为是脱敏提示（应对资源名变化） */
    private val FALLBACK_MARKERS = listOf(
        "sensitive notification content hidden",
        "notification content hidden",
        "content hidden"
    )

    /** 缓存系统实际文案，避免每条通知都反射一次 */
    @Volatile
    private var cachedTexts: Set<String>? = null

    /**
     * 判断给定文本是否为系统的脱敏提示。
     *
     * @param text 通知内容全文
     * @return true = 这是一条脱敏通知，取件码提取不到
     */
    fun isRedacted(text: String): Boolean {
        if (text.isBlank()) return false

        // 取出当前系统的脱敏文案
        val systemTexts = getSystemRedactedTexts()

        // 精确匹配：文本就是那句脱敏提示
        val trimmed = text.trim()
        systemTexts.forEach { sysText ->
            if (trimmed.equals(sysText, ignoreCase = true)) return true
            // 有些系统会在前后加包裹字符
            if (trimmed.contains(sysText, ignoreCase = true) && sysText.length > 8) {
                return true
            }
        }

        // 兜底特征匹配（处理文本被截断的情况）
        val lower = trimmed.lowercase()
        if (FALLBACK_MARKERS.any { lower.contains(it) }) return true

        return false
    }

    /**
     * 读取系统当前语言下的脱敏提示文案。
     *
     * 用 Resources.getSystem() 拿到系统资源，再用 getIdentifier 按名字定位。
     * getIdentifier 在资源名变化时会返回 0，此时回退到特征匹配。
     */
    private fun getSystemRedactedTexts(): Set<String> {
        cachedTexts?.let { return it }

        val result = mutableSetOf<String>()
        try {
            val sysRes = Resources.getSystem()
            for (name in CANDIDATE_RES_NAMES) {
                try {
                    val resId = sysRes.getIdentifier(name, "string", "android")
                    if (resId != 0) {
                        result += sysRes.getString(resId)
                    }
                } catch (t: Throwable) {
                    // 单个资源名失败不影响其他，继续尝试
                }
            }
        } catch (t: Throwable) {
            Log.w("RedactedDetector", "读取系统资源失败：${t.message}")
        }

        // 始终把英文原文加进去，作为跨语言兜底
        result += FALLBACK_MARKERS

        cachedTexts = result
        return result
    }

    private object Log {
        fun w(tag: String, msg: String) = android.util.Log.w(tag, msg)
    }
}