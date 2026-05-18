# WakeGuard: re-sleep accidental touchpad wakes from the launcher

## Premise

Background and root-cause analysis live in [android_touchpad_wake_findings.md](android_touchpad_wake_findings.md). Short version: pointer motion from the internal HID device wakes Android from `Dozing` with `WAKE_REASON_WAKE_MOTION`, and there is no non-root way to block that wake on this build. The mitigation has to be reactive: detect the wake, decide whether it was intentional, and call `DevicePolicyManager.lockNow()` if it was not.

This document specifies the solution as a delta on the current Compy Launcher codebase.

## Constraints from Compy and the current launcher

- The launcher is the device's default HOME app. Compy-IDE (`toys.compy.ide`) is the visible app; the launcher only appears when HOME is reached or maintenance mode is opened.
- All device-specific power and input handling lives in the launcher. Compy-IDE stays mostly device-agnostic and should not be concerned about handling particular hardware quirks.
- The current launcher has a deliberately small surface: a single `MainActivity` (raw `android.app.Activity`, no AppCompat) that draws a black `FrameLayout` and schedules a throttled launch of Compy-IDE, plus `KioskState` / `KioskConfig`, `KioskControlActivity`, and `KioskTileService`. New code should match that style and reuse `KioskState` for persistence and `KioskConfig` for tunables.
- Target SDK 33, `minSdk` 24, Android 13 device, not rooted, no `su`.
- Maintenance mode already exists as the universal "let an admin actually use the device" escape hatch (10-minute window, triggered by the QS tile or 5 home presses in 5 seconds). WakeGuard must respect it.

## Design

### One activity, two modes

`MainActivity` is the wake-arbitration surface. It already draws fullscreen black, already runs through `onResume` whenever HOME is reached, and already owns the relaunch decision. Wake arbitration is one more branch in that decision:

```
MainActivity.onResume
├─ KioskState.recordHomeResumeAndCheckSecret -> maintenance       (existing path)
├─ KioskState.isMaintenanceActive             -> maintenance       (existing path)
├─ KioskState.isInWakeArbitrationWindow       -> arbitrate (new)
└─ otherwise                                  -> scheduleLaunch    (existing path)
```

In arbitration mode, `scheduleLaunch` is skipped and the activity classifies the first user input within a short window:

| First input within window                       | Action                                       |
| ----------------------------------------------- | -------------------------------------------- |
| Pointer movement only (no buttons, no key down) | `lockNow()` after a small delay              |
| Mouse button / click                            | Exit arbitration, run normal launch path     |
| Key down                                        | Exit arbitration, run normal launch path     |
| Window timeout with no intentional input        | `lockNow()` (conservative; configurable)     |

Maintenance mode wins unconditionally: arbitration is skipped when maintenance is active so an admin or the 5-home secret can wake the device normally.

### Why this is enough

The launcher cannot suppress the wake itself, and it cannot reliably interpose ahead of Compy-IDE before the wake finishes. What it can do is take the foreground via the HOME-app exemption to background-activity-launch restrictions and then decide quickly whether to put the device back to sleep. The existing HOME intent path does the foreground transition.

### Bringing the launcher to the front on wake

A small foreground service, `WakeMonitorService`, listens for `Intent.ACTION_SCREEN_OFF` and `Intent.ACTION_SCREEN_ON` (these broadcasts require dynamic registration; manifest-declared receivers do not receive them) and on screen-on it does two things:

1. Stamps a "screen-on, arbitrate" timestamp in `KioskState`.
2. Fires a HOME intent so Android routes to the default HOME activity, i.e. `MainActivity`:

```kotlin
val home = Intent(Intent.ACTION_MAIN).apply {
    addCategory(Intent.CATEGORY_HOME)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
startActivity(home)
```

The HOME route works because default-launcher activity launches are the exemption that survives Android 12+ background-activity-launch restrictions.

If the HOME launch is still blocked on a given firmware build, `MainActivity` will not enter arbitration this cycle, the device will simply stay awake on top of Compy-IDE, and the next user interaction (or Android's own screen timeout) will resolve the state. That is a graceful degradation, not a hang.

### Input classification

`MainActivity` overrides `dispatchGenericMotionEvent`, `dispatchTouchEvent`, and `dispatchKeyEvent` only while arbitration is active. Outside arbitration the overrides fall through to `super`, so normal Activity behavior is unchanged.

Movement-only condition:

```text
event source is SOURCE_MOUSE / SOURCE_TOUCHPAD / SOURCE_CLASS_POINTER
AND action is ACTION_HOVER_MOVE or ACTION_MOVE
AND event.buttonState == 0
```

Any button-pressed bit, any `ACTION_DOWN` / `ACTION_BUTTON_PRESS` / `ACTION_UP`, or any `KeyEvent.ACTION_DOWN` ends arbitration and resumes the normal launch path.

### Re-sleep without loops

When the movement-only condition is met, `MainActivity` posts a short delay (≈150 ms) and then calls `DevicePolicyManager.lockNow()`. The delay avoids running `lockNow()` from inside an input-dispatch frame, which has caused focus/cleanup edge cases in similar setups.

Two cooldowns prevent ping-pong:

- `KioskState.armWakeArbitration(now)` is only honored once per `ACTION_SCREEN_ON`; subsequent SCREEN_ON broadcasts inside a debounce window are ignored.
- After scheduling `lockNow()`, `MainActivity` sets a re-sleep flag that suppresses further `lockNow()` calls for the rest of the activity's life and for a short post-finish cooldown.

### Maintenance interaction

Maintenance mode is the universal escape. The order in `onResume` is:

1. Run the existing five-home-press secret detection. If triggered, open maintenance and return.
2. If maintenance is active, open maintenance and return.
3. If `KioskState.isInWakeArbitrationWindow()`, enter arbitration and return.
4. Otherwise, `scheduleLaunch()` (existing behavior).

Because (1) and (2) precede (3), an admin can always wake the device by triggering the secret or by leaving maintenance armed, regardless of WakeGuard state.

## Code map

These are the concrete changes to make. File names match the existing package layout under `app/src/main/java/toys/compy/launcher/`.

### `KioskConfig.kt` — add tunables

```kotlin
const val WAKE_ARBITRATION_TIMEOUT_MS = 2_000L
const val WAKE_RESLEEP_DELAY_MS = 150L
const val WAKE_GUARD_DEBOUNCE_MS = 1_000L
const val WAKE_CONSERVATIVE_TIMEOUT_RESLEEP = true
```

### `KioskState.kt` — add screen-state helpers

Add three SharedPreferences keys and helpers:

- `markScreenOff()` records `System.currentTimeMillis()`.
- `armWakeArbitration()` stamps a "screen-on at" timestamp, but only if more than `WAKE_GUARD_DEBOUNCE_MS` has elapsed since the last stamp; returns whether the arming was honored.
- `isInWakeArbitrationWindow()` returns `true` while we are inside `WAKE_ARBITRATION_TIMEOUT_MS` of the stamp **and** maintenance is not active.
- `clearWakeArbitration()` is called when the launcher decides arbitration is over (intentional input, re-sleep scheduled, or maintenance entered).

The maintenance check stays where it is. Do not entangle the two stamps.

### `KioskDeviceAdminReceiver.kt` — new file

```kotlin
package toys.compy.launcher

import android.app.admin.DeviceAdminReceiver

class KioskDeviceAdminReceiver : DeviceAdminReceiver()
```

### `WakeMonitorService.kt` — new foreground service

Responsibilities only:

- `onCreate`: `startForeground(...)` with a low-importance notification channel ("Wake monitor"). Dynamically register a receiver for `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON`.
- On `ACTION_SCREEN_OFF`: `KioskState.markScreenOff(this)`.
- On `ACTION_SCREEN_ON`:
  1. Skip if maintenance is active.
  2. `KioskState.armWakeArbitration(this)`; bail if debounced.
  3. Fire the HOME intent shown above.
- `onDestroy`: unregister the receiver.

Start the service from `MainActivity.onCreate` (idempotent via `startForegroundService` / `startService`). This means the service comes up the first time the launcher is reached after boot, which on a default-HOME install is effectively immediately.

### `MainActivity.kt` — add arbitration branch

In `onResume`, after the existing secret/maintenance checks, ask `KioskState.isInWakeArbitrationWindow()`. If true:

- Cancel any pending `launchRunnable` (the existing `handler.removeCallbacks` already does this on `onPause`; do it explicitly here too).
- Post a delayed `onArbitrationTimeout()` at `WAKE_ARBITRATION_TIMEOUT_MS`.
- Enter a local `arbitrating = true` flag for the duration.

Override `dispatchGenericMotionEvent`, `dispatchTouchEvent`, and `dispatchKeyEvent`. While `arbitrating` is true:

- Movement-only pointer event → `scheduleReSleep()` and consume the event.
- Button / click / key-down → call `exitArbitration()` and fall through to `super.dispatch*` so the event reaches the rest of the system normally.

`scheduleReSleep()` posts a delayed call that:

1. Looks up `DevicePolicyManager` and the `KioskDeviceAdminReceiver` `ComponentName`.
2. If `isAdminActive`, calls `lockNow()`.
3. Otherwise logs a warning and falls through (the user sees the launcher on top until they interact; admin activation should be re-attempted from the maintenance UI).

`exitArbitration()` clears the arbitration window in `KioskState`, removes the timeout callback, and calls the existing `scheduleLaunch()` so Compy-IDE comes back as usual.

`onArbitrationTimeout()` calls `scheduleReSleep()` when `WAKE_CONSERVATIVE_TIMEOUT_RESLEEP == true`. The conservative default trades one-touch wake convenience for fewer accidental wakes; flip the constant for the opposite trade.

### `KioskControlActivity.kt` — admin-activation hook

Add a new button alongside the existing maintenance controls:

- Label: "Activate device admin" (string resource).
- Action: if `isAdminActive` is already true, toast that admin is active. Otherwise launch the standard `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` intent with `EXTRA_DEVICE_ADMIN` set to the receiver and a short explanation string.
- The button can be hidden when admin is already active.

This keeps admin activation discoverable for a maintainer on the device without forcing it on first boot.

### `AndroidManifest.xml` — additions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

(Android 14+ adds typed foreground-service permissions; for `targetSdk = 33` the untyped permission above is sufficient. Re-evaluate when `targetSdk` moves to 34+.)

Device admin receiver:

```xml
<receiver
    android:name=".KioskDeviceAdminReceiver"
    android:label="@string/app_name"
    android:description="@string/device_admin_description"
    android:permission="android.permission.BIND_DEVICE_ADMIN"
    android:exported="true">

    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin" />

    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
    </intent-filter>
</receiver>
```

Service:

```xml
<service
    android:name=".WakeMonitorService"
    android:exported="false" />
```

### `res/xml/device_admin.xml` — new file

```xml
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

### `res/values/strings.xml` — new strings

Add `device_admin_description`, the admin activation button label, and the explanation string used by `ACTION_ADD_DEVICE_ADMIN`. Keep the wording aligned with the kiosk framing already in this file.

## Test plan

These tests assume admin has been activated (either via the maintenance UI button or `adb shell dpm set-active-admin toys.compy.launcher/.KioskDeviceAdminReceiver`).

### 1. Service liveness

```powershell
adb shell 'dumpsys activity services toys.compy.launcher' | findstr WakeMonitorService
```

Expect a running service entry after the first launcher resume post-boot.

### 2. Screen-event reception

```powershell
adb logcat -c
adb logcat -v threadtime WakeMonitor:I MainActivity:I PowerManagerService:I '*:S'
adb shell 'input keyevent 26'
```

Expect `WakeMonitor: ACTION_SCREEN_OFF` followed by `PowerManagerService: Dozing...`. Move the touchpad. Expect `PowerManagerService: Waking up ... WAKE_REASON_WAKE_MOTION ...`, then `WakeMonitor: ACTION_SCREEN_ON`, then `MainActivity: onResume ... arbitrating=true`.

### 3. Movement-only re-sleep

After step 2, the next log lines should be a movement event followed by `MainActivity: scheduleReSleep ... lockNow`. Visible behavior: screen wakes for a fraction of a second, then sleeps again.

### 4. Intentional wake

Repeat step 2 but, instead of moving the touchpad, press the power button to wake and then immediately press a key or click. Expect `MainActivity: exitArbitration ...` and Compy-IDE on top within the normal launch delay.

### 5. Maintenance bypass

Trigger maintenance mode (5 home presses or the QS tile), sleep with `input keyevent 26`, wake by moving the touchpad. Expect `MainActivity: maintenance active, skipping arbitration` and the maintenance UI on top with no `lockNow()`.

### 6. Rapid stress

Move the touchpad continuously while the device is trying to sleep. Expect no Activity-launch spam in logcat (debounce holds), no crash, and that the device eventually settles into the asleep state.

## Failure modes

- **HOME launch blocked from the service on this firmware.** Arbitration will not start; Compy-IDE remains on top after the wake. The conservative timeout cannot help here because `MainActivity` never gains focus. Acceptable degradation; document for the user as "if accidental wake leaves the screen on, press the power button or Fn-F2".
- **Admin not active.** `lockNow()` no-ops with a warning. The maintenance UI must surface this state so a maintainer can re-activate admin. Consider showing the QS tile label as "Maintenance · admin off" when admin is inactive.
- **Conservative timeout re-sleeps a legitimate power-button wake.** If this becomes a complaint, either raise `WAKE_ARBITRATION_TIMEOUT_MS` or flip `WAKE_CONSERVATIVE_TIMEOUT_RESLEEP` to `false`; both are constants in `KioskConfig`.
- **Foreground-service notification is undesirable on a kiosk.** Use a low-importance channel with minimal text. If the device is later provisioned as device owner, revisit whether a notification is still required.

## Acceptance criteria

1. Launcher remains the default HOME app, and Compy-IDE remains the visible app in normal use.
2. `WakeMonitorService` starts on first launcher resume after boot and survives normal operation.
3. `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` are logged for timeout, power-button, and keyboard-Zzz sleep/wake cycles.
4. A touchpad-motion-only wake causes the device to lock again within roughly half a second on this device.
5. A key press or pointer click after wake returns the user to Compy-IDE without re-sleep.
6. Maintenance mode (QS tile or 5-home secret) suppresses arbitration entirely.
7. No Compy-IDE code changes are required.
8. All new logs are filterable under `WakeMonitor:` and `MainActivity:` tags.

## If privileged access becomes available later

The reactive WakeGuard becomes redundant when the wake source itself can be muted. Likely paths, in priority order:

1. `echo disabled > /sys/devices/platform/soc@2900000/5200000.ehci1-controller/usb1/1-1/1-1.3/power/wakeup` (or the parent branch).
2. Kernel input inhibition on the pointer node, if a custom kernel exposes it.
3. Unbind/rebind the HID pointer interface across sleep/wake.
4. Vendor/framework patch making pointer motion non-wake-capable.

Caveat from the findings: the wake-control branch is shared across all internal USB HID inputs, so any of (1)–(3) may also disable wake from the built-in keyboard. The physical power button remains independent and acceptable as the sole wake path in that case.
