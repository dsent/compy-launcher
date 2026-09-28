# Compy Launcher

A lightweight Android "soft kiosk" launcher for [Compy](https://github.com/compy-toys/compy),
a console-based Lua-programmable computer for children based on löve2d framework.
It ensures Compy IDE remains in the foreground with a hidden maintenance mode for system administration.

**Repository**: [dsent/compy-launcher](https://github.com/dsent/compy-launcher)
**License**: [MIT](LICENSE)

## Features
- **Auto-Launch**: Automatically starts Compy IDE on boot or when the Home button is reached.
- **Throttled Restarts**: Smart delay and backoff to avoid launch storms if the target app exits.
- **Maintenance Mode**: A temporary state (default 10 minutes) that suspends auto-launching.
- **Hidden Triggers**:
    - **Five Home presses**: Pressing the Home button 5 times within 5 seconds enables maintenance mode.
    - **Quick Settings Tile**: A "Maintenance" tile to toggle maintenance mode from the notification shade.
- **Maintenance Control**: A hidden screen to launch other apps, access Android settings, manage files, or resume kiosk mode manually.
- **Startup storage**: Each time Launcher starts, before it first starts Compy IDE, it finishes project restores that a power cut or crash interrupted, first on built-in storage, then on the SD card. With `STARTUP_CARD_CHECK_ENABLED` off, the default, Launcher neither waits for the card nor checks it: it recovers the card's restores at once, which fails without harm when Android has not mounted the card yet, and those restores then wait for a Maintenance restore or a later start of Launcher that finds the card mounted. With it on, Launcher waits up to 30 seconds for Android to mount the card, then checks it, and while the card cannot be read or written yet, checks it again every 3 seconds for up to 30 seconds; it recovers the card's restores after the check. Each of these two waits runs once: a return to Launcher during them, such as a Home press, continues them instead of starting over. When Launcher has waited for the card or a recovery for more than 3 seconds, the screen says Compy is getting projects ready. Compy IDE does not start while a recovery runs. Every other step that reads or writes the card or asks Android about it, including waiting for an earlier check to finish, gets at most 3 seconds, except while a recovery runs, when it waits for that recovery to end; after that Launcher shows the storage warning when the startup card check is on and starts Compy IDE when it is off. Restores not recovered before Compy IDE first starts wait for the next start of Launcher, normally the next restart of Compy, or a Maintenance restore. Launcher cannot tell whether Compy IDE ran before Launcher itself started: if Android restarts Launcher while Compy IDE is open, Launcher recovers once more, beside Compy IDE in the background, before bringing it back. After Compy IDE has started, returns to Launcher check the card only when `STARTUP_CARD_CHECK_ENABLED` is on, and then wait only for what remains of the two waits started at startup. With it on, a card that remains unavailable shows the storage warning; Maintenance remains accessible. Only a card that Android shows in the built-in slot is the SD card. USB storage, including a card in a USB card reader, is never used as the SD card, and without an identified card Launcher uses built-in storage. Removable storage that is present but not mounted counts as an unreadable card.
- **SD Initialization**: Maintenance seeds a formatted portable card, restarts the device, and verifies the complete product seed after Android remounts the card.
- **Stock Restore**: Maintenance restores every working program from a chosen on-device stock set, with a retained version choice for each program.

## TODO
- self-update
- update Compy-IDE
- OTA updates

## Configuration
All kiosk behavior is controlled via `KioskConfig.kt`:
- `TARGET_PACKAGE`: The app to keep in foreground (default: `toys.compy.ide`).
- `LOCK_TASK_PACKAGES`: Launcher, IDE, and Android SystemUI, allowing USB permission dialogs during normal IDE use. The allowance covers the entire SystemUI package; notification and navigation features are controlled separately by `LOCK_TASK_FEATURES`.
- `NORMAL_LAUNCH_DELAY_MS`: Delay before launching the target (default: 2.5s).
- `MAINTENANCE_DURATION_MS`: How long maintenance mode stays active (default: 10m).
- `STARTUP_CARD_CHECK_ENABLED`: The one switch for what Launcher does with the card before Compy IDE starts (default: off). On, it waits for the card to mount and accept writes, checks it, and shows the storage warning for a card that remains unavailable, at startup and on returns to Launcher. It stays off until it passes repeated cold boots on the cards known to be refused at boot. With it off, Launcher still recovers interrupted restores before it first starts Compy IDE. Outside the switch: a card initialization started in Maintenance is verified in Maintenance after its restart, and the screen shown while a restore recovery runs long mentions the card.

## Getting Started
1. Install the app.
2. Set **Compy Launcher** as the default Home app in Android Settings.
3. To escape: Use the Quick Settings tile or press the Home button 5 times within 5 seconds.

## Requirements
- Android 13 (API 33) is the primary target.
- `minSdk` 24 (required for `TileService`).

## Building

Release packaging requires the external PKCS#12 keystore
`compy-android-release.p12`. Set
`COMPY_ANDROID_KEYSTORE_PATH`, `COMPY_ANDROID_KEYSTORE_PASSWORD`,
`COMPY_ANDROID_KEY_ALIAS`, and `COMPY_ANDROID_KEY_PASSWORD`, then run
`./gradlew assembleRelease`. The keystore remains outside the repository.

The `Package signed launcher` workflow uses the `android-release` environment.
Configure `ANDROID_KEYSTORE_ALIAS`, `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_KEYPASSWORD`, and `ANDROID_KEYSTORE_STOREPASSWORD` as secrets,
and `ANDROID_SIGNING_CERT_SHA256` as the expected non-secret certificate digest.
