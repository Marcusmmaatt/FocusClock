# FocusClock

**A local-only focus guard for Android.** Pick one app, set a per-session time limit, and FocusClock
reminds you — or blocks you — the moment you go over it. No account, no cloud, no network permission.

**一款完全离线的 Android 专注力守门员。** 选定一个应用、设定单次使用上限，到点提醒或强制退出。
不联网、不登录、不上传任何数据。

[English](#english) · [中文说明](#中文说明) · [Build](#build-from-source) · [Privacy](#privacy-and-permissions) · [License](#license)

---

## English

### Why FocusClock

Most screen-time tools are either whole-device summaries (too vague) or cloud services (too invasive).
FocusClock watches **one app at a time** and enforces a concrete rule: *this app, this many minutes,
then a break*. It also watches **total screen-on time**, so a session of app-hopping still counts.

It runs entirely on your phone: the app declares **no `INTERNET` permission** and even strips
`ACCESS_NETWORK_STATE` from the merged manifest. There is nothing to sign up for and nothing to leak.

### Features

- **Per-app limit** — choose one app, set 5–60 minutes (step 5, default 15) per session.
- **Global screen-on limit** — optional independent cap of 10–120 minutes (step 5, default 30).
- **Two interrupt modes**
  - **Soft reminder** — a floating card with a short vibration. Tap *Got it* to dismiss, reset the
    timer and snooze for 5 more minutes.
  - **Hard block** — a full-screen shield, then back to the home screen, plus a 300-second cooldown.
    Re-opening the app during the cooldown is blocked again within ~0.5 s.
- **180-second grace window** — switching away (notification, call, brief look-up) doesn't kill the
  session; come back within 3 minutes and the timer continues.
- **Survives reboot** — an active cooldown is persisted to disk and restored after boot.
- **Keep-alive guide** — step-by-step help for auto-start, battery whitelist and recent-task locking
  on aggressive Chinese ROMs.
- **No ads, no analytics, no network code.** Zero dependencies on HTTP clients.

### How the rules work

| Situation | Behaviour |
| --- | --- |
| Target app in foreground | Session timer runs |
| Target app left / screen off | 180 s grace; return in time and the timer resumes |
| Per-app limit reached (soft) | Floating reminder; *Got it* → timer resets, 5 min snooze |
| Per-app limit reached (hard) | Shield 1.5 s → home screen → 300 s cooldown, re-entry re-blocked |
| Global screen-on limit reached | Soft reminder for "phone" usage; acknowledging resets it |
| Screen off | Global screen-on timer resets to zero |
| Unlocked after screen on | Global timer is re-anchored automatically (self-healing, no restart needed) |

### Privacy and permissions

FocusClock requests only what the monitoring itself needs:

| Permission | Why |
| --- | --- |
| **Usage access** (`PACKAGE_USAGE_STATS`) | Read the current foreground package name — Android's only public API for this |
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | Draw the reminder card and the full-screen shield |
| **Notifications** (Android 13+) | Show the required foreground-service notification |
| **Ignore battery optimizations** (optional) | Keep monitoring alive on ROMs that kill background services |
| **Boot completed** | Restore an active cooldown after a restart |

There is **no internet permission**, no account, no telemetry. All state (settings, cooldown
deadline) stays in the app's private storage. See [`PRIVACY.md`](PRIVACY.md) for details.

### Requirements

- Android 9.0 (API 28) or newer.
- Not supported: HarmonyOS NEXT (pure HarmonyOS) — it has no AOSP compatibility layer.

### Build from source

You need **JDK 17** and the **Android SDK with API 35** (Android Studio installs both).

```bash
git clone https://github.com/<your-name>/FocusClock.git
cd FocusClock

# debug build (package id com.sessionclock.debug, installs alongside a release build)
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk

# release build (unsigned unless you configure a keystore)
./gradlew assembleRelease
```

On Windows use `gradlew.bat`. Open the folder directly in Android Studio if you prefer the IDE.

Toolchain: AGP 8.7.3 · Gradle 8.11.1 · Kotlin 2.1.20 · Jetpack Compose (BOM 2024.12.01) ·
compileSdk/targetSdk 35 · minSdk 28.

### After installing

1. Open FocusClock and grant **usage access** and **display over other apps** (the banner walks you
   through both).
2. Allow notifications, and add the app to your ROM's battery whitelist / auto-start list — open
   *Settings → 防杀后台必要设置* for guided steps.
3. Pick the app to watch and set the limits. That's it; the foreground service keeps running.

### Project structure

```
app/src/main/java/com/sessionclock/
├── engine/      State machines: per-app session, global screen-on timer, event loop
├── monitor/     Foreground-app polling, screen on/off receiver
├── overlay/     WindowManager overlays (Compose host + root view)
├── service/     Foreground service, notifications, interrupt handling
├── ui/          Compose screens (dashboard, app picker, settings, guides, overlays)
├── data/        DataStore settings, cooldown persistence
└── core/        DI, permissions, haptics, time source, vendor settings shortcuts
```

Unit tests live in `app/src/test/` and cover the session state machine's boundary behaviour.

### Contributing

Issues and pull requests are welcome. Please open an issue first for anything larger than a bug fix.
By contributing you agree that your work is licensed under the MIT License.

### License

[MIT](LICENSE) © FocusClock contributors

> FocusClock is a self-control tool, not a parental-control or MDM product. It runs with the user's
> consent and can be uninstalled or disabled at any time — as any honest focus tool should be.

---

## 中文说明

### 这是什么

**FocusClock（中文名：应用监控器）** 是一款纯本地的 Android 防沉迷 / 专注力工具。
它同一时间只盯住**一个应用**：你可以设定"这个应用每次最多用多少分钟"，到点就弹提醒，
或者直接把你请出应用并冷静 5 分钟。同时它还会统计**全局亮屏时间**，
防止"切来切去凑时长"。

整个应用**不联网**：清单里没有 `INTERNET` 权限，连传递依赖带进来的 `ACCESS_NETWORK_STATE`
都被显式移除。没有账号、没有云端、没有埋点。

### 功能

- **单应用上限**：选一个应用，设定 5–60 分钟（步进 5，默认 15）。
- **全局亮屏上限**：可独立开关，10–120 分钟（步进 5，默认 30）。
- **两种打断模式**
  - **仅提醒**：悬浮卡片 + 短震动，点【我知道了】后计时归零并顺延 5 分钟。
  - **强制退出（推荐）**：全屏遮罩 → 强退桌面 → 冷静 5 分钟；冷静期内重开会在约 0.5 秒内再次拦截。
- **180 秒容差**：切出去回个消息、接个短电话，3 分钟内回来计时继续，不会前功尽弃。
- **重启不丢冷静期**：冷静期截止时间写入本地文件，开机后自动恢复拦截。
- **保活指引**：自启动、电池白名单、最近任务加锁，一步一步教你在国产 ROM 上活下来。
- **无广告、无统计、无网络代码。**

### 规则一览

| 场景 | 行为 |
| --- | --- |
| 目标应用在前台 | 开始 / 继续累计单次使用时长 |
| 切出应用或熄屏 | 进入 180 秒容差；按时回来继续计时，超时则本次会话清零 |
| 单应用到点（仅提醒） | 弹悬浮提醒；确认后归零并顺延 5 分钟 |
| 单应用到点（强制退出） | 遮罩 1.5 秒 → 回桌面 → 冷静 300 秒，期间重开再次拦截 |
| 全局亮屏到点 | 弹出"手机"使用提醒；确认后归零并顺延 |
| 熄屏 | 全局亮屏计时清零 |
| 亮屏解锁 | 自动重建全局计时（自愈，无需重启应用） |

### 权限说明（为什么需要）

| 权限 | 用途 |
| --- | --- |
| **使用情况访问** | 读取当前前台应用包名，Android 仅此一种公开方式 |
| **悬浮窗** | 绘制提醒卡片与全屏拦截遮罩 |
| **通知**（Android 13+） | 前台服务的常驻通知 |
| **忽略电池优化**（可选） | 避免后台服务被系统清理 |
| **开机启动** | 重启后恢复未结束的冷静期 |

所有数据（设置、冷静期截止时间）只存在应用私有目录，卸载即删除。

### 环境要求

- Android 9.0（API 28）及以上。
- 不支持 HarmonyOS NEXT（纯血鸿蒙）。

### 从源码构建

需要 **JDK 17** 与 **Android SDK API 35**（安装 Android Studio 即可一并装好）。

```bash
git clone https://github.com/<你的用户名>/FocusClock.git
cd FocusClock

./gradlew assembleDebug     # 调试包，包名 com.sessionclock.debug，可与正式包共存
./gradlew assembleRelease   # 正式包（未配置签名时为未签名 APK）
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。

工具链：AGP 8.7.3 · Gradle 8.11.1 · Kotlin 2.1.20 · Compose（BOM 2024.12.01）·
compileSdk/targetSdk 35 · minSdk 28。

### 安装后三步

1. 打开 FocusClock，按顶部提示授予**使用情况访问**和**悬浮窗**权限。
2. 允许通知，并把应用加入系统的电池白名单 / 自启动名单（设置页有图文指引）。
3. 选择要监控的应用、调整上限，保持总开关开启即可。

### 目录结构

```
app/src/main/java/com/sessionclock/
├── engine/      状态机：单应用会话、全局亮屏计时、事件循环
├── monitor/     前台应用轮询、亮屏/熄屏广播
├── overlay/     WindowManager 浮层（Compose 宿主 + 根视图）
├── service/     前台服务、通知、打断执行
├── ui/          Compose 页面（控制台、应用选择、设置、指引、浮层）
├── data/        DataStore 配置、冷静期持久化
└── core/        依赖注入、权限、震动、时间源、厂商设置跳转
```

### 许可证

[MIT](LICENSE) © FocusClock contributors

> FocusClock 是自我管理工具，不是家长控制 / MDM 产品。它需要用户主动授权，
> 也随时可以被卸载或关闭——诚实的专注工具本该如此。
