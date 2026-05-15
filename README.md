# Compy Launcher

A lightweight Android "soft kiosk" Home app designed for Android 13. It ensures Compy IDE remains in the foreground with a hidden maintenance mode for system administration.

## Features
- **Auto-Launch**: Automatically starts Compy IDE on boot or when the Home button is reached.
- **Throttled Restarts**: Smart delay and backoff to avoid launch storms if the target app exits.
- **Maintenance Mode**: A temporary state (default 10 minutes) that suspends auto-launching.
- **Hidden Triggers**:
    - **Triple Home Press**: Pressing the Home button 3 times within 5 seconds enables maintenance mode.
    - **Quick Settings Tile**: A "Kiosk Mode" tile to toggle maintenance mode from the notification shade.
- **Maintenance Control**: A hidden screen to launch other apps, access Android settings, or resume kiosk mode manually.

## Configuration
All kiosk behavior is controlled via `KioskConfig.kt`:
- `TARGET_PACKAGE`: The app to keep in foreground (default: `toys.compy.ide`).
- `NORMAL_LAUNCH_DELAY_MS`: Delay before launching the target (default: 2.5s).
- `MAINTENANCE_DURATION_MS`: How long maintenance mode stays active (default: 10m).

## Getting Started
1. Install the app.
2. Set **Compy Launcher** as the default Home app in Android Settings.
3. To escape: Use the Quick Settings tile or triple-press the Home button.

## Requirements
- Android 13 (API 33) is the primary target.
- `minSdk` 24 (required for `TileService`).
