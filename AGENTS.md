# Agent Guidelines (AGENTS.md)

This document provides critical context and rules for AI agents interacting with the Compy Launcher codebase.

## 1. Project Overview
- This is a "soft kiosk" Home app for Android 13.
- Normal UX must not expose a visible home screen.
- Primary target package is `toys.compy.ide`.
- No overlay permission or device-owner mode used.
- Kiosk behavior is conservative: delayed launches, throttling, and no rapid restart loops.

## 2. Core Logic & Protection
- **The Loop**: The app's primary function is to keep the target app in the foreground. This is triggered in `MainActivity.onResume()`.
- **Intent Flags**: When launching the target app, the flags `FLAG_ACTIVITY_NEW_TASK` and `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` are mandatory.
- **Maintenance Mode**: A hidden state that suspends auto-launching to allow system maintenance.

## 3. Manifest Requirements
- **Home Category**: The `MainActivity` must always have the `<category android:name="android.intent.category.HOME" />` intent filter.
- **Package Visibility**: `AndroidManifest.xml` must include `<queries>` for any app we need to launch, including the target package and the broad launcher intent query for the maintenance app list.

## 4. Configuration & State
- **KioskConfig**: Central place for all kiosk-related constants (timeouts, target package).
- **KioskState**: SharedPreferences-backed helper for persistent state like maintenance mode and Home press history.

## 5. Modification Rules
- **Surgical Edits**: Use `replace_file_content` or `multi_replace_file_content` for specific logic changes.
- **Verification**: After modifying `MainActivity.kt` or `AndroidManifest.xml`, check for syntax errors or missing imports.
- **UI**: Use simple Android Views/programmatic UI. No Compose unless already present.
