# Google Play 上架申报说明（启明星 / Venus）

> 包名：`com.muxiao.Venus`　版本：0.0.7（versionCode 7）
> 文档日期：2026-09-04
> **发布前必须替换的占位符**：隐私政策中的 `[[CONTACT_EMAIL]]`、`[[GITHUB_USER]]`，以及政策托管 URL。

---

## 一、隐私权政策

| 项目 | 内容 |
|---|---|
| 中文版 | `docs/privacy-policy.html` |
| 英文版 | `docs/privacy-policy-en.html` |
| 托管要求 | Google Play「政策 → 应用内容 → 隐私权政策」需填写一个**公开可访问**的 URL。可用 GitHub Pages 托管仓库内 `docs/` 目录 |

政策中所有数据声明均已与源码逐项核对，依据文件：
`AndroidManifest.xml`、`common/DeviceUtils.java`、`common/HeaderManager.java`、
`User/UserManager.java`、`User/PasswordLogin.java`、`Setting/UpdateChecker.java`、
`common/MiHoYoBBSConstants.java`、`res/xml/network_security_config.xml`。

---

## 二、「应用访问权限」问卷答案

### 问题 1：您的应用是否有任何部分设有限制？

**答案：是（有部分受限）**

依据：除「设置」页外，核心功能全部依赖已登录的米游社 / HoYoLAB 账号。未添加账号时，签到、抽卡链接获取、用户管理均无法执行。

### 问题 2：涉及哪些凭据类型？

| 凭据类别 | 是否勾选 | 说明 |
|---|---|---|
| **账号登录详细信息**（邮箱、用户名、密码、单点登录） | ✅ **勾选** | 国服支持**账号密码登录**（`PasswordLogin.java`，RSA 本地加密后提交 `passport-api.mihoyo.com`）；国际服支持 **OAuth 网页登录**（`OAuthLoginActivity`）；国服另支持**扫码登录**（`UserLoginActivity`） |
| **需要在另一台设备上执行操作** | ✅ **勾选** | 国服扫码登录需用米游社 App 在**另一台设备**上扫码确认后，本应用才能取得登录态 |
| 推荐代码和/或二维码 | ⬜ 不勾选 | 本应用内的二维码是**登录二维码**（登录凭据载体），不是推荐码 / 兑换码，已归入上面两项。若控制台单独列出「二维码」类别，可一并勾选并在备注注明「登录用二维码」 |
| 一次性 PIN 码 / 两步验证 | ⬜ 不勾选 | 应用不处理短信验证码或 2FA |
| 生物识别验证信息 | ⬜ 不勾选 | 无 |
| 付款信息 | ⬜ 不勾选 | 无内购、无订阅、无付费墙、无会员层级 |

### ⚠️ 关键卡点：必须提供审核用测试账号

Google Play 规定：应用存在受限部分时，开发者**必须**提供可供审核人员登录的凭据，否则直接拒审。

由于本应用依赖真实米游社账号，建议按以下方案处理：

1. **注册一个专用的米游社测试账号**（切勿使用个人主账号，凭据会暴露给审核人员）。
2. 在「应用访问权限」的登录凭据栏填写**账号 + 密码**，并在备注中说明：「请选择国服 → 账号密码登录；如触发验证码，请使用提供的备用方式」。
3. **优先提供密码登录凭据，不要只提供扫码登录** —— 审核人员无法为扫码流程扫码，这是此类应用被拒的最常见原因。
4. 如担心账号安全，可在应用中增加**演示 / Demo 模式**（无需真实账号即可展示全部 UI 与流程），这是最稳妥的做法。
5. 同时提供简要操作说明（如何添加账号、如何执行签到）。

---

## 三、应用类别与标签

### 应用类别

**主类别：工具（Tools）**

理由：本应用是执行自动化任务的实用工具，不属于娱乐、社交、个性化或图书参考类。
**备选**：效率（Productivity）。若控制台认为「工具」不匹配，选「效率」。

### 标签（Tags，最多 5 个）

建议按优先级填写：

1. 工具
2. 自动化
3. 实用工具
4. 效率
5. 游戏助手

> 注：Google Play 标签为固定候选列表，实际名称以控制台下拉项为准。若「游戏助手」不可选，改为「任务管理」或留空第 5 个。**不要**选含「游戏」主类别含义的标签，避免被归入游戏分类。

---

## 四、数据安全表单（Data Safety）对照

### 开场问题：您的应用是否会收集或分享任何必须披露的用户数据类型？

**答案：是（Yes）**

依据：应用会把设备标识符与账号凭据**发送出设备**（发往米游社 / HoYoLAB 官方服务器）。按 Google 的定义，数据离开设备即构成「收集」，发送给第三方即构成「共享」——这与"是否上传到开发者自己的服务器"无关。

### 4.1 需要声明的数据类型（逐项子答案）

| 数据类型 | 收集 | 共享 | 短暂处理 | 用途 | 传输加密 | 可请求删除 |
|---|---|---|---|---|---|---|
| **设备或其他 ID**（OAID、本地生成的 UUID、`device_fp`） | ✅ 是 | ✅ 是（米游社 / HoYoLAB、极验） | ⬜ 否（会持久化并用于多次请求） | 应用功能；欺诈预防、安全与合规性 | ✅ 是 | ✅ 是 |
| **个人信息 → 用户 ID**（`mid`、`account_id`） | ✅ 是 | ✅ 是（米游社 / HoYoLAB） | ⬜ 否（存于 SharedPreferences） | 应用功能；账号管理 | ✅ 是 | ✅ 是 |
| **个人信息 → 其他**（`stoken` / `ltoken` / `cookie_token` / `cookie`） | ✅ 是 | ✅ 是（米游社 / HoYoLAB） | ⬜ 否（存于 SharedPreferences） | 应用功能；账号管理 | ✅ 是 | ✅ 是 |

**「短暂处理」（Ephemeral）为何选否**：Google 判定短暂处理需同时满足「仅存于内存、不为其他目的复用、不落盘、不发往网络持久化」。本应用的令牌与设备 ID 均会写入本地 `SharedPreferences` 并随后续请求反复发送，**不满足**任一条件。

**删除方式**：在应用内「用户管理」删除账号，或系统设置中清除应用数据 / 卸载应用。

### 4.2 不需要声明的数据类型

以下全部选**「否」**，逐项依据：

| 数据类型 | 为何不声明 |
|---|---|
| 位置信息（精确 / 大致） | 无定位权限，未集成定位 SDK |
| 个人信息 → 姓名 / 邮箱 / 住址 / 电话 / 种族 / 宗教信仰 / 性取向 | 均不采集。密码登录时输入的手机号/邮箱仅作为登录账号提交给米游社官方接口，**本应用不读取也不保存** |
| 财务信息（付款信息 / 购买记录 / 信用评分） | 无内购、无订阅、无支付 SDK |
| 健康与健身 | 不涉及 |
| 通讯录 / 日历 / 短信彩信 / 其他应用内消息 | 无对应权限 |
| 照片与视频 / 音频文件 / 文件与文档 | 背景图与裁剪图仅在设备本地处理，从不上传 |
| 应用活动（应用交互、搜索记录、已安装应用、用户生成内容） | 无埋点、无行为分析 SDK |
| 网页浏览记录 | 云游戏 WebView 内的浏览行为不在本应用采集范围 |
| **应用信息和性能 → 崩溃日志 / 性能诊断** | **未集成任何崩溃上报 SDK**；运行日志仅写入应用私有目录，不上传 |
| 应用信息和性能 → 其他应用性能数据 | 同上 |

### 4.3 三个容易填错的点

1. **不要因为"我们没有服务器"就选「否」**。Google 的「收集」定义是数据离开设备，发送给米游社同样算收集 + 共享。选「否」而实际有外发，属于虚假声明，是拒审与下架的直接原因。
2. **本地数据无需声明**。本地日志、主题/语言偏好、背景图等从不离开设备，不应计入。仅声明真正外发的三类。
3. **无需填写"账号删除网址"**。本应用不提供自有账号注册，表单中相应项留空。

### 4.4 用户可见说明文本（Data Safety 摘要）

Play 控制台在「数据安全」末尾要求填写一段**用户可见**说明，分两栏：
- **简短说明**（约 500 字以内，展示在商店详情页）
- **完整说明**（可展开查看的完整描述）

两段口径必须与 4.1 的勾选完全一致。下面给出**中文**（与你的沟通语言一致）与**英文**（用于英文商品详情，本应用含国际服）两版，直接复制粘贴即可。

#### 中文版

**简短说明（直接粘贴）**

```
本应用在您登录米游社 / HoYoverse 账号后，会收集设备标识符（如 OAID、设备指纹）与账号凭据（登录令牌、用户 ID），用于执行签到、抽卡链接等自动化任务。数据经加密传输，仅发送至米游社 / HoYoverse 官方服务器，用于应用功能与账号安全。您可随时在应用内删除账号或清除应用数据来移除这些信息。
```

**完整说明（直接粘贴）**

```
启明星（Venus）是一款面向米游社 / HoYoverse 社区的自动化辅助工具。当您添加并登录米游社 / HoYoverse 账号后，应用会为提供功能而向米游社官方服务器收集并传输以下数据：

1. 设备或其他 ID：包括 OAID（在支持的设备上）或本地生成的设备标识符，以及向米游社指纹服务获取的设备指纹（device_fp）。用于应用功能，以及欺诈防范、安全与合规（米游社风控机制）；在触发人机验证时，设备指纹也会由极验（GeeTest）验证码服务处理。

2. 个人信息 — 用户 ID（mid、account_id）：用于账号识别与管理。

3. 个人信息 — 其他（会话令牌，如 stoken、ltoken、cookie_token、cookie）：用于鉴权并维持登录态。

上述全部数据均经 HTTPS 加密传输，应用不会出售任何数据，也未集成任何广告、统计或崩溃上报 SDK。您的密码在设备端使用米游社 RSA 公钥加密后仅提交给米游社，本应用从不保存。仅存于设备本地的数据（本地日志、主题、背景图）不会被收集或共享。您可随时在「用户管理」中删除账号，或通过清除应用数据 / 卸载应用来移除全部数据。
```

#### 英文版

**Short description（paste directly）**

```
Venus collects device identifiers (such as OAID and device fingerprint) and account credentials (login tokens and user IDs) after you sign in with a miHoYo / HoYoverse account. This data is required to perform sign-in automation and other in-app tasks, is encrypted in transit, and is sent only to miHoYo's official servers for app functionality and account security. You can remove it anytime by deleting the account in the app or clearing app data.
```

**Full description（paste directly）**

```
Venus is an automation utility for the miHoYo / HoYoverse community. When you add and sign in with a miHoYo / HoYoverse account, the app collects and transmits the following data to miHoYo's official servers solely to provide its features:

1. Device or other IDs — including OAID (where available) or a locally generated identifier, and a device fingerprint (device_fp) obtained from miHoYo's fingerprint service. Used for app functionality and for fraud prevention, security, and compliance (miHoYo's risk-control). The device fingerprint is also processed by the GeeTest captcha service when verification is required.

2. Personal info — User IDs (mid, account_id) used for account identification and management.

3. Personal info — Other (session tokens such as stoken, ltoken, cookie_token, cookie) used to authenticate requests and keep you signed in.

All of the above is transmitted over HTTPS and is never sold. The app integrates no advertising, analytics, or crash-reporting SDKs. Your password is encrypted on-device with miHoYo's RSA public key and sent only to miHoYo; it is never stored by Venus. Data that stays only on your device (local logs, theme, wallpaper) is not collected or shared. You can delete your data at any time by removing the account in "User Management" or by clearing the app's data / uninstalling the app.
```

---

## 五、其他必填声明

| 项目 | 答案 |
|---|---|
| 广告 | 无广告，未集成任何广告 SDK |
| 目标受众 | 非儿童专用；不面向 13 岁以下儿童 |
| 敏感数据 / 健康类 | 不涉及 |
| 政府 / 政治类 | 不涉及 |
| 应用内账号删除 | 不提供自有账号，无需提供删除网址 |
| 数据删除请求联系方式 | 隐私政策中的联系邮箱 |

---

## 六、⚠️ 上架风险提示（务必提前评估）

以下问题可能导致拒审或下架，其中**第 1、2 项风险最高**：

### 1. 第三方服务条款冲突（高风险）

本应用通过逆向得到的**私有接口**与米哈游服务器交互，包括自实现的 DS 签名算法、动态下发的 salt 参数、以及设备指纹接口调用，并**自动化**执行签到。米哈游 / HoYoverse《米游社用户协议》通常禁止第三方自动化工具与外挂程序。

Google Play 政策要求应用不得违反第三方服务条款。这是同类工具被拒的**最主要原因**。
**建议**：准备一份说明，强调本应用仅为个人使用的任务自动化辅助、不修改游戏数据、不提供代练或商业化服务，并接受拒审风险。

### 2. 设备指纹伪装 / 欺骗行为（高风险）

`DeviceUtils.java` 中存在以下行为：
- 传感器数据（加速度计、磁力计、陀螺仪）返回**硬编码固定值**
- 序列号固定发送 `UNKNOWN`
- User-Agent 伪造成 `miHoYoBBS/版本号`、假装官方客户端（`X-Requested-With: com.mihoyo.hyperion`）

这些属于「伪装成官方客户端」，可能触碰 Play 的**欺骗行为（Deceptive Behavior）**与**仿冒（Impersonation）**政策。
**建议**：最稳妥的做法是移除伪造成分、如实上报真实设备特征；若保留，需准备接受被判定为规避检测的风险。

### 3. 账号密码登录（中风险）

Play 对「应用索要用户密码」高度敏感。当前实现在政策与代码层面是安全的（本地 RSA 加密、不留存密码），但审核人员未必能验证。
**建议**：在应用内登录页显著提示「密码仅在本地加密后直接提交至米游社官方接口，本应用不保存」，并在「应用访问权限」备注中重复说明。

### 4. 商标与版权（中风险）

- 应用名「启明星 / Venus」未使用米哈游商标 —— ✅ 无问题
- **但**商品详情、截图、宣传图中**不得**使用米哈游 Logo、官方美术素材或游戏角色立绘
- 描述中避免暗示「官方」「正版授权」等字样

### 5. 后台服务声明（低风险）

使用了 `FOREGROUND_SERVICE_DATA_SYNC` + `WAKE_LOCK` 后台执行签到。需在 Play 的**前台服务类型声明**中如实申报 `dataSync` 用途，并提供说明视频或截图。

### 6. 病毒误报（已在 README 记录）

OAID 库可能被部分安全软件标记为风险。建议提交时使用**不含 OAID 的构建变体**，或在申诉时提供 VirusTotal 无检出报告。

---

## 七、提交前检查清单

- [ ] 替换隐私政策中的 `[[CONTACT_EMAIL]]`、`[[GITHUB_USER]]` 占位符
- [ ] 将 `docs/` 下的政策页托管到公开 URL，填入 Play 控制台
- [ ] 注册专用米游社测试账号，在「应用访问权限」中填写**账号密码**凭据
- [ ] 按第二节填写应用访问权限问卷
- [ ] 按第三节选择类别「工具」与标签
- [ ] 按第四节填写数据安全表单
- [ ] 核验商品详情与截图无第三方商标 / 版权素材
- [ ] 申报前台服务类型 `dataSync`
- [ ] 用第六节/第七节的英文文本填写「登录详细信息」，替换测试账号占位符

---

## 八、登录详细信息（Login details）—— 英文原文，可直接粘贴

在 Play 控制台「应用内容 → 应用访问权限 → 登录详细信息」中，按下表填写。
**以下全为英文**，符合 Google 要求。粘贴前请替换 `[[TEST_ACCOUNT]]`、`[[TEST_PASSWORD]]`、`[[CONTACT_EMAIL]]`。

### 字段 1：Name（名称）

```
MiYouShe test account (CN server) - review only
```

### 字段 2：Username（用户名）

```
[[TEST_ACCOUNT]]
```

> 填写测试账号的**手机号或邮箱**（即米游社登录账号）。

### 字段 3：Password（密码）

```
[[TEST_PASSWORD]]
```

### 字段 4：All other information required to access your app

```
Venus is a task-automation utility for miHoYo's MiYouShe community
(CN server). Every feature except "Settings" requires a logged-in
MiYouShe account. The credentials above belong to a dedicated test
account created solely for this review; it has no payment method
attached and no real user data.

=== HOW TO LOG IN ===

1. Open the app, tap "Settings" (bottom navigation, far right).
2. Under "Server Type" / "Select Server:", choose "China".
   IMPORTANT: password login is supported on the China server only.
   If "Global" is selected, the Password tab is disabled.
3. Tap "User Management" (second tab from the left).
4. Tap "Add/Login User".
5. In the "Username (UID recommended)" field, type any local label,
   for example "review". NOTE: this is only a local nickname used to
   identify the account inside the app. It is NOT the MiYouShe
   account name.
6. Tap "Login", then in the login dialog select the "Password" tab
   (do NOT use the "QR Code" tab, which requires scanning with the
   MiYouShe app on a second device).
7. Enter the credentials provided above:
       Phone number / Email  ->  the Username field value
       Password              ->  the Password field value
8. Tap "Login". On success the account appears in the user list and
   all features become accessible.

=== BEFORE RUNNING TASKS ===

Stay on the "Settings" tab and enable at least one forum and at least
one game under the sign-in task configuration. Without these, the
tasks exit immediately with "please select at least one forum/game".
Then return to "Settings" and confirm the user is selected.

=== TESTING THE FEATURES ===

- "Home" tab: tap "Run Sign-In" to execute the daily sign-in tasks.
  Results appear in the "Task List" below.
- "Gacha Link" tab: retrieves the gacha record URL for the logged-in
  account. The "Cloud Game" entry inside this tab opens a WebView that
  requires a separate web login and may be skipped.

=== TROUBLESHOOTING ===

- GeeTest / aigis verification: if the app says the account requires
  captcha verification, the account has triggered miHoYo's risk
  control. Wait a few hours and retry, or email [[CONTACT_EMAIL]] for
  a fresh test account.
- Real-name or face verification: must be completed in the official
  MiYouShe app before Venus can use the account.
- SMS code / two-factor: the app does not handle SMS codes. If miHoYo
  requests one, complete it in the official app or browser first, then
  retry in Venus.
- Expired session ("cookie may have expired"): tap the account in
  "User Management" and re-login with the same credentials.

=== NOTES FOR THE REVIEWER ===

- The app contains no payment, subscription, membership tier,
  biometric login, or location-based restriction of any kind.
- The password is encrypted on-device with miHoYo's RSA public key and
  sent only to miHoYo's official endpoint (passport-api.mihoyo.com).
  Venus never stores it and never uploads it anywhere.
- Only session tokens (stoken / ltoken / cookie_token / mid) are
  saved, and only locally on the device.
- Developer contact for login issues: [[CONTACT_EMAIL]]
```

### 填写要点提示

- **必须先切「China」** —— 这是最容易被审核员漏掉、进而判定「无法访问」的一步。
- **应用内 Username 字段只是本地备注**，真正的米游社账号填在 Password 标签页的「Phone number / Email」里。这个区别务必在上面第 5、7 步写清楚，否则审核员会认为凭据错误。
- **不要只提供扫码登录** —— 审核员无法扫码，几乎必然导致拒审。
- **测试账号务必专用**，不要绑定支付方式或实名信息。

---

## 九、应用商店详情文案（Short / Full description）

> 这是 Google Play 商品详情页里「简短说明」（≤80 字）与「完整说明」（≤4000 字）两栏文本，**不是** Data Safety 那段。两栏口径需与第四节数据声明一致，且不得暗示「官方 / 正版授权」。

### 9.1 中文版（简体，主文案）

**简短说明（直接粘贴，≤80 字）**

```
一键完成米游社、HoYoLAB、森空岛每日签到与抽卡链接，支持多账号与国际服。
```

**完整说明（直接粘贴，≤4000 字）**

```
启明星（Venus）是一款面向米游社、HoYoLAB 与森空岛社区的第三方任务自动化工具，帮你一键完成每日社区的琐碎操作，省去手动点点的麻烦。

【主要功能】
• 每日签到：一键完成米游社的米游币签到与各游戏签到（原神、崩坏系列、绝区零、星穹铁道等），以及森空岛（明日方舟）签到
• 国际服支持：支持 HoYoLAB 国际版游戏签到
• 抽卡链接：登录后自动获取原神、绝区零的抽卡记录链接；部分游戏可通过云游戏入口获取
• 多账号管理：支持同时管理多个账号，分别登录、分别执行任务
• 后台与小组件：支持前台 / 后台执行签到任务，并提供桌面小组件快捷查看状态
• 多语言：内置简体中文、繁体中文、英文

【使用说明】
• 本应用需要你已有的米游社 / HoYoLAB / 森空岛账号。首次使用请在「用户管理」中添加账号并登录（国服支持账号密码或扫码登录，国际服为网页授权登录）。
• 所有任务均在设备本地发起，凭据仅用于与本应用所支持的官方服务器通信。

【隐私与安全】
• 我们不会收集你的密码：密码仅在设备端加密后提交给对应官方服务器，本应用不保存。
• 仅保存必要的登录态令牌（如 stoken、ltoken 等）于设备本地，用于保持登录与执行任务。
• 未集成任何广告、统计或崩溃上报 SDK。详细数据说明见应用内隐私权政策。

【声明】
启明星是独立的第三方工具，与米哈游（miHoYo / HoYoverse）、鹰角网络（森空岛 / Skland）等厂商无隶属或合作关系，并非官方应用。使用本应用请遵守相关社区的服务条款。
```

### 9.2 英文版（用于英文商品详情）

**Short description（paste directly, ≤80 chars）**

```
One-tap daily check-in for MiYouShe, HoYoLAB & Skland, plus gacha links. Multi-account.
```

**Full description（paste directly, ≤4000 chars）**

```
Venus is a third-party task-automation tool for the MiYouShe, HoYoLAB and Skland communities. It helps you complete daily community tasks with one tap instead of doing them by hand.

KEY FEATURES
• Daily check-in: one-tap MiYouShe forum-coin and game check-ins (Genshin Impact, Honkai series, Zenless Zone Zero, Honkai: Star Rail, and more), plus Skland (Arknights) check-in.
• International support: HoYoLAB global server game check-ins.
• Gacha links: automatically fetch your gacha-record links for Genshin Impact and Zenless Zone Zero after login; some titles are available via the cloud-game entry.
• Multi-account: manage several accounts, each logged in and run independently.
• Background & widget: run check-in tasks in the foreground or background, with a home-screen widget to view status.
• Multilingual: Simplified Chinese, Traditional Chinese and English.

HOW TO USE
• An existing MiYouShe / HoYoLAB / Skland account is required. On first launch, add and sign in to your account under "User Management" (CN server supports password or QR login; global server uses web OAuth).
• All tasks are initiated on your device; credentials are used only to communicate with the respective official servers.

PRIVACY & SECURITY
• We never collect your password: it is encrypted on-device and submitted only to the corresponding official server; Venus never stores it.
• Only the necessary login tokens (such as stoken, ltoken) are kept locally on your device to keep you signed in and run tasks.
• No advertising, analytics, or crash-reporting SDKs are integrated. See the in-app Privacy Policy for full details.

DISCLAIMER
Venus is an independent third-party tool and is not affiliated with or endorsed by miHoYo / HoYoverse, Hypergryph (Skland / Arknights), or any other publisher. It is not an official app. Use it in accordance with the relevant community's terms of service.
```
