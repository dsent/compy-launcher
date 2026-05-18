# Android Touchpad Wake: Investigation and Findings

## Summary

On this Android 13 netbook device, the internal composite USB HID device generates pointer-motion events that Android's input/power policy treats as a wake reason. The decisive log line is:

```text
PowerManagerService: Waking up from Dozing
  reason=WAKE_REASON_WAKE_MOTION
  details=android.policy:MOTION
```

The wake originates inside Android policy, not in apps, alarms, dreams, or the launcher. There is no non-root, non-firmware control surface on this build that can suppress the wake itself.

## Reproduction

```powershell
adb logcat -c
adb shell 'input keyevent 26'
adb logcat -b system -v threadtime `
    PowerManagerService:V PhoneWindowManager:V `
    DisplayPowerController:V InputDispatcher:V '*:S'
```

Then touch or move the touchpad. The wake reproduces deterministically within seconds.

Expected log sequence:

```text
PowerManagerService: Going to sleep due to power_button (uid 1000)...
PowerManagerService: Dozing...
PowerManagerService: Waking up from Dozing (uid=1000,
    reason=WAKE_REASON_WAKE_MOTION, details=android.policy:MOTION)...
PowerManagerService: Screen on took ... ms
```

When classifying a wake from a dump, prioritize the most recent live `Waking up ... reason=...` line in `logcat-recent.txt` over older wakelock entries (e.g. historical `PhoneWindowManager.mPowerKeyWakeLock` records), which can otherwise mislead a heuristic.

## Hardware

The implicated device is a composite USB HID:

```text
AMR-TOUCH-MOUSE 202110 USB KEYBOARD
```

It exposes multiple input interfaces (keyboard, system, consumer, pointer). `WAKE_REASON_WAKE_MOTION` implicates the pointer interface specifically.

The keyboard has an Fn-F2 combination that toggles the touchpad. It is handled inside the controller firmware:

- `getevent` produces no event for it.
- `dumpsys input` shows no meaningful state delta.
- `/sys/class/input/...` shows no state delta.
- Touchpad behavior changes anyway.

Conclusion: Fn-F2 is not visible to Android, cannot be replayed via `adb input keyevent`, and its on/off state cannot be queried by an app.

## State after `input keyevent 26`

The device enters `Dozing`, not full suspend. In this state pointer motion still produces `WAKE_REASON_WAKE_MOTION`. The physical power button continues to work normally as an explicit wake source.

## Control surfaces and their accessibility

| Surface                                            | Available on this build | Usable without root |
| -------------------------------------------------- | ----------------------- | ------------------- |
| `cmd input enable/disable`                         | No                      | n/a                 |
| `/sys/class/input/.../inhibited`                   | No                      | n/a                 |
| `/sys/.../power/wakeup` on the USB HID branch      | Yes                     | No (EACCES)         |
| `/proc/bus/input/devices`                          | Yes                     | No (EACCES)         |
| Screensaver / dream / doze settings                | Yes                     | No effect on motion wake |
| Developer Options doze / tap-to-wake / lift-to-wake| Absent or ineffective   | n/a                 |

`adb shell su` reports `inaccessible or not found`. There is no normal-app or `adb shell` path to flip the kernel `power/wakeup` bit on this build.

## Ruled out as direct causes

These appear repeatedly in dumps but are not the trigger for the manual touchpad-motion reproduction:

- Google Play services / GMS scheduler and alarms.
- GCM heartbeat / check-queue alarms.
- `AlarmManager` entries and notification records.
- USB debugging and physical-keyboard notifications.
- `DreamManagerService`, screensaver settings, `screensaver_activate_on_dock`.
- The custom launcher.

Changing screensaver/dream settings (including disabling dock activation and disabling the screensaver entirely) had no effect on motion wake.

## Composite-HID caveat for any future fix

The relevant USB wakeup path is approximately:

```text
/sys/devices/platform/soc@2900000/5200000.ehci1-controller/usb1/1-1/1-1.3/power/wakeup
```

This branch sits above the entire internal HID composite, not the touchpad interface alone. Disabling wake here would likely disable wake from the built-in keyboard as well. That may still be acceptable in this product because the physical power button stays available.

Selective touchpad-only suppression would require addressing a specific HID interface lower in the tree, which is not actionable without root and a kernel that exposes it.

## Implications for any mitigation

- The wake itself cannot be prevented on this build without root or firmware changes.
- Any non-root mitigation has to accept the wake and react after the fact.
- Fn-F2 remains the only hardware-side way to suppress the wake source at its origin, and it is user-driven, not programmable.
