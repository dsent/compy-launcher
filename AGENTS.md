# Agent Guidelines (AGENTS.md)

This document provides critical context and rules for AI agents interacting with the Compy
Launcher codebase.

## 1. Project Overview
- This is a "soft kiosk" launcher for Compy IDE (a console-based Lua-programmable computer
  for children based on löve2d framework).
- **Build Target**: Low-end 32-bit Android 13.0 device. Tablet display, always in landscape mode,
  physical keyboard and mouse.
- Normal UX must not expose a visible home screen.
- Use simple Android Views/programmatic UI. No Compose!
- No overlay permission or device-owner mode used.
- Kiosk behavior is conservative: delayed launches, throttling, and no rapid restart loops.

## 2. Core Logic & Protection
- **The Loop**: The app's primary function is to keep the Compy IDE (`toys.compy.ide`) in the
  foreground. This is triggered in `MainActivity.onResume()`.
- **Intent Flags**: When launching the target app, the flags `FLAG_ACTIVITY_NEW_TASK` and
  `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` are mandatory.
- **Maintenance Mode**: A hidden state that suspends auto-launching to allow system maintenance.

## 3. Configuration & State
- **KioskConfig**: Central place for all kiosk-related constants (timeouts, target package).
- **KioskState**: SharedPreferences-backed helper for persistent state like maintenance mode and
  Home press history.

## 4. Verification
- **Verification**: After modifying `MainActivity.kt` or `AndroidManifest.xml`, check for syntax
  errors or missing imports.

