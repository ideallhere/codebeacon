package com.codebeacon.widget.core

/** 提取结果 */
data class ExtractionResult(
    val code: String,
    val courier: Courier,
    /** 置信度 0~100，低于 60 的不采信 */
    val confidence: Int,
    /** 命中的原文片段，便于调试 */
    val matchedSnippet: String
)

/**
 * 取件码提取引擎。
 *
 * 设计要点：
 *  1. **多级策略**：先按快递公司专用规则试，再用通用关键词规则兜底
 *  2. **置信度打分**：避免把订单号、手机号、日期误当取件码
 *  3. **强干扰词过滤**：排除明显不是取件码的数字（订单号、运单号长度特征）
 *  4. **去重**：同一通知内多个候选时取最可信的
 */
object PickupCodeExtractor {

    /** 明显不是取件码的模式 —— 命中则直接排除 */
    private val NEGATIVE_PATTERNS = listOf(
        // 11 位手机号
        Regex("""^1[3-9]\d{9}$"""),
        // 18 位身份证
        Regex("""^\d{17}[\dXx]$"""),
        // 银行卡（16-19 位，带分隔）
        Regex("""^\d{4}[- ]\d{4}[- ]\d{4}[- ]\d{4}$"""),
        // 毫秒时间戳（13 位）
        Regex("""^\d{13}$""")
    )

    /**
     * 日期类数字 —— **只在"看起来像真日期"时才排除**。
     *
     * 踩过的坑：早期规则 `^\d{4}[-/]?\d{2}[-/]?\d{2}$` 会把 `12345678` 这种
     * 8 位纯数字取件码误杀（可解释成 1234-56-78），导致
     * 「中通取件码 12345678」这类常见短信提取失败。
     *
     * 现在的判定规则：必须带分隔符，或月份/日期在合理范围内。
     * 这样 `20260915`（真日期，9 月 15 日）会被排除，
     * 而 `12345678`（56 月 78 日，不可能）会保留。
     */
    private val DATE_LIKE = Regex("""^\d{4}[-/]\d{2}[-/]\d{2}$""")
    private val COMPACT_DATE = Regex("""^\d{8}$""")

    private fun isDateLike(code: String): Boolean {
        // 带分隔符的日期，如 2026-09-15
        if (DATE_LIKE.matches(code)) return true

        // 紧凑格式 YYYYMMDD，且月/日必须合法
        if (COMPACT_DATE.matches(code)) {
            val year = code.substring(0, 4).toIntOrNull() ?: return false
            val month = code.substring(4, 6).toIntOrNull() ?: return false
            val day = code.substring(6, 8).toIntOrNull() ?: return false
            val plausibleYear = year in 2020..2099
            return plausibleYear && month in 1..12 && day in 1..31
        }

        return false
    }

    /** 附近出现这些词，说明这个数字大概率不是取件码 */
    private val NEGATIVE_CONTEXT = listOf(
        "订单号", "运单号", "单号", "交易号", "流水号", "订单编号",
        "电话", "手机号", "金额", "余额", "积分", "卡号", "账号"
    )

    /** 提示这是取件码的词 —— 出现则显著加分 */
    private val POSITIVE_CONTEXT = listOf(
        "取件码", "取件号", "取件密码", "提货码", "自提码", "取件号码",
        "货架", "柜", "驿站", "货架号", "快递柜", "验证码"
    )

    /**
     * 从通知文本中提取取件码。
     *
     * @param text 通知标题 + 正文的拼接文本
     * @return 提取结果；未找到返回 null
     */
    fun extract(text: String): ExtractionResult? {
        if (text.isBlank()) return null

        val courier = Courier.detect(text)
        val candidates = mutableListOf<ExtractionResult>()

        // 策略 1：通用关键词规则（优先级最高，最可靠）
        for (pattern in Courier.GENERIC_PATTERNS) {
            pattern.find(text)?.let { m ->
                val code = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (code.isNotEmpty() && isValidCode(code)) {
                    candidates += ExtractionResult(
                        code = code,
                        courier = courier ?: Courier.UNKNOWN,
                        confidence = 95,
                        matchedSnippet = m.value
                    )
                }
            }
        }

        // 策略 2：快递公司专用规则
        val target = courier ?: Courier.UNKNOWN
        for (pattern in target.patterns) {
            pattern.find(text)?.let { m ->
                val code = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (code.isNotEmpty() && isValidCode(code)) {
                    val score = scoreByContext(code, text, courier != null)
                    if (score >= MIN_CONFIDENCE) {
                        candidates += ExtractionResult(
                            code = code,
                            courier = target,
                            confidence = score,
                            matchedSnippet = m.value
                        )
                    }
                }
            }
        }

        // 取置信度最高的
        val best = candidates
            .filter { it.confidence >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence }
            ?: return null

        // 若通用规则命中但实际识别出了快递公司，回填公司名（避免显示"未知快递"）
        return if (best.courier == Courier.UNKNOWN && courier != null) {
            best.copy(courier = courier)
        } else {
            best
        }
    }

    /** 判断码本身是否合法（长度、字符集、非干扰模式） */
    private fun isValidCode(code: String): Boolean {
        val trimmed = code.trim()

        // 长度：4~12 位（兼容分组式顺丰码可能更长）
        if (trimmed.length !in 4..16) return false

        // 只能是字母数字和横线
        if (!trimmed.all { it.isLetterOrDigit() || it == '-' }) return false

        // 必须至少包含一个数字（纯字母几乎不可能是取件码）
        if (!trimmed.any { it.isDigit() }) return false

        // 排除强干扰模式
        if (NEGATIVE_PATTERNS.any { it.matches(trimmed) }) return false

        // 排除"看起来像日期"的数字（注意：只排除真日期，见 isDateLike 注释）
        if (isDateLike(trimmed)) return false

        return true
    }

    /**
     * 根据上下文打分。
     *
     * @param hasCourier 是否已识别出快递公司
     */
    private fun scoreByContext(code: String, text: String, hasCourier: Boolean): Int {
        var score = 50

        // 附近有"取件码"这类词 → 大幅加分
        if (POSITIVE_CONTEXT.any { text.contains(it) }) score += 30

        // 附近有"订单号"这类词 → 大幅减分
        val lowerText = text.lowercase()
        if (NEGATIVE_CONTEXT.any { lowerText.contains(it.lowercase()) }) score -= 35

        // 已识别快递公司 → 加分
        if (hasCourier) score += 10

        // 分组式码（带横线）通常是顺丰的，略微加分
        if (code.contains('-')) score += 5

        // 长度合理性：6 位最常见
        val len = code.filter { it.isDigit() }.length
        score += when {
            len in 5..8 -> 5
            len in 9..12 -> 3
            len == 4 -> 0
            else -> -5
        }

        return score.coerceIn(0, 100)
    }

    /** 低于此置信度不采信，宁可漏过也不误报 */
    const val MIN_CONFIDENCE = 60
}