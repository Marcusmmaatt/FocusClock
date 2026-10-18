# Privacy Policy / 隐私说明

FocusClock does not collect, transmit, or sell any data.

**FocusClock 不收集、不上传、不出售任何数据。**

## English

### What stays on your device

- Settings (chosen app, time limits, interrupt mode, master switch) are stored in Jetpack DataStore
  inside the app's private storage.
- The cooldown deadline (a single timestamp) is stored in a private file in the app's `filesDir`.
- Nothing else is written, and nothing leaves the device.

### What is never done

- No `INTERNET` permission is declared. `ACCESS_NETWORK_STATE` (pulled in transitively by
  WorkManager) is explicitly removed in the merged manifest.
- No analytics SDK, crash reporter, ad SDK, or account system.
- No screen contents, keystrokes, or app usage history are recorded or uploaded. Only the
  *current foreground package name* is read, in memory, to compare against your selected app.

### Permissions

- **Usage access** — required to read the current foreground package name.
- **Display over other apps** — required to show the reminder card and the block screen.
- **Notifications** — required for the foreground-service notification.
- **Ignore battery optimizations**, **Boot completed** — optional/keep-alive only.

### Removing your data

Uninstalling the app deletes all of its data. There is no server-side copy, because there is no server.

---

## 中文

### 留在你手机上的数据

- 设置（选中的应用、时长上限、打断模式、总开关）保存在应用私有目录的 Jetpack DataStore 中。
- 冷静期截止时间（一个时间戳）保存在应用私有目录的单个文件里。
- 除此之外不写任何文件，也不向任何地方发送数据。

### 永远不会做的事

- 清单中不声明 `INTERNET` 权限；WorkManager 传递依赖带入的 `ACCESS_NETWORK_STATE`
  也在合并清单中被显式移除。
- 不集成统计、崩溃上报、广告、账号等任何 SDK。
- 不记录、不上传屏幕内容、按键、应用使用历史。只会在内存中读取**当前前台包名**，
  用来与你选择的应用比对。

### 权限用途

- **使用情况访问**：读取当前前台应用包名。
- **悬浮窗**：显示提醒卡片与拦截遮罩。
- **通知**：前台服务常驻通知。
- **忽略电池优化**、**开机启动**：仅用于保活。

### 删除数据

卸载应用即删除全部数据。没有服务端副本，因为没有服务端。
