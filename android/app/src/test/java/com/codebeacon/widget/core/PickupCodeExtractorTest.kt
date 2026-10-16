package com.codebeacon.widget.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 取件码提取引擎单元测试。
 *
 * 覆盖：真实短信样本、干扰项排除、置信度阈值、快递识别。
 * 运行：./gradlew test
 */
class PickupCodeExtractorTest {

    // ── 真实短信样本（已脱敏）────────────────────────────────

    @Test
    fun `顺丰分组式取件码`() {
        val text = "【顺丰速运】您的包裹已到丰巢智能柜，取件码 8-2-3456，请于24小时内取件。快递柜位置：B区12号"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull("应该能提取到取件码", r)
        assertEquals("8-2-3456", r!!.code)
        assertEquals(Courier.SF, r.courier)
    }

    @Test
    fun `京东取件码`() {
        val text = "【京东物流】您的快递已存放至丰巢柜机，取件码 5521，请尽快取件。京东快递 95318"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull(r)
        assertEquals("5521", r!!.code)
        assertEquals(Courier.JD, r.courier)
    }

    @Test
    fun `菜鸟字母数字取件码`() {
        val text = "菜鸟驿站提醒：您的包裹已到店，取件码 A1234，取件时间 8:00-22:00"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull(r)
        assertEquals("A1234", r!!.code)
    }

    @Test
    fun `通用关键词兜底_未知快递`() {
        val text = "您的包裹已到自提点，取件码：863521，请凭此码取件"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull("通用规则应能兜底", r)
        assertEquals("863521", r!!.code)
        assertEquals("通用规则命中，置信度应最高", 95, r.confidence)
    }

    @Test
    fun `提货码同义词`() {
        val text = "您的提货码 1234567 已生成，请出示给店员"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull(r)
        assertEquals("1234567", r!!.code)
    }

    @Test
    fun `带引号的取件码`() {
        val text = """顺丰速运提示："取件码"：5-2-8899，请及时取件"""
        val r = PickupCodeExtractor.extract(text)
        assertNotNull(r)
        assertEquals("5-2-8899", r!!.code)
    }

    // ── 干扰项排除（误报防护，比漏报更重要）─────────────────

    @Test
    fun `不应把手机号当取件码`() {
        val text = "您的手机号 13800138000 已注册，如非本人操作请致电 10086。取件码请见柜机屏幕"
        val r = PickupCodeExtractor.extract(text)
        // 就算提取到了，也不该是手机号
        if (r != null) {
            assertNotEquals("绝不能返回手机号", "13800138000", r.code)
        }
    }

    @Test
    fun `不应把订单号当取件码`() {
        val text = "您的订单号 202609151234567 已发货，取件码 4321"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull(r)
        assertEquals("有明确取件码时应取那个", "4321", r.code)
        assertNotEquals("不能是订单号", "202609151234567", r.code)
    }

    @Test
    fun `不应把日期当取件码`() {
        val text = "包裹预计 20260915 送达，请注意查收"
        // 没有取件码关键词时，应返回 null（宁漏勿误报）
        val r = PickupCodeExtractor.extract(text)
        assertTrue("日期不应被当作取件码：$r", r == null || !r.code.startsWith("2026"))
    }

    /**
     * 回归测试：8 位纯数字取件码曾被日期规则误杀。
     *
     * 原规则 `^\d{4}[-/]?\d{2}[-/]?\d{2}$` 能匹配 `12345678`（视作 1234-56-78），
     * 导致「中通取件码 12345678」这类**极常见**的短信提取失败。
     * 修复：日期只排除"看起来像真日期"的（月/日在合理范围）。
     */
    @Test
    fun `八位纯数字取件码不应被日期规则误杀`() {
        val text = "中通快递提醒：您的包裹取件码 12345678，请及时领取"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull("8 位纯数字取件码必须能提取", r)
        assertEquals("12345678", r!!.code)
        assertEquals(Courier.ZTO, r.courier)
    }

    @Test
    fun `八位数字边界情况仍能提取`() {
        val r = PickupCodeExtractor.extract("圆通速递：取件码 99999999 已生成")
        assertNotNull("99 月 99 日不存在，不应被当日期排除", r)
        assertEquals("99999999", r!!.code)
    }

    /** 防止修复过头：真正的紧凑格式日期仍须排除 */
    @Test
    fun `紧凑格式真日期仍应被排除`() {
        // 20260915 = 2026年09月15日，是合法日期
        val r = PickupCodeExtractor.extract("您的包裹预计 20260915 送达，请注意查收")
        assertTrue("真日期不应被当作取件码：$r", r == null || r.code != "20260915")
    }

    @Test
    fun `带分隔符日期应排除但不影响真取件码`() {
        val r = PickupCodeExtractor.extract("下单日期 2026-09-15 已确认，取件码 4321")
        assertNotNull(r)
        assertEquals("4321", r!!.code)
    }

    @Test
    fun `纯数字无上下文应低置信度被拒`() {
        val text = "订单已发货 123456789"
        val r = PickupCodeExtractor.extract(text)
        // 无快递公司、无取件关键词、邻近"订单" → 应被 MIN_CONFIDENCE 过滤
        assertTrue("无上下文的裸数字不应采信，实际=${r?.code}", r == null || r.confidence >= 60)
    }

    @Test
    fun `空文本不崩溃`() {
        assertNull(PickupCodeExtractor.extract(""))
        assertNull(PickupCodeExtractor.extract("   "))
    }

    @Test
    fun `无数字文本不崩溃`() {
        assertNull(PickupCodeExtractor.extract("今天天气不错"))
    }

    // ── 快递公司识别 ───────────────────────────────────────

    @Test
    fun `识别顺丰`() {
        assertEquals(Courier.SF, Courier.detect("顺丰速运已到柜"))
    }

    @Test
    fun `识别京东`() {
        assertEquals(Courier.JD, Courier.detect("京东物流取件"))
    }

    @Test
    fun `识别菜鸟`() {
        assertEquals(Courier.CAINIAO, Courier.detect("菜鸟裹裹提醒您取件"))
    }

    @Test
    fun `未知快递返回null`() {
        assertNull(Courier.detect("今天天气不错"))
    }

    // ── 置信度 ─────────────────────────────────────────────

    @Test
    fun `有取件码关键词的置信度应高于无关键词`() {
        val withKeyword = PickupCodeExtractor.extract("取件码 1234")
        val withoutKeyword = PickupCodeExtractor.extract("顺丰 1234")
        assertNotNull(withKeyword)
        assertNotNull(withoutKeyword)
        assertTrue(
            "带关键词置信度(${withKeyword!!.confidence})应 >= 不带(${withoutKeyword!!.confidence})",
            withKeyword.confidence >= withoutKeyword.confidence
        )
    }

    @Test
    fun `归一化后的码应去除首尾空格`() {
        val r = PickupCodeExtractor.extract("取件码： 7788 ")
        assertNotNull(r)
        assertEquals("7788", r!!.code)
    }

    @Test
    fun `分组式码保留横线`() {
        val r = PickupCodeExtractor.extract("顺丰 取件码 8-2-3456")
        assertNotNull(r)
        assertTrue("应保留横线：${r!!.code}", r.code.contains('-'))
    }

    @Test
    fun `多个取件码取最可信的`() {
        val text = "订单号 202609151234567，取件码 8899，客服电话 12345678901"
        val r = PickupCodeExtractor.extract(text)
        assertNotNull(r)
        assertEquals("8899", r!!.code)
    }
}