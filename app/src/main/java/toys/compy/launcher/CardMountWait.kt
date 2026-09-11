package toys.compy.launcher

import android.os.Environment

/** Wait for Android's asynchronous portable-storage mount before probing files. */
internal object CardMountWait {
    fun await(
        timeoutMs: Long,
        pollMs: Long,
        state: () -> String?,
        now: () -> Long,
        pause: (Long) -> Unit,
        isCurrent: () -> Boolean,
    ): Boolean {
        val deadline = now() + timeoutMs
        while (isCurrent()) {
            val mounting = when (state()) {
                null, Environment.MEDIA_UNKNOWN, Environment.MEDIA_REMOVED,
                Environment.MEDIA_UNMOUNTED, Environment.MEDIA_CHECKING,
                Environment.MEDIA_EJECTING -> true
                else -> false
            }
            val remaining = deadline - now()
            if (!mounting || remaining <= 0) return isCurrent()
            pause(minOf(pollMs, remaining))
        }
        return false
    }
}
