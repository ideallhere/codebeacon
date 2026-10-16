# 取件码信标 CodeBeacon

自动识别快递取件码的 Android 桌面小部件。收到短信、微信、支付宝里的取件通知，
桌面自动显示，点一下就复制。

---

## 技术架构

小部件必须是原生（`RemoteViews` 不支持 Flutter 视图），管理界面用 Flutter。

```
┌───────────────────────────────────────┐
│  Kotlin 核心                            │
│  ├─ CodeListenerService                 │  ← NotificationListenerService
│  │   监听通知 → 提取 → 存库             │
│  ├─ PickupCodeExtractor                │  ← 9 家快递规则 + 置信度引擎
│  ├─ CodeWidgetProvider                 │  ← AppWidgetProvider + RemoteViews
│  ├─ RedactedDetector                   │  ← Android 15 脱敏检测
│  └─ AppBridge                          │  ← MethodChannel
├───────────────────────────────────────┤
│  Flutter 界面                           │
│  ├─ 主界面（最新码 + 待取列表）           │
│  ├─ 归档页（记事本）                     │
│  └─ 设置页 / 监听应用                   │
└───────────────────────────────────────┘
```

---

## 为什么用通知监听而不是短信权限

| 方案 | 结论 |
|---|---|
| `READ_SMS` 读短信库 | ❌ Android 13+ 仅默认短信应用可读，**14+ 完全禁止** |
| `SmsRetriever` | ❌ 依赖 Google Play 服务，MIUI 等国产 ROM 不可用 |
| **`NotificationListenerService`** | ✅ **唯一可行** |

取件码短信**必然会发通知**，通知正文里就有码。而且同一套机制顺带覆盖
微信、支付宝里的取件通知，不用额外权限。

---

## 权限

**V1 只需一项**：`BIND_NOTIFICATION_LISTENER_SERVICE`（用户在设置页手动开启）。

刻意**不**申请：
- ❌ `READ_SMS` / `RECEIVE_SMS` — 13+ 拿不到，14+ 禁止
- ❌ `INTERNET` — V1 全本地，取件码不出设备
- ❌ `QUERY_ALL_PACKAGES` — 只查已知包名

---

## 已实现的取件码提取规则

支持 9 家快递：顺丰、京东、菜鸟、中通、圆通、韵达、申通、极兔、邮政EMS。

**提取策略**（按优先级）：
1. **通用关键词** — `取件码`/`取件密码`/`提货码`/`自提码` + 码值（置信度 95）
2. **快递专用规则** — 各家码制不同（顺丰支持 `8-2-3456` 分组式）

**误报防护**（宁漏勿误报）：
- 排除手机号、身份证、银行卡、日期、毫秒时间戳
- 附近出现「订单号/运单号/电话/金额」等词 → 降权 35 分
- 置信度低于 60 一律不采信

算法逻辑已验证 **14/14 用例通过**（`tools/verify_extractor.py`）。

---

## 已知技术限制（诚实标注）

### 1. Android 15 OTP 脱敏

系统检测到通知含 OTP 时，会把内容替换成「敏感通知内容已隐藏」。
取件码通常**不属于** OTP，多数能读到。已实现
`RedactedDetector` —— 用反射读取当前系统语言的脱敏文案做比对，
识别到就在界面上明确提示，而不是静默失败。

### 2. 小部件无法做「顺滑下移」动画

小部件视图由系统 Launcher 进程渲染，**无法施加布局动画**（架构性限制）。

| 场景 | 效果 |
|---|---|
| 应用内列表 | ✅ 真实的下移 + 淡出（Flutter 动画） |
| 桌面小部件 | 内容直接切换为下一条 |

### 3. 无法静默写入手机自带备忘录

华为/小米/OPPO/vivo 的备忘录均**未开放写入接口**，Android 也无标准 API。
已实现为：导出 `.txt` 到手机「文档」目录 / 分享给备忘录应用。

### 4. 进程被杀需重开

通知监听服务依赖进程存活。被系统回收后需重新打开 App。

---

## 项目结构

```
codebeacon/
├── lib/
│   ├── main.dart              入口
│   ├── bridge.dart            MethodChannel 客户端 + 数据模型
│   ├── home_page.dart         主界面（含顺滑下移动画）
│   ├── archive_page.dart      归档页（记事本）
│   └── settings_page.dart     设置页 + 监听应用
├── android/app/src/
│   ├── java/com/codebeacon/widget/
│   │   ├── core/
│   │   │   ├── Courier.kt              9 家快递规则定义
│   │   │   └── PickupCodeExtractor.kt  提取引擎（置信度 + 防误报）
│   │   ├── data/
│   │   │   └── PickupCodeStore.kt      SQLite 存储（去重/归档）
│   │   ├── listener/
│   │   │   ├── CodeListenerService.kt  通知监听入口
│   │   │   ├── SourceFilter.kt         来源过滤 + 权限检测
│   │   │   └── RedactedDetector.kt     Android 15 脱敏检测
│   │   ├── widget/
│   │   │   ├── CodeWidgetProvider.kt   小部件 Provider
│   │   │   └── WidgetAnimator.kt       动画工具
│   │   └── bridge/
│   │       ├── AppBridge.kt            MethodChannel 服务端
│   │       ├── MainActivity.kt         插件注册（关键！）
│   │       └── MemoExporter.kt         记事本导出
│   ├── res/                             布局/资源/深色模式
│   └── src/test/                       Kotlin 单元测试
└── tools/
    ├── verify_extractor.py      提取引擎验证（14 用例）
    └── check_consistency.py     混编一致性检查（6 项）
```

---

## 验证状态

| 项目 | 状态 |
|---|---|
| 提取引擎算法 | ✅ **14/14 通过**（Python 等价实现验证） |
| Kotlin 单元测试 | 📄 已写 16 个用例，**待 JVM 执行** |
| 混编契约一致性 | ✅ **6/6 通过**（通道名/方法/参数/类/资源/插件注册） |
| Dart 语法 | ✅ 5/5 括号配对通过 |
| **实际编译** | ❌ **未执行** —— 本机无 Flutter/JDK/Android SDK |
| **真机运行** | ❌ 未执行 |

---

## 构建

```bash
flutter pub get
cd android
# 若要用签名，创建 android/key.properties
flutter build apk --release
```

### 环境要求

- Flutter 3.27+
- JDK 17
- Android SDK，compileSdk 34
- AGP 8.5.2

### CI 云端构建

已配置 GitHub Actions（`.github/workflows/build-apk.yml`），
只出 `arm64-v8a` 单架构。

---

## 使用步骤

1. 安装 APK 后打开应用
2. 点击「立即开启」→ 在系统设置里**勾选通知使用权**
3. 长按桌面空白处 → 部件 → 找到「取件码」
4. 收到取件通知后，桌面自动显示取件码
5. 点一下小部件 → 复制到剪贴板
6. 点勾选 → 归档（在应用内「已取件归档」页）

---

## 后续规划

- **V2**：手机号登录主动查询（需运营商认证 SDK 付费服务 + 后端）
- **V2**：取件码到期提醒
- **V2**：小部件列表模式（需 RemoteViewsService collection）
- **V3**：多设备同步

---

*2026-10-15 · 依据 MIUI 小部件开发规范实现*