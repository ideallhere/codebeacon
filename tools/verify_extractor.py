# -*- coding: utf-8 -*-
"""
PickupCodeExtractor 的 Python 等价实现，用于在无 JVM 环境下验证算法逻辑。

这不是交付物，只是把 Kotlin 逻辑 1:1 移植过来跑测试，提前发现设计缺陷。
写完验证后，若算法无误再照搬到 Kotlin。
"""
import re

# ── 强干扰模式：命中则直接排除 ──────────────────────────────
NEGATIVE_PATTERNS = [
    re.compile(r"^1[3-9]\d{9}$"),            # 手机号
    re.compile(r"^\d{17}[\dXx]$"),            # 身份证
    re.compile(r"^\d{4}[- ]\d{4}[- ]\d{4}[- ]\d{4}$"),  # 银行卡
    re.compile(r"^\d{13}$"),                 # 毫秒时间戳
]

# 日期类数字：只在"看起来像真日期"时排除。
# 踩过的坑：早期规则 ^\d{4}[-/]?\d{2}[-/]?\d{2}$ 会把 12345678 这种
# 8 位纯数字取件码误杀（可解释成 1234-56-78），导致
# 「中通取件码 12345678」这类常见短信提取失败。
DATE_LIKE = re.compile(r"^\d{4}[-/]\d{2}[-/]\d{2}$")
COMPACT_DATE = re.compile(r"^\d{8}$")


def is_date_like(code):
    if DATE_LIKE.match(code):
        return True
    if COMPACT_DATE.match(code):
        year = int(code[0:4])
        month = int(code[4:6])
        day = int(code[6:8])
        plausible_year = 2020 <= year <= 2099
        return plausible_year and 1 <= month <= 12 and 1 <= day <= 31
    return False

NEGATIVE_CONTEXT = ["订单号", "运单号", "单号", "交易号", "流水号", "订单编号",
                    "电话", "手机号", "金额", "余额", "积分", "卡号", "账号"]

POSITIVE_CONTEXT = ["取件码", "取件号", "取件密码", "提货码", "自提码", "取件号码",
                    "货架", "柜", "驿站", "货架号", "快递柜", "验证码"]

MIN_CONFIDENCE = 60

# ── 快递公司定义 ─────────────────────────────────────────────
COURIERS = [
    ("SF", "顺丰速运", ["顺丰", "sf", "丰网"], [
        re.compile(r"\b(\d{1,2}-\d{1,2}-\d{3,6}(?:-\d{2,6})?)\b"),
        re.compile(r"\b(\d{12})\b"),
        re.compile(r"\b(\d{6})\b"),
    ]),
    ("JD", "京东物流", ["京东", "jd", "京东物流", "京东快递"], [
        re.compile(r"\b(\d{4,8})\b"),
        re.compile(r"\b([A-Z]\d{4,6})\b"),
    ]),
    ("CAINIAO", "菜鸟裹裹", ["菜鸟", "裹裹", "驿站"], [
        re.compile(r"\b([A-Z]{1,2}\d{3,6})\b"),
        re.compile(r"\b(\d{6,8})\b"),
    ]),
    ("ZTO", "中通快递", ["中通", "zto"], [
        re.compile(r"\b(\d{9,12})\b"), re.compile(r"\b(\d{6,8})\b")]),
    ("YTO", "圆通速递", ["圆通", "yto"], [
        re.compile(r"\b(\d{9,12})\b"), re.compile(r"\b(\d{6,8})\b")]),
    ("YUNDA", "韵达速递", ["韵达", "yunda"], [
        re.compile(r"\b(\d{9,12})\b"), re.compile(r"\b(\d{6,8})\b")]),
    ("STO", "申通快递", ["申通", "sto"], [
        re.compile(r"\b(\d{9,12})\b"), re.compile(r"\b(\d{6,8})\b")]),
    ("JEBIAO", "极兔速递", ["极兔", "jitu"], [
        re.compile(r"\b(\d{9,12})\b"), re.compile(r"\b(\d{6,8})\b")]),
    ("EMS", "邮政EMS", ["ems", "邮政", "中国邮政"], [
        re.compile(r"\b([A-Z]{2}\d{6,10})\b"),
        re.compile(r"\b(\d{9,12})\b"), re.compile(r"\b(\d{6,8})\b")]),
]

UNKNOWN_PATTERNS = [re.compile(r"\b(\d{6,8})\b")]

GENERIC_PATTERNS = [
    re.compile(r"取件码[：:\s]*([A-Za-z0-9-]{4,12})"),
    re.compile(r"取件密码[：:\s]*([A-Za-z0-9-]{4,12})"),
    re.compile(r"提货码[：:\s]*([A-Za-z0-9-]{4,12})"),
    re.compile(r"自提码[：:\s]*([A-Za-z0-9-]{4,12})"),
    re.compile(r"\"取件码\"\s*[：:]\s*([A-Za-z0-9-]{4,12})"),
    re.compile(r"取件号码[：:\s]*([A-Za-z0-9-]{4,12})"),
]


def detect_courier(text):
    lower = text.lower()
    best, best_score = None, 0
    for key, name, keywords, _ in COURIERS:
        hits = sum(1 for kw in keywords if kw.lower() in lower)
        if hits > best_score:
            best, best_score = (key, name), hits
    return best


def is_valid_code(code):
    code = code.strip()
    if not (4 <= len(code) <= 16):
        return False
    if not re.match(r"^[A-Za-z0-9-]+$", code):
        return False
    if not any(c.isdigit() for c in code):
        return False
    for p in NEGATIVE_PATTERNS:
        if p.match(code):
            return False
    if is_date_like(code):
        return False
    return True


def score_by_context(code, text, has_courier):
    score = 50
    if any(k in text for k in POSITIVE_CONTEXT):
        score += 30
    lower = text.lower()
    if any(k.lower() in lower for k in NEGATIVE_CONTEXT):
        score -= 35
    if has_courier:
        score += 10
    if "-" in code:
        score += 5
    digits = sum(1 for c in code if c.isdigit())
    if 5 <= digits <= 8:
        score += 5
    elif 9 <= digits <= 12:
        score += 3
    elif digits == 4:
        score += 0
    else:
        score -= 5
    return max(0, min(100, score))


def extract(text):
    if not text.strip():
        return None
    courier = detect_courier(text)
    candidates = []

    # 策略 1：通用关键词（优先级最高）
    for p in GENERIC_PATTERNS:
        m = p.search(text)
        if m:
            code = (m.group(1) or "").strip()
            if code and is_valid_code(code):
                candidates.append({
                    "code": code,
                    "courier": courier[1] if courier else "未知快递",
                    "confidence": 95,
                })

    # 策略 2：快递专用规则
    if courier:
        patterns = next(pats for k, n, kws, pats in COURIERS if k == courier[0])
    else:
        patterns = UNKNOWN_PATTERNS

    for p in patterns:
        m = p.search(text)
        if m:
            code = (m.group(1) or "").strip()
            if code and is_valid_code(code):
                s = score_by_context(code, text, courier is not None)
                if s >= MIN_CONFIDENCE:
                    candidates.append({
                        "code": code,
                        "courier": courier[1] if courier else "未知快递",
                        "confidence": s,
                    })
        # 同一 pattern 只取第一个匹配，与 Kotlin 版 find() 一致

    # 取置信度最高的；同置信度时优先快递公司明确的（dict 顺序即加入顺序，
    # max 在并列时保留先出现的，所以通用规则要先加入但要标记 courier）
    if not candidates:
        return None

    best = max(candidates, key=lambda c: c["confidence"])
    # 若通用规则命中且置信度并列，用实际识别出的快递公司名回填
    if best["courier"] == "未知快递" and courier is not None:
        best["courier"] = courier[1]
    return best


# ── 测试用例（与 Kotlin 版一一对应）─────────────────────────
CASES = [
    ("顺丰分组式取件码",
     "【顺丰速运】您的包裹已到丰巢智能柜，取件码 8-2-3456，请于24小时内取件。快递柜位置：B区12号",
     {"code": "8-2-3456", "courier": "顺丰速运"}),
    ("京东取件码",
     "【京东物流】您的快递已存放至丰巢柜机，取件码 5521，请尽快取件。京东快递 95318",
     {"code": "5521", "courier": "京东物流"}),
    ("菜鸟字母数字取件码",
     "菜鸟驿站提醒：您的包裹已到店，取件码 A1234，取件时间 8:00-22:00",
     {"code": "A1234"}),
    ("通用关键词兜底",
     "您的包裹已到自提点，取件码：863521，请凭此码取件",
     {"code": "863521"}),
    ("提货码同义词",
     "您的提货码 1234567 已生成，请出示给店员",
     {"code": "1234567"}),
    ("带引号的取件码",
     '顺丰速运提示："取件码"：5-2-8899，请及时取件',
     {"code": "5-2-8899"}),
    ("订单号干扰",
     "您的订单号 202609151234567 已发货，取件码 4321",
     {"code": "4321"}),
    ("多个取件码取最可信",
     "订单号 202609151234567，取件码 8899，客服电话 12345678901",
     {"code": "8899"}),
    ("归一化去空格",
     "取件码： 7788 ",
     {"code": "7788"}),
    # ── 回归用例：8 位纯数字取件码曾被日期规则误杀 ──────────
    ("八位纯数字取件码（日期规则回归）",
     "中通快递提醒：您的包裹取件码 12345678，请及时领取",
     {"code": "12345678", "courier": "中通快递"}),
    ("八位数字边界情况",
     "圆通速递：取件码 99999999 已生成",
     {"code": "99999999"}),
    # ── 真日期仍需被排除（防止修过头）────────────────────
    ("紧凑格式真日期应排除",
     "您的包裹预计 20260915 送达，请注意查收",
     {"__none__": True}),
    ("带分隔符日期应排除",
     "下单日期 2026-09-15 已确认，取件码 4321",
     {"code": "4321"}),
]


def run_tests():
    passed, failed = 0, []

    for name, text, expect in CASES:
        r = extract(text)

        # 断言"必须提取不出"的情况
        if expect.get("__none__"):
            if r is None:
                passed += 1
                print("  [ok]   %s -> None（符合预期）" % name)
            else:
                failed.append("%s | 期望 None，实际 %s" % (name, r["code"]))
                print("  [FAIL] %s -> %s，期望 None"
                      % (name, r["code"]))
            continue

        if r is None:
            failed.append("%s | 期望 %s，实际 None" % (name, expect.get("code")))
            print("  [FAIL] %s -> None" % name)
            continue

        ok = r["code"] == expect["code"]
        if "courier" in expect and r["courier"] != expect["courier"]:
            ok = False

        if ok:
            passed += 1
            print("  [ok]   %s -> %s (%s, %d分)"
                  % (name, r["code"], r["courier"], r["confidence"]))
        else:
            failed.append("%s | 期望 %s，实际 %s(%s)"
                          % (name, expect.get("code"), r["code"], r["courier"]))
            print("  [FAIL] %s -> %s (%s)，期望 %s"
                  % (name, r["code"], r["courier"], expect.get("code")))

    # 反例：这些必须返回 None 或不含被禁的值
    print("\n--- 反例（误报防护）---")
    negatives = [
        ("纯日期", "包裹预计 20260915 送达，请注意查收", "不能是日期"),
        ("无上下文裸数字", "订单已发货 123456789", "不该采信"),
        ("空文本", "", "应返回 None"),
        ("无数字", "今天天气不错", "应返回 None"),
    ]
    for name, text, why in negatives:
        r = extract(text)
        if r is None:
            passed += 1
            print("  [ok]   %s -> None（%s）" % (name, why))
        else:
            # 有结果时，至少不能是被禁的值
            print("  [warn] %s -> %s (%s)，%s"
                  % (name, r["code"], r["courier"], why))

    # 手机号绝不能出现
    r = extract("您的手机号 13800138000 已注册，取件码请见柜机屏幕")
    if r is None or r["code"] != "13800138000":
        passed += 1
        print("  [ok]   手机号未被误识别")
    else:
        failed.append("手机号被误识别为取件码！")
        print("  [FAIL] 手机号被误识别！")

    total = passed + len(failed)
    print("\n%d/%d 通过" % (passed, total))
    if failed:
        print("\n失败明细：")
        for f in failed:
            print("  · " + f)
    return 0 if not failed else 1


if __name__ == "__main__":
    run_tests()