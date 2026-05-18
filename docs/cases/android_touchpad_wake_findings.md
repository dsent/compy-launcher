# Android Touchpad Wake Investigation: Findings and Workaround

## Executive conclusion

The device wakes because touchpad/mouse movement is treated by Android input/power policy as a wake-capable motion event while the system is in `Dozing` state.

The decisive log pattern is:

```text
Going to sleep due to power_button
Dozing...
Waking up from Dozing (uid=1000, reason=WAKE_REASON_WAKE_MOTION, details=android.policy:MOTION)...
Screen on took ... ms
```

This is not primarily caused by Google services, alarms, notifications, Android dreams/screensaver, or the custom launcher. Those appear in dumps as surrounding noise, not as the root cause.

The most viable non-root/non-firmware workaround is:

1. Keep the custom launcher/activity foreground.
2. Detect that the device has just woken.
3. If the first received input is mouse/touchpad movement only, with no button/key press, immediately call `DevicePolicyManager.lockNow()` to send the device back to sleep.
4. Treat mouse click, keyboard input, or power button wake as intentional and keep the device awake.

This will not prevent the brief wake. It can only re-sleep the device quickly after accidental touchpad-motion wake.

---

## 1. What we tested

### 1.1 Wake monitoring via ADB

We built a PowerShell/ADB monitoring workflow that captured wake incidents using:

- `dumpsys power`
- filtered `logcat`
- `dumpsys input`
- `dumpsys alarm`
- `dumpsys notification`
- `dumpsys deviceidle`
- `dumpsys window`
- `dumpsys activity top`
- `dumpsys batterystats --history`

The initial dumps were too noisy, so we reduced them into per-wake incident packs and summaries.

The filtering approach was useful, but the initial classifier was too easily misled by historical wakelock entries such as old `PhoneWindowManager.mPowerKeyWakeLock` records. The correct primary classifier should prioritize the nearest live `PowerManagerService: Waking up... reason=...` line from `logcat-recent.txt`.

### 1.2 Reproducing the wake manually

The wake is reproducible without overnight testing:

```powershell
adb logcat -c
adb shell 'input keyevent 26'
adb logcat -b system -v threadtime PowerManagerService:V PhoneWindowManager:V DisplayPowerController:V InputDispatcher:V '*:S'
```

Then touching/moving the touchpad wakes the device.

Observed output:

```text
PowerManagerService: Going to sleep due to power_button (uid 1000)...
PowerManagerService: Dozing...
PowerManagerService: Waking up from Dozing (uid=1000, reason=WAKE_REASON_WAKE_MOTION, details=android.policy:MOTION)...
PowerManagerService: Screen on took ... ms
```

This is the cleanest reproduction and should be used for future tests.

### 1.3 Checking whether Fn-F2 is visible to Android

The device has a hardware key combination, Fn-F2, that disables the touchpad.

We tested whether Fn-F2 produces Android-visible input events using `getevent`.

Result: Fn-F2 produced no visible event.

Conclusion: Fn-F2 is probably handled inside the keyboard/touchpad controller firmware. Android does not receive a key/scancode for it, so Android cannot emulate it with `adb input keyevent`, and the launcher cannot directly observe that key combo.

### 1.4 Checking whether Fn-F2 changes Android/Linux-visible input state

We compared input state before and after pressing Fn-F2 using:

- `dumpsys input`
- `getevent -lp`
- `/sys/class/input/...`
- event-node mappings under `/sys/class/input/event*`

The diffs were essentially empty. Only transient `age=` fields changed in `dumpsys input`.

Conclusion: Fn-F2 does not change Android-visible device state. It likely just gates touchpad event generation inside the HID controller.

### 1.5 Checking the suspected input device

The suspicious input device is a composite USB HID device:

```text
AMR-TOUCH-MOUSE 202110 USB KEYBOARD
```

It appears as multiple input interfaces, including keyboard/system/consumer/mouse-style nodes.

The wake reason points specifically to motion:

```text
WAKE_REASON_WAKE_MOTION
android.policy:MOTION
```

This implicates the touchpad/mouse portion of the composite HID device, not a keyboard press or app alarm.

### 1.6 Android input-device disable command

We checked for an Android shell command to disable/enable input devices:

```powershell
adb shell cmd input help
```

Result: no usable `cmd input disable` / `cmd input enable` command exists on this build.

Conclusion: there is no exposed non-root Android shell API to disable the input device.

### 1.7 Linux input inhibition

We checked whether the kernel exposes input inhibition nodes:

```powershell
adb shell 'find /sys/class/input -name inhibited -print -exec cat {} \; 2>/dev/null'
```

Result: no `inhibited` nodes exist.

Conclusion: this kernel/build does not expose the clean input-inhibition mechanism.

### 1.8 Kernel wake-source control under `/sys/.../power/wakeup`

We found wakeup-control files under the EHCI/USB hierarchy:

```text
/sys/devices/platform/soc@2900000/5200000.ehci1-controller/usb1/power/wakeup
/sys/devices/platform/soc@2900000/5200000.ehci1-controller/usb1/1-1/power/wakeup
/sys/devices/platform/soc@2900000/5200000.ehci1-controller/usb1/1-1/1-1.3/power/wakeup
/sys/devices/platform/soc@2900000/5200000.ehci1-controller/power/wakeup
```

The relevant branch, `/sys/devices/platform/soc@2900000/5200000.ehci1-controller/usb1/1-1/1-1.3`, appears to be the root for all input devices, not just the touchpad. Disabling it might disable wake from all internal USB HID input devices, which would be acceptable if the physical power button remains available.

However, normal `adb shell` cannot read or write these files:

```text
Permission denied
```

We also tested root:

```powershell
adb shell su -c 'cat /sys/.../power/wakeup'
```

Result:

```text
su: inaccessible or not found
```

Conclusion: wake-source control exists but is inaccessible without root/system privilege.

### 1.9 `/proc/bus/input/devices`

We tried:

```powershell
adb shell 'cat /proc/bus/input/devices'
```

Result:

```text
Permission denied
```

This is not a blocker because `getevent -lp`, `dumpsys input`, and `/sys/class/input` provide enough input-device information.

### 1.10 Screensaver / dream settings

Initial state:

```text
screensaver_enabled = 1
screensaver_activate_on_sleep = 0
screensaver_activate_on_dock = 1
secure doze_* settings = null
system lift_to_wake = null
```

We tested changing screensaver/dream settings, including disabling dock activation and disabling screensaver entirely.

Result: no effect on touchpad-motion wake behavior.

Conclusion: dream/screensaver state was correlated noise. It is not the cause of the wake.

### 1.11 Developer Options / Android settings

We looked for plausible settings keys related to:

- doze
- dream
- screen
- wake
- tap
- lift
- gesture
- ambient display

The relevant doze/tap/lift settings were absent or ineffective on this build.

Conclusion: Developer Options / normal Android settings do not appear to expose a useful toggle for this wake source.

---

## 2. What we found

### 2.1 Root cause class

The root cause is best described as:

```text
internal USB HID touchpad/mouse movement wakes Android from Dozing through input/power policy
```

The key evidence is:

```text
Waking up from Dozing
reason=WAKE_REASON_WAKE_MOTION
details=android.policy:MOTION
```

This is Android policy interpreting pointer motion as a wake gesture/event.

### 2.2 It is not an app/alarm/notification problem

The dumps contain lots of noise from:

- Google Play services / GMS scheduler
- GCM heartbeat/check queue alarms
- `AlarmManager`
- notification records
- USB debugging notifications
- physical keyboard notifications
- `DreamManagerService`
- background wake locks

But none of those is the direct wake trigger in the clean reproduction.

The direct trigger is touchpad/mouse motion.

### 2.3 Fn-F2 is probably firmware-only

Because Fn-F2:

- emits no `getevent` event,
- creates no meaningful `dumpsys input` delta,
- creates no visible `/sys/class/input` state delta,
- yet disables touchpad behavior,

it is probably handled inside the keyboard/touchpad controller firmware.

Android cannot query this firmware-level toggle state directly through normal APIs.

Android also cannot reliably replay or emulate Fn-F2 through `adb input keyevent` because there is no Android-visible key event to replay.

### 2.4 The device is not entering a deeper state with `input keyevent 26`

Sending the power key with:

```powershell
adb shell 'input keyevent 26'
```

produces:

```text
Going to sleep due to power_button
Dozing...
```

not an observable full suspend state that ignores motion.

So the practical state is:

```text
screen off / dozing, but still wakeable by pointer motion
```

### 2.5 The clean low-level fix is blocked without root/system privilege

The technically clean fixes would be one of:

- disable wake on the USB HID branch through `/sys/.../power/wakeup`,
- inhibit or disable the input node,
- unbind the HID driver/interface during sleep,
- patch vendor/system input policy,
- patch framework power/input policy,
- change firmware behavior.

All of those require root, system privilege, custom image modification, or firmware modification.

For this device/build, non-root shell cannot do them.

### 2.6 Composite HID complicates selective control

The relevant USB path appears to be the root for all internal input devices, not just touchpad.

That means even with root, disabling the wake source at the USB branch may disable wake from the internal keyboard as well. This may be acceptable if the physical power button remains available.

Selective touchpad-only control may require a lower-level distinction between HID interfaces, if exposed. Without root, this is not actionable.

---

## 3. Most viable workaround without firmware/root manipulation

### 3.1 Goal

Prevent accidental touchpad movement from leaving the device awake.

Since we cannot stop the wake itself, the workaround is:

```text
wake happens briefly
launcher detects movement-only wake
launcher immediately sends device back to sleep
```

This should make accidental touchpad wakes self-correcting.

### 3.2 Core behavior

The launcher should maintain a short `recentlyWoke` window after screen-on.

During that window:

- mouse/touchpad movement only → immediately sleep again
- mouse button click → stay awake
- keyboard key press → stay awake
- power button wake → stay awake if no pointer-motion event follows
- hidden admin gesture/button → stay awake / open controls

This prevents a noisy or brushed touchpad from waking the device permanently, while preserving intentional wake/use paths.

### 3.3 Recommended Android API: Device Admin + `lockNow()`

The launcher should become an active Device Admin and use:

```kotlin
DevicePolicyManager.lockNow()
```

This requires a `DeviceAdminReceiver` and the `force-lock` policy.

This is preferable to relying on shell commands because it can be invoked directly inside the app when accidental wake is detected.

### 3.4 Event detection strategy

The launcher Activity should log and classify input events immediately after wake.

Relevant event sources/actions:

```kotlin
InputDevice.SOURCE_MOUSE
InputDevice.SOURCE_TOUCHPAD
InputDevice.SOURCE_CLASS_POINTER
MotionEvent.ACTION_HOVER_MOVE
MotionEvent.ACTION_MOVE
MotionEvent.buttonState
```

Movement-only condition:

```text
pointer source
AND action is hover/move
AND buttonState == 0
AND within recently-woke window
```

If true, call `lockNow()` after a short delay.

### 3.5 Why a delay is useful

Calling `lockNow()` directly inside input dispatch can create focus/input cleanup edge cases.

Use a short delay, for example 100–200 ms:

```text
movement-only event detected
consume event
post delayed lockNow()
```

### 3.6 Avoiding wake/sleep loops

Add cooldowns:

- `recentlyWoke` window: approximately 2–3 seconds after `ACTION_SCREEN_ON`.
- `reSleepCooldown`: after calling `lockNow()`, ignore repeated re-sleep attempts for 1–2 seconds.
- If multiple wake-motion events happen rapidly, only call `lockNow()` once.

### 3.7 Required app state machine

Suggested state machine:

```text
NORMAL_AWAKE
  User is actively using device.
  Pointer movement is allowed.

SCREEN_OFF_OR_LOCK_REQUESTED
  App requested sleep or observed screen off.

RECENTLY_WOKE
  Entered on ACTION_SCREEN_ON or Activity resume after screen-on.
  Timer: 2–3 seconds.

RECENTLY_WOKE + pointer movement only
  Consume event.
  Schedule lockNow().
  Enter RE_SLEEPING.

RECENTLY_WOKE + button/key/click/admin gesture
  Treat as intentional.
  Enter NORMAL_AWAKE.

RE_SLEEPING
  lockNow() has been called.
  Cooldown prevents loops.
```

### 3.8 Minimal Kotlin sketch

```kotlin
private var recentlyWoke = false
private var reSleepInProgress = false
private val mainHandler = Handler(Looper.getMainLooper())

private fun markRecentlyWoke() {
    recentlyWoke = true
    mainHandler.postDelayed({
        recentlyWoke = false
    }, 2500)
}

override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (shouldReSleepFromPointerMotion(event)) {
        scheduleReSleep()
        return true
    }

    return super.dispatchGenericMotionEvent(event)
}

override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    if (shouldReSleepFromPointerMotion(event)) {
        scheduleReSleep()
        return true
    }

    return super.dispatchTouchEvent(event)
}

private fun shouldReSleepFromPointerMotion(event: MotionEvent): Boolean {
    if (!recentlyWoke) return false
    if (reSleepInProgress) return false

    val isPointer =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
        event.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
        event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)

    val isMovementOnly =
        event.action == MotionEvent.ACTION_HOVER_MOVE ||
        event.action == MotionEvent.ACTION_MOVE

    val hasButtonPressed = event.buttonState != 0

    return isPointer && isMovementOnly && !hasButtonPressed
}

private fun scheduleReSleep() {
    reSleepInProgress = true
    recentlyWoke = false

    mainHandler.postDelayed({
        val devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(
            this,
            KioskDeviceAdminReceiver::class.java
        )

        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.lockNow()
        }

        mainHandler.postDelayed({
            reSleepInProgress = false
        }, 1500)
    }, 150)
}
```

### 3.9 Screen-on receiver

```kotlin
private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> markRecentlyWoke()
            Intent.ACTION_USER_PRESENT -> {
                recentlyWoke = false
                reSleepInProgress = false
            }
        }
    }
}

override fun onStart() {
    super.onStart()

    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT)
    }

    registerReceiver(screenReceiver, filter)
}

override fun onStop() {
    unregisterReceiver(screenReceiver)
    super.onStop()
}
```

### 3.10 Device admin pieces

Receiver:

```kotlin
class KioskDeviceAdminReceiver : DeviceAdminReceiver()
```

Manifest:

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

`res/xml/device_admin.xml`:

```xml
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

Activation for development:

```powershell
adb shell 'dpm set-active-admin toys.compy.launcher/.KioskDeviceAdminReceiver'
```

If this fails, activate manually in Android settings.

### 3.11 Logging test for feasibility

Before implementing the automatic lock behavior, add logging:

```kotlin
Log.i(
    "KioskWakeInput",
    "action=${event.action} source=${event.source} buttons=${event.buttonState} device=${event.device?.name}"
)
```

Then test:

```powershell
adb logcat -c
adb shell 'input keyevent 26'
adb logcat -v threadtime KioskWakeInput:I PowerManagerService:I '*:S'
```

Touch the touchpad.

If `KioskWakeInput` logs a movement event after wake, the workaround is feasible.

If the app never receives the post-wake movement event, use lifecycle-only detection as a weaker fallback:

```text
on screen-on / resume:
  wait a very short interval
  if no key/click/admin action happens
  sleep again
```

That fallback is harsher because it may also re-sleep some intentional wakes.

### 3.12 Expected user experience

With the workaround:

- accidental touchpad movement wakes the screen for a fraction of a second and then it goes dark again;
- clicking a mouse/touchpad button should keep the device awake;
- pressing a key should keep the device awake;
- physical power button remains the reliable explicit wake path;
- manual Fn-F2 remains the best hardware-side mitigation if the user wants zero accidental touchpad wake.

---

## 4. Alternative non-root workaround: fake sleep

If immediate re-sleep is not acceptable, the other viable non-root workaround is not to use Android sleep at all.

Instead, the launcher implements a fake sleep mode:

```text
on idle timeout:
  keep device awake
  show fullscreen black Activity
  dim brightness to minimum
  ignore pointer movement
  require explicit key/button/hidden gesture to exit fake sleep
```

Pros:

- avoids Android wake policy entirely;
- no root needed;
- full control inside app;
- no accidental wake because the device never enters Android doze/screen-off wake state.

Cons:

- worse power saving;
- screen/backlight behavior may still consume power;
- may not be acceptable if true display-off is required;
- may need brightness and immersive-mode handling.

Shell brightness test:

```powershell
adb shell 'settings get system screen_brightness'
adb shell 'settings put system screen_brightness 1'
```

The app can use Android brightness APIs instead of shell commands.

This approach is suitable if the device is mostly plugged in and kiosk reliability matters more than power efficiency.

---

## 5. What not to keep chasing

### Google services / GMS alarms

The dumps contain GMS alarms and wakelocks, but they are not the direct cause of the manual touchpad-triggered wake.

### Notification channels

USB debugging and physical keyboard notifications appear in the notification dump. They are not the cause of the motion wake.

### Screensaver / dreams

Screensaver settings were tested and had no effect. Dream/doze records are incidental state information, not the control point.

### Emulating Fn-F2

Fn-F2 emits no Android-visible event. There is no event to replay through `adb input keyevent`.

### Querying exact touchpad on/off state

Because Fn-F2 leaves no Android/Linux-visible state delta, Android cannot reliably query the firmware toggle state. Only behavioral inference is possible: move the touchpad and see whether events arrive.

### More ADB dumping

Further dumps are unlikely to reveal a non-root control knob. The core mechanism is already identified.

---

## 6. If root/system modification becomes acceptable later

The likely clean fixes, in priority order, would be:

1. Disable wake for the USB HID branch:

```text
echo disabled > /sys/devices/platform/soc@2900000/5200000.ehci1-controller/usb1/1-1/1-1.3/power/wakeup
```

2. Disable wake at the parent USB branch if the specific interface is not separable.

3. Inhibit or disable the input node if the kernel exposes such a mechanism after root/custom kernel changes.

4. Unbind/rebind the relevant HID interface around sleep/wake.

5. Patch vendor/framework input policy so pointer motion is not wake-capable.

6. Patch firmware/controller behavior if available, which is unlikely.

Caution: because the internal keyboard/touchpad is a composite USB HID device, disabling wake at the USB branch may disable keyboard wake too. If the physical power button remains independent, this may still be acceptable.

---

## 7. Recommended next implementation step

Implement and test the launcher-level re-sleep workaround.

Minimum test plan:

1. Add device-admin receiver with `force-lock`.
2. Activate the launcher as device admin.
3. Add logging for `dispatchGenericMotionEvent` and `dispatchTouchEvent`.
4. Reproduce:

```powershell
adb logcat -c
adb shell 'input keyevent 26'
adb logcat -v threadtime KioskWakeInput:I PowerManagerService:I '*:S'
```

5. Touch the touchpad.
6. Confirm whether the launcher receives movement-only input immediately after wake.
7. If yes, add movement-only re-sleep logic.
8. Add cooldown and intentional-input escape conditions.
9. Test cases:

```text
touchpad movement only       -> wake briefly, immediately sleep again
touchpad click               -> stay awake
keyboard key                 -> stay awake
power button                 -> stay awake unless followed by movement-only event
admin hidden gesture/control -> stay awake / open admin UI
```

This is the best available path without rooting the device or modifying firmware/system partitions.

