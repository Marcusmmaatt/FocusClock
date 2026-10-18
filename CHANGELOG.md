# Changelog

All notable changes to FocusClock are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).

## [1.1.3] - 2026-10-18

First public release. Package id stays `com.sessionclock`; the display name changed from
「应用监控器」to **FocusClock**.

### Fixed

- **Overlay crash that made reminders and blocks invisible.** The `ViewTreeLifecycleOwner`
  (plus `ViewModelStoreOwner` / `SavedStateRegistryOwner`) tag was set on the inner `ComposeView`
  instead of the window root view that `WindowManager.addView()` receives. Since androidx.lifecycle
  2.8 the Compose `WindowRecomposer` only reads the tag from the window root, so the first
  composition threw `IllegalStateException: ViewTreeLifecycleOwner not found` → the process died and
  the overlay never appeared, in both soft-reminder and hard-block modes.
  Tags are now set on the root view (and kept on the inner `ComposeView` as a harmless second copy).
- **Global screen-on timer stuck at "phone locked / screen off".** The global timer was only
  re-anchored on service start and on `ACTION_USER_PRESENT`. When the system skipped that broadcast
  (smart lock / no lock screen / some OEM ROMs), turning the screen back on left the global timer
  idle until the app was restarted. It is now re-anchored on `ACTION_SCREEN_ON` when the device is
  unlocked, plus a 500 ms tick-level self-heal fallback.

### Added

- Diagnostic `Log.i` lines for screen on/off/unlock, global limit reached, and soft-reminder
  attach/confirm/auto-dismiss (`adb logcat -s MonitorEngine MonitorService`).

## [1.1.2]

- Fixed the overlay owner-tag bug (see 1.1.3 for the full explanation); comments corrected.

## [1.1.1]

- Initial source drop.
