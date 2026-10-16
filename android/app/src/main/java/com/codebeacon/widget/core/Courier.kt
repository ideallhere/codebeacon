package com.codebeacon.widget.core

/**
 * 快递公司定义与码制规则。
 *
 * 每家快递的取件码格式不同：
 *  - 顺丰：8-2-3456（分组式）、或纯数字 12 位
 *  - 京东：纯数字 4-6 位
 *  - 菜鸟：A 开头字母 + 数字混合，或纯数字
 *  - 中通/圆通/韵达/申通/极兔：多为纯数字 6-12 位
 *  - 邮政EMS：字母 + 数字组合
 *
 * [keywords] 用于从通知正文判断是哪家快递，[patterns] 用于从文本中抽取具体的码。
 */
enum class Courier(
    val displayName: String,
    /** 出现这些关键词才认为是这家快递 */
    val keywords: List<String>,
    /** 抽取正则，组 1 即为取件码 */
    val patterns: List<Regex>,
    /** 图标字符（用于小部件显示，避免依赖图片资源） */
    val badge: String
) {
    SF(
        displayName = "顺丰速运",
        keywords = listOf("顺丰", "sf", "SF", "丰网"),
        patterns = listOf(
            // 分组式：8-2-3456 / 8-2-3456-7890
            Regex("""\b(\d{1,2}-\d{1,2}-\d{3,6}(?:-\d{2,6})?)\b"""),
            // 12 位纯数字（无分隔）
            Regex("""\b(\d{12})\b"""),
            // 6 位纯数字
            Regex("""\b(\d{6})\b""")
        ),
        badge = "顺"
    ),

    JD(
        displayName = "京东物流",
        keywords = listOf("京东", "jd", "JD", "京东物流", "京东快递"),
        patterns = listOf(
            Regex("""\b(\d{4,8})\b"""),
            Regex("""\b([A-Z]\d{4,6})\b""")
        ),
        badge = "京"
    ),

    CAINIAO(
        displayName = "菜鸟裹裹",
        keywords = listOf("菜鸟", "裹裹", "驿站", "cn"),
        patterns = listOf(
            // A 开头字母 + 数字：A1234 / AB12345
            Regex("""\b([A-Z]{1,2}\d{3,6})\b"""),
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "菜"
    ),

    ZTO(
        displayName = "中通快递",
        keywords = listOf("中通", "zto", "ZTO"),
        patterns = listOf(
            Regex("""\b(\d{9,12})\b"""),
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "中"
    ),

    YTO(
        displayName = "圆通速递",
        keywords = listOf("圆通", "yto", "YTO"),
        patterns = listOf(
            Regex("""\b(\d{9,12})\b"""),
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "圆"
    ),

    YUNDA(
        displayName = "韵达速递",
        keywords = listOf("韵达", "yunda", "YUNDA"),
        patterns = listOf(
            Regex("""\b(\d{9,12})\b"""),
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "韵"
    ),

    STO(
        displayName = "申通快递",
        keywords = listOf("申通", "sto", "STO", "申通快递"),
        patterns = listOf(
            Regex("""\b(\d{9,12})\b"""),
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "申"
    ),

    JEBIAO(
        displayName = "极兔速递",
        keywords = listOf("极兔", "jitu", "JITU", "极兔速递"),
        patterns = listOf(
            Regex("""\b(\d{9,12})\b"""),
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "兔"
    ),

    EMS(
        displayName = "邮政EMS",
        keywords = listOf("EMS", "ems", "邮政", "中国邮政", "邮政快递"),
        patterns = listOf(
            // 字母 + 9位数字：EA123456789
            Regex("""\b([A-Z]{2}\d{6,10})\b"""),
            Regex("""\b(\d{9,12})\b"""),
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "邮"
    ),

    UNKNOWN(
        displayName = "未知快递",
        keywords = emptyList(),
        patterns = listOf(
            Regex("""\b(\d{6,8})\b""")
        ),
        badge = "?"
    );

    companion object {
        /** 未知快递的兜底规则：用「取件码」等关键词泛化匹配 */
        val GENERIC_PATTERNS = listOf(
            Regex("""取件码[：:\s]*([A-Za-z0-9-]{4,12})"""),
            Regex("""取件密码[：:\s]*([A-Za-z0-9-]{4,12})"""),
            Regex("""提货码[：:\s]*([A-Za-z0-9-]{4,12})"""),
            Regex("""自提码[：:\s]*([A-Za-z0-9-]{4,12})"""),
            Regex(""""取件码"\s*[：:]\s*([A-Za-z0-9-]{4,12})"""),
            Regex("""取件号码[：:\s]*([A-Za-z0-9-]{4,12})""")
        )

        /**
         * 根据文本识别快递公司。
         * 优先匹配关键词更多的公司（避免「顺丰」被「丰」误配等）。
         */
        fun detect(text: String): Courier? {
            val lower = text.lowercase()
            return entries
                .filter { it != UNKNOWN }
                .filter { c -> c.keywords.any { kw -> lower.contains(kw.lowercase()) } }
                // 关键词命中数多的优先
                .maxByOrNull { c -> c.keywords.count { kw -> lower.contains(kw.lowercase()) } }
        }
    }
}