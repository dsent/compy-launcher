# WakeGuard Implementation Handoff for Launcher Developers

## Purpose

Implement a non-root workaround in the launcher app to handle accidental wakeups caused by touchpad/mouse movement on an Android 13 netbook-style device.

The launcher is the default Home app. It usually has no visible interface: when it gains control, it launches or returns to Compy-IDE. Compy-IDE is the app normally visible to the user and should not own device-specific power/input workarounds.

This document describes the practical launcher-side implementation.

---

## Scope

### In scope

- Detect normal screen-off / screen-on transitions.
- Attempt to bring a launcher-owned guard Activity to the foreground immediately after wake.
- Classify the first user input after wake.
- If the wake appears to be caused only by pointer movement, immediately put the device back to sleep.
- If the wake appears intentional, return to Compy-IDE.
- Keep Compy-IDE free of device-specific wake handling.

### Out of scope

- Root-based fixes.
- Firmware changes.
- Kernel/sysfs wake-source changes.
- Modifying Android framework/vendor partitions.
- Reliably preventing the wake before it happens.
- Reading `/dev/input` events from a normal app.
- Reading system logcat from a normal app.

---

## Environment assumptions

- Android 13.
- Launcher package: `toys.compy.launcher`.
- Compy-IDE package: `toys.compy.ide`.
- Launcher is configured as the default Home app.
- Device is not rooted.
- `su` is unavailable.
- No usable `cmd input disable/enable` command exists on this build.
- Kernel input inhibition nodes are unavailable.
- `/sys/.../power/wakeup` exists but is not readable/writable by `adb shell` or normal apps.
- Touchpad wake is caused by pointer/motion wake policy, not by alarms, notifications, or Compy-IDE.

The known wake log pattern is:

```text
PowerManagerService: Going to sleep due to power_button ...
PowerManagerService: Dozing...
PowerManagerService: Waking up from Dozing (uid=1000, reason=WAKE_REASON_WAKE_MOTION, details=android.policy:MOTION)...
```

The workaround cannot stop this initial wake. It can only re-sleep the device quickly after the wake if the first input looks accidental.

---

## Target behavior

### Normal use

- Compy-IDE remains the primary visible app.
- Launcher stays mostly invisible.
- Launcher continues to auto-start or return to Compy-IDE when Home is reached.

### On screen off

The launcher service records that the device entered a screen-off/non-interactive state.

Sources of screen-off may include:

- screen timeout;
- physical power button;
- keyboard sleep/Zzz button;
- launcher-initiated sleep;
- Android power policy.

The workaround does not need to distinguish these causes.

### On screen on

The launcher service attempts to start `WakeGuardActivity` immediately.

`WakeGuardActivity` is a minimal, fullscreen, black/no-UI Activity that briefly arbitrates the wake.

Within a short post-wake window:

- pointer movement only → call `DevicePolicyManager.lockNow()` and finish;
- mouse/touchpad click → treat as intentional wake, open/return to Compy-IDE;
- keyboard key press → treat as intentional wake, open/return to Compy-IDE;
- hidden admin gesture/control → open admin/system controls if implemented;
- no input for timeout → conservative default should be to sleep again.

---

## UX policy

Recommended default policy:

```text
Accidental wake by touchpad movement should self-correct.
Intentional wake requires an explicit key press or pointer click.
```

This is intentionally conservative. It may require the user to press a key/click after waking with the power button. That is acceptable if preventing accidental wakes is more important than one-touch wake convenience.

Policy constants to make configurable:

```kotlin
private const val WAKE_ARBITRATION_TIMEOUT_MS = 2_000L
private const val RESLEEP_DELAY_MS = 150L
private const val RESLEEP_COOLDOWN_MS = 1_500L
```

---

## Android components to implement

### 1. `KioskDeviceAdminReceiver`

Needed to call `DevicePolicyManager.lockNow()`.

```kotlin
package toys.compy.launcher

import android.app.admin.DeviceAdminReceiver

class KioskDeviceAdminReceiver : DeviceAdminReceiver()
```

### 2. `WakeMonitorService`

A small persistent service that:

- starts in foreground;
- dynamically registers for `Intent.ACTION_SCREEN_OFF` and `Intent.ACTION_SCREEN_ON`;
- records screen state;
- launches `WakeGuardActivity` on screen-on;
- logs diagnostic events.

Use a foreground service for reliability. The service notification can be low-importance and hidden/minimized as much as the platform allows.

### 3. `WakeGuardActivity`

A minimal Activity that:

- uses black fullscreen UI;
- starts with no animation;
- receives post-wake input if it successfully becomes foreground;
- classifies the first events;
- calls `lockNow()` on movement-only wake;
- returns to Compy-IDE on intentional input.

### 4. Existing launcher/Home Activity

Existing behavior should continue:

```text
When launcher receives Home / gains normal control:
  start or return to Compy-IDE.
```

Do not route normal Home behavior through `WakeGuardActivity` unless the app is in a post-wake arbitration window.

---

## Manifest additions

### Device admin receiver

```xml
<receiver
    android:name=".KioskDeviceAdminReceiver"
    android:description="@string/device_admin_description"
    android:exported="true"
    android:label="@string/app_name"
    android:permission="android.permission.BIND_DEVICE_ADMIN">

    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin" />

    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
    </intent-filter>
</receiver>
```

### Foreground service

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

For Android versions that require a foreground service type, choose the least inappropriate type available for the target SDK and build. If the target SDK/build requires a type, define it explicitly and test on-device.

```xml
<service
    android:name=".WakeMonitorService"
    android:exported="false" />
```

### Wake guard Activity

```xml
<activity
    android:name=".WakeGuardActivity"
    android:excludeFromRecents="true"
    android:exported="false"
    android:finishOnTaskLaunch="true"
    android:showWhenLocked="true"
    android:theme="@style/Theme.CompyLauncher.WakeGuard" />
```

Depending on device behavior, also set window flags in code. Do not rely only on manifest attributes.

---

## `res/xml/device_admin.xml`

```xml
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

---

## WakeGuard theme

Use a black, fullscreen, no-actionbar theme.

Example:

```xml
<style name="Theme.CompyLauncher.WakeGuard" parent="android:style/Theme.Material.NoActionBar">
    <item name="android:windowNoTitle">true</item>
    <item name="android:windowActionBar">false</item>
    <item name="android:windowFullscreen">true</item>
    <item name="android:windowIsTranslucent">false</item>
    <item name="android:windowDisablePreview">true</item>
    <item name="android:colorAccent">#000000</item>
    <item name="android:navigationBarColor">#000000</item>
    <item name="android:statusBarColor">#000000</item>
</style>
```

Avoid overlay permissions. The guard should be a real Activity, not a system overlay.

---

## Service implementation sketch

```kotlin
package toys.compy.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

class WakeMonitorService : Service() {
    private var lastScreenOffElapsedMs: Long = 0L
    private var lastGuardLaunchElapsedMs: Long = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastScreenOffElapsedMs = SystemClock.elapsedRealtime()
                    Log.i(TAG, "ACTION_SCREEN_OFF")
                }

                Intent.ACTION_SCREEN_ON -> {
                    val now = SystemClock.elapsedRealtime()
                    val sinceOff = now - lastScreenOffElapsedMs
                    Log.i(TAG, "ACTION_SCREEN_ON sinceOffMs=$sinceOff")
                    launchWakeGuardBestEffort()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        registerReceiver(screenReceiver, filter)
        Log.i(TAG, "WakeMonitorService started")
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        Log.i(TAG, "WakeMonitorService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun launchWakeGuardBestEffort() {
        val now = SystemClock.elapsedRealtime()

        if (now - lastGuardLaunchElapsedMs < GUARD_LAUNCH_DEBOUNCE_MS) {
            Log.i(TAG, "Skipping guard launch: debounce")
            return
        }

        lastGuardLaunchElapsedMs = now

        val guardIntent = Intent(this, WakeGuardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(WakeGuardActivity.EXTRA_REASON, "screen_on")
        }

        try {
            startActivity(guardIntent)
            Log.i(TAG, "WakeGuardActivity launch requested")
        } catch (exception: Exception) {
            Log.w(TAG, "Could not start WakeGuardActivity", exception)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Wake monitor active")
            .setContentText("Launcher wake guard is running")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WakeMonitor"
        private const val CHANNEL_ID = "wake_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val GUARD_LAUNCH_DEBOUNCE_MS = 1_000L
    }
}
```

Notes:

- `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` must be dynamically registered.
- Foreground service is used to keep the launcher process alive.
- Starting an Activity from the background is restricted on modern Android. Test on this specific device. Being the default Home app may help in practice, but it is not a universal guarantee.

---

## Starting the service

Start `WakeMonitorService` from the launcher’s main Activity and from app startup paths that are already guaranteed to execute.

```kotlin
private fun ensureWakeMonitorServiceRunning() {
    val serviceIntent = Intent(this, WakeMonitorService::class.java)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(serviceIntent)
    } else {
        startService(serviceIntent)
    }
}
```

Call this before launching Compy-IDE.

---

## WakeGuardActivity implementation sketch

```kotlin
package toys.compy.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class WakeGuardActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var createdElapsedMs: Long = 0L
    private var reSleepStarted = false
    private var allowStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdElapsedMs = SystemClock.elapsedRealtime()

        configureWindow()
        setContentView(View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        })

        Log.i(TAG, "onCreate reason=${intent.getStringExtra(EXTRA_REASON)}")

        mainHandler.postDelayed({
            onArbitrationTimeout()
        }, WAKE_ARBITRATION_TIMEOUT_MS)
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        logMotionEvent("generic", event)

        if (shouldReSleepFromPointerMotion(event)) {
            scheduleReSleep("pointer_movement_generic")
            return true
        }

        if (isIntentionalPointerEvent(event)) {
            allowWakeAndOpenCompy("intentional_pointer_generic")
            return true
        }

        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        logMotionEvent("touch", event)

        if (shouldReSleepFromPointerMotion(event)) {
            scheduleReSleep("pointer_movement_touch")
            return true
        }

        if (isIntentionalPointerEvent(event)) {
            allowWakeAndOpenCompy("intentional_pointer_touch")
            return true
        }

        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.i(
            TAG,
            "key action=${event.action} keyCode=${event.keyCode} scanCode=${event.scanCode} device=${event.device?.name}"
        )

        if (event.action == KeyEvent.ACTION_DOWN) {
            allowWakeAndOpenCompy("key_down")
            return true
        }

        return true
    }

    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun shouldReSleepFromPointerMotion(event: MotionEvent): Boolean {
        if (reSleepStarted || allowStarted) return false
        if (!isInsideArbitrationWindow()) return false

        val isPointer =
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)

        val isMovement =
            event.action == MotionEvent.ACTION_HOVER_MOVE ||
            event.action == MotionEvent.ACTION_MOVE

        val hasButton = event.buttonState != 0

        return isPointer && isMovement && !hasButton
    }

    private fun isIntentionalPointerEvent(event: MotionEvent): Boolean {
        if (reSleepStarted || allowStarted) return false

        val hasButton = event.buttonState != 0

        val isClickLikeAction =
            event.action == MotionEvent.ACTION_DOWN ||
            event.action == MotionEvent.ACTION_BUTTON_PRESS ||
            event.action == MotionEvent.ACTION_UP

        return hasButton || isClickLikeAction
    }

    private fun isInsideArbitrationWindow(): Boolean {
        val ageMs = SystemClock.elapsedRealtime() - createdElapsedMs
        return ageMs <= WAKE_ARBITRATION_TIMEOUT_MS
    }

    private fun onArbitrationTimeout() {
        if (reSleepStarted || allowStarted) return

        if (CONSERVATIVE_TIMEOUT_RESLEEP) {
            scheduleReSleep("timeout_no_intentional_input")
        } else {
            allowWakeAndOpenCompy("timeout")
        }
    }

    private fun scheduleReSleep(reason: String) {
        if (reSleepStarted || allowStarted) return
        reSleepStarted = true

        Log.i(TAG, "scheduleReSleep reason=$reason")

        mainHandler.postDelayed({
            val manager = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)

            if (manager.isAdminActive(admin)) {
                Log.i(TAG, "lockNow")
                manager.lockNow()
            } else {
                Log.w(TAG, "Device admin inactive; cannot lockNow")
            }

            finish()
        }, RESLEEP_DELAY_MS)
    }

    private fun allowWakeAndOpenCompy(reason: String) {
        if (reSleepStarted || allowStarted) return
        allowStarted = true

        Log.i(TAG, "allowWakeAndOpenCompy reason=$reason")

        val launchIntent = packageManager.getLaunchIntentForPackage(COMPY_IDE_PACKAGE)

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
        } else {
            Log.w(TAG, "Compy-IDE launch intent not found: $COMPY_IDE_PACKAGE")
        }

        finish()
    }

    private fun logMotionEvent(prefix: String, event: MotionEvent) {
        Log.i(
            TAG,
            "$prefix action=${event.action} source=${event.source} buttons=${event.buttonState} device=${event.device?.name} x=${event.x} y=${event.y}"
        )
    }

    companion object {
        const val EXTRA_REASON = "reason"

        private const val TAG = "WakeGuard"
        private const val COMPY_IDE_PACKAGE = "toys.compy.ide"
        private const val WAKE_ARBITRATION_TIMEOUT_MS = 2_000L
        private const val RESLEEP_DELAY_MS = 150L
        private const val CONSERVATIVE_TIMEOUT_RESLEEP = true
    }
}
```

Notes:

- `FLAG_KEEP_SCREEN_ON` is added only while guard is active to prevent the guard from immediately timing out during classification. Remove it if it causes unexpected behavior.
- `CONSERVATIVE_TIMEOUT_RESLEEP = true` means a wake with no explicit key/click goes back to sleep.
- If this is too aggressive, set it to false after testing.
- If AppCompat is not used, extend `Activity` instead of `AppCompatActivity`.

---

## Main launcher behavior

Existing Home behavior should remain simple:

```kotlin
private fun returnToCompyIde() {
    val launchIntent = packageManager.getLaunchIntentForPackage("toys.compy.ide")

    if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
    }
}
```

Call `ensureWakeMonitorServiceRunning()` before returning to Compy-IDE.

Do not always show `WakeGuardActivity` when Home is pressed. Only show it during post-wake arbitration or explicit sleep workflow.

---

## Optional explicit sleep workflow

If the launcher later controls sleep explicitly, use this stronger flow:

```text
start WakeGuardActivity
WakeGuardActivity calls lockNow()
WakeGuardActivity remains top when device wakes
```

This is more reliable than launching the guard after `ACTION_SCREEN_ON`, because the guard is already the top Activity before sleep.

Even if this is not viable now, design `WakeGuardActivity` so it can support both modes:

```text
mode = launched_after_screen_on
mode = launched_before_sleep
```

---

## Testing plan

### 1. Confirm device admin activation

Install the launcher, then run:

```powershell
adb shell 'dpm set-active-admin toys.compy.launcher/.KioskDeviceAdminReceiver'
```

If this fails, activate manually in Android settings.

Verify in logs that `manager.isAdminActive(admin)` returns true.

### 2. Confirm service starts and receives screen events

Run:

```powershell
adb logcat -c
adb logcat -v threadtime WakeMonitor:I WakeGuard:I PowerManagerService:I ActivityTaskManager:I '*:S'
```

Trigger sleep:

```powershell
adb shell 'input keyevent 26'
```

Expected logs:

```text
WakeMonitor: ACTION_SCREEN_OFF
PowerManagerService: Dozing...
```

Wake by touching the touchpad.

Expected logs:

```text
PowerManagerService: Waking up from Dozing ... WAKE_REASON_WAKE_MOTION ...
WakeMonitor: ACTION_SCREEN_ON ...
WakeMonitor: WakeGuardActivity launch requested
WakeGuard: onCreate
WakeGuard: onResume
```

### 3. Confirm guard receives input

Touch/move the touchpad after wake.

Expected logs:

```text
WakeGuard: generic action=... source=... buttons=0 device=...
WakeGuard: scheduleReSleep reason=pointer_movement_...
WakeGuard: lockNow
```

If the guard does not receive input, check whether it actually became top Activity:

```powershell
adb shell 'dumpsys activity top | grep -i "WakeGuard\|Compy\|launcher"'
```

### 4. Confirm re-sleep behavior

Expected visible behavior:

```text
Touchpad movement wakes screen briefly.
Guard appears black or nearly invisible.
Device sleeps again within a fraction of a second.
```

### 5. Confirm intentional wake behavior

Test each:

```text
Power button wake + keyboard key press -> Compy-IDE opens/stays open.
Power button wake + pointer click -> Compy-IDE opens/stays open.
Touchpad movement only -> re-sleep.
Keyboard Zzz sleep + touchpad movement wake -> re-sleep.
Timeout sleep + touchpad movement wake -> re-sleep.
```

### 6. Stress test rapid loops

Move the touchpad repeatedly while the device is trying to sleep.

Expected:

- no crash;
- no rapid Activity spam;
- no infinite visible flicker beyond unavoidable wake/sleep attempts;
- debounce prevents repeated guard launches within ~1 second.

---

## Known failure modes and mitigations

### Failure: `WakeGuardActivity` does not start on screen-on

Cause:

- Android background Activity launch restrictions.

Mitigations:

1. Ensure launcher is default Home app.
2. Ensure `WakeMonitorService` is a foreground service.
3. Start guard with `FLAG_ACTIVITY_NEW_TASK`, `CLEAR_TOP`, `SINGLE_TOP`, `NO_ANIMATION`.
4. Test whether making the launcher device owner is acceptable later.
5. If post-wake launch is blocked, only explicit pre-sleep guard mode will be reliable.

### Failure: guard starts but does not receive touchpad movement

Cause:

- Compy-IDE remains focused.
- Guard did not become top fast enough.
- The wake-causing event is consumed by system policy and not delivered to apps.

Mitigations:

1. Use conservative timeout: if no intentional key/click arrives within 2 seconds, re-sleep.
2. Try pre-sleep guard mode for launcher-initiated sleep.
3. Add logging for Activity lifecycle and top Activity.

### Failure: user intentionally wakes with power button, but device sleeps again

Cause:

- Conservative timeout policy.

Mitigations:

1. Document required confirmation action: press any key or click after wake.
2. Increase timeout to 3 seconds.
3. Add hidden/admin exception.
4. Add a configurable permissive mode where no input means open Compy-IDE.

### Failure: device admin cannot be activated via ADB

Mitigations:

1. Activate manually in Settings.
2. Consider device owner provisioning if the device can be reset/provisioned.

### Failure: notification from foreground service is undesirable

Mitigations:

1. Use a low-importance channel.
2. Make notification text minimal.
3. If device owner later becomes available, revisit service/background limits.

---

## Optional Compy-IDE integration hooks for later

Do not depend on these for the first implementation, but keep the architecture compatible.

If Compy-IDE can later cooperate, it may send explicit intents to the launcher:

```text
toys.compy.launcher.action.REQUEST_SLEEP_GUARD
  Compy-IDE is about to let the device sleep or user selected sleep.
  Launcher starts WakeGuardActivity and locks device.

toys.compy.launcher.action.USER_ACTIVE
  Compy-IDE observed intentional user interaction.
  Launcher should not re-sleep immediately.

toys.compy.launcher.action.ADMIN_REQUEST
  Open launcher/admin UI.
```

The launcher should protect exported receivers with package checks or signature permissions if any exported intent surface is added.

---

## Security and safety notes

- Keep `WakeGuardActivity` non-exported.
- Keep `WakeMonitorService` non-exported.
- Avoid broad exported broadcast receivers.
- If adding exported control intents, require explicit package, signature permission, or other validation.
- Do not request overlay permission for this workaround.
- Do not attempt to read `/dev/input` or system logcat from the app; that is not available to a normal app and should not be part of the design.

---

## Acceptance criteria

Implementation is acceptable if all are true:

1. Launcher remains default Home app.
2. Compy-IDE remains the normal visible app.
3. WakeMonitorService starts reliably and survives normal operation.
4. Service logs `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` during timeout, power button, and Zzz-triggered sleep/wake cycles.
5. On touchpad-movement wake, WakeGuardActivity is started or the conservative fallback is applied.
6. Movement-only wake causes `lockNow()` and the device goes back to sleep.
7. Key press or pointer click after wake returns to Compy-IDE.
8. No endless Activity-launch loop occurs.
9. No device-specific logic is added to Compy-IDE.
10. All debug logs can be filtered by `WakeMonitor` and `WakeGuard` tags.

---

## Recommended first development milest