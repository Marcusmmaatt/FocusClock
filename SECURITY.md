# 安全策略

FocusClock 需要「使用情况访问」和「悬浮窗」两项敏感权限，因此安全问题的报告渠道必须明确。

## 支持范围

只维护**最新发布版本**（当前 `1.1.x`）。旧版本的问题请先升级并在最新版复现。

## 怎么报告

**请不要开公开 issue。**

首选：仓库 **Security** 标签页 → **Report a vulnerability**（GitHub 私密漏洞报告）。
如果看不到这个按钮，开一个 issue 只写「发现安全问题，需要私下联系渠道」，**不要写任何细节**，我会回复联系方式。

## 哪些算安全问题

- **绕过强拦截**：冷静期内重新打开目标应用却没有被拦截
- **绕过冷静期**：改系统时间、重启、切换用户等方式让冷静期提前结束
- **悬浮窗失效**：遮罩可被点击穿透、可被其他应用覆盖后失效、可被无障碍服务点掉
- **权限滥用**：读取了声明之外的静默数据，或写入应用私有目录以外的位置
- **静默失效**：特定机型或 ROM 上监控/遮罩崩溃后不报错、用户以为在保护其实没有

## 哪些不算

- 需要 root / ADB / Shizuku 才能绕过 —— 用户对自己的设备有完全控制权，这不是安全边界
- 卸载应用、关闭监控总开关、撤销权限 —— 这是产品设计的一部分
- 系统杀后台导致监控停止 —— 属于保活问题，请按 Bug 模板提交（附机型与 ROM）
- 「应用能读取前台应用包名」本身 —— 这是功能所必需，已在 README 与 [PRIVACY.md](PRIVACY.md) 中公开说明

## 响应

个人项目，**尽力而为**：通常 7 天内首次回复，不承诺修复时限。
修复发布后会在 Release Notes 中致谢（除非你要求匿名）。

## English summary

Please **do not open a public issue** for security problems. Use the **Security → Report a vulnerability**
form in this repository. In scope: bypassing the block screen or the cooldown, overlay click-through,
permission or data misuse, and silent monitoring failures. Out of scope: anything that requires root,
ADB or Shizuku, uninstalling the app, and the OS killing the background service. This is a personal
project: reports are handled on a best-effort basis, usually within 7 days.
