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
- **SD Initialization**: Maintenance seeds a formatted portable card, restarts the device, and verifies the complete product seed after Android remounts the card.
- **Stock Restore**: Maintenance restores every working program from a chosen on-device stock set, with a retained version choice for each program.

## TODO
- self-update
- update Compy-IDE
- OTA updates

## Configuration
All kiosk behavior is controlled via `KioskConfig.kt`:
- `TARGET_PACKAGE`: The app to keep in foreground (default: `toys.compy.ide`).
- `NORMAL_LAUNCH_DELAY_MS`: Delay before launching the target (default: 2.5s).
- `MAINTENANCE_DURATION_MS`: How long maintenance mode stays active (default: 10m).

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
