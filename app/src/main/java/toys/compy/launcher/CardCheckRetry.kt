/*
 * Copyright (c) 2026 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

/**
 * Checks the card again while it reports an access failure, for a bounded window.
 *
 * At boot Android marks a portable volume mounted a few seconds before its media storage service
 * attaches it. Until then every write through the volume fails with "Operation not permitted", so
 * the first probe after the mount wait can fail on a card that works moments later. An unreadable
 * or unwritable result is therefore checked again before it becomes a warning.
 */
internal object CardCheckRetry {
    fun run(
        windowMs: Long,
        intervalMs: Long,
        check: () -> CompyCardCheckResult,
        now: () -> Long,
        pause: (Long) -> Unit,
        isCurrent: () -> Boolean,
        onRetry: (CompyCardCheckResult) -> Unit = {},
    ): CompyCardCheckResult? {
        val deadline = now() + windowMs
        while (isCurrent()) {
            val result = check()
            if (!isCurrent()) return null
            if (!isAccessFailure(result) || now() + intervalMs > deadline) return result
            onRetry(result)
            pause(intervalMs)
        }
        return null
    }

    /** The results a volume that the media storage service has not attached yet produces. */
    fun isAccessFailure(result: CompyCardCheckResult): Boolean =
        result.condition == CompyCardCondition.UNREADABLE ||
            result.condition == CompyCardCondition.UNWRITABLE
}
