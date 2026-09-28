/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.annotation.TargetApi
import android.app.admin.DevicePolicyManager
import android.os.Build

object KioskConfig {
    const val LAUNCHER_PACKAGE = CompyStorageContract.LAUNCHER_PACKAGE
    const val TARGET_PACKAGE = CompyStorageContract.IDE_PACKAGE
    // Android hosts USB permission activities in SystemUI; allow them during normal IDE use.
    val LOCK_TASK_PACKAGES = arrayOf(LAUNCHER_PACKAGE, TARGET_PACKAGE, "com.android.systemui")
    @TargetApi(Build.VERSION_CODES.P)
    const val LOCK_TASK_FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    const val LOCK_TASK_CONFIRM_INTERVAL_MS = 100L
    const val LOCK_TASK_CONFIRM_TIMEOUT_MS = 4000L
    const val NORMAL_LAUNCH_DELAY_MS = 2500L
    const val MIN_LAUNCH_INTERVAL_MS = 5000L
    const val MAX_BACKOFF_DELAY_MS = 15000L
    // Pilot devices must reach Compy even when Android transiently denies SD-card access at boot.
    // Off hides only the storage warning: restore recovery still runs first. The warning needs the
    // card check, which runs only with STARTUP_CARD_WAIT_ENABLED.
    const val STARTUP_CARD_CHECK_ENABLED = false
    // Whether startup waits before the IDE for the card to mount and accept writes, checking it until
    // it does. The boot-time denial, whose cause is unknown, was met while startup looked at the card,
    // so both stay off until they pass repeated cold boots on the cards known to meet that denial
    // (compy-launcher-startup-card-check-restored). Off, startup recovers the card's restores without
    // waiting for or checking the card, as v0.4.2 did.
    const val STARTUP_CARD_WAIT_ENABLED = false
    const val CARD_MOUNT_TIMEOUT_MS = 30000L
    const val CARD_MOUNT_POLL_MS = 250L
    const val CARD_CHECK_TIMEOUT_MS = 3000L
    const val CARD_CHECK_RETRY_WINDOW_MS = 30000L
    const val CARD_CHECK_RETRY_INTERVAL_MS = 3000L
    const val CARD_CHECK_POLL_MS = 100L
    const val MAINTENANCE_DURATION_MS = 10 * 60 * 1000L
    const val HOME_SECRET_PRESS_COUNT = 5
    const val HOME_SECRET_WINDOW_MS = 5000L
}
