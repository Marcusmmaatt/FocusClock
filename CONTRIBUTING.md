# 贡献指南

感谢你想帮 FocusClock 变得更好。这份文档说明环境要求、项目红线，以及一个 PR 需要达到什么标准。

## 这个项目是什么

一个**完全离线**的 Android 防沉迷工具：只监控一个应用，同时统计全局亮屏时长，到点提醒或强制退出。
没有账号、没有云端、没有 `INTERNET` 权限 —— 这是产品的核心卖点，也是代码的硬约束。

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | **17 或 21**（Gradle 8.11 不支持 25，会报 `Unsupported class file major version 69`） |
| Android SDK | Platform 35 + Build-Tools 35.0.0 |
| Gradle | 8.11.1，由 `gradlew` 自动下载，不需要单独安装 |
| Android Studio | 近两年任意版本（可选，纯命令行也能构建） |

## 构建与测试

```bash
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 状态机单元测试（CI 跑的也是这个）
```

Windows 下用 `gradlew.bat`。首次构建要下载 Gradle 与依赖，约 3–10 分钟。

## 三条红线

1. **不要引入任何网络能力** —— 不添加 `INTERNET` / `ACCESS_NETWORK_STATE` 权限，不引入
   Retrofit / OkHttp / Coil 这类联网库，不做「云同步」「远程配置」。合并清单里已经显式移除了
   `ACCESS_NETWORK_STATE`，请不要把它加回来。
2. **不要改包名** —— 保持 `com.sessionclock`（debug 为 `com.sessionclock.debug`）。
   包名一变，已安装用户就无法覆盖升级。
3. **不要采集数据** —— 不写日志文件、不做埋点、不上报崩溃。应用私有目录只存设置项与冷静期时间戳。

## 代码风格

- Kotlin，4 空格缩进，保留现有文件的分区注释风格
- 代码注释与用户可见文案用中文；类名、变量名用英文
- UI 用 Jetpack Compose + Material 3，不要引入 XML 布局
- 依赖统一通过 `AppContainer` 注入，不要在 Composable 里直接 `new`
- 新增行为请补单元测试（`app/src/test/`，纯 JVM，不依赖 Robolectric）

## 提交 PR 前自查

- [ ] `./gradlew assembleDebug` 通过
- [ ] `./gradlew testDebugUnitTest` 全绿
- [ ] 改动涉及界面时附截图
- [ ] 没有触碰上面三条红线
- [ ] commit message 说清楚「改了什么、为什么」

## 真机验收清单

CI 只能验证编译和状态机，下面这些必须真机过一遍：

1. 设 5 分钟上限 → 刷到点 → 软提醒 / 强拦截是否出现
2. 强拦截后按返回键、切后台、重新打开目标应用 → 冷静期内是否再次拦截
3. 熄屏再亮屏（尤其是不发解锁广播的机型）→ 全局计时是否恢复
4. 重启手机 → 冷静期是否仍然生效
5. 国产 ROM：一键清理后台后，服务能否被拉起

## 报告问题

普通 bug 请用仓库的 issue 模板；**安全相关不要开公开 issue**，见 [SECURITY.md](SECURITY.md)。

## 语言

中文、英文都欢迎。
