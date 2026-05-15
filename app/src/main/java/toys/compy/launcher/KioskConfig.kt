/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

object KioskConfig {
    const val TARGET_PACKAGE = "toys.compy.ide"
    const val NORMAL_LAUNCH_DELAY_MS = 2500L
    const val MIN_LAUNCH_INTERVAL_MS = 5000L
    const val MAX_BACKOFF_DELAY_MS = 15000L
    const val MAINTENANCE_DURATION_MS = 10 * 60 * 1000L
    const val HOME_SECRET_PRESS_COUNT = 5
    const val HOME_SECRET_WINDOW_MS = 5000L
}
