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
    val LOCK_TASK_PACKAGES = arrayOf(LAUNCHER_PACKAGE, TARGET_PACKAGE)
    @TargetApi(Build.VERSION_CODES.P)
    const val LOCK_TASK_FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    const val LOCK_TASK_CONFIRM_INTERVAL_MS = 100L
    const val LOCK_TASK_CONFIRM_TIMEOUT_MS = 4000L
    const val NORMAL_LAUNCH_DELAY_MS = 2500L
    const val MIN_LAUNCH_INTERVAL_MS = 5000L
    const val MAX_BACKOFF_DELAY_MS = 15000L
    const val CARD_CHECK_TIMEOUT_MS = 3000L
    const val CARD_CHECK_POLL_MS = 100L
    const val MAINTENANCE_DURATION_MS = 10 * 60 * 1000L
    const val HOME_SECRET_PRESS_COUNT = 5
    const val HOME_SECRET_WINDOW_MS = 5000L
}
