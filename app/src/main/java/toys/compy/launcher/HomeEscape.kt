/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

internal data class HomeEntryDispatch(
    val handleNow: Boolean,
    val countHomePress: Boolean,
)

/** Keeps singleTask HOME callbacks from counting one press twice. */
internal class HomeEntryGate {
    private var resumed = false
    private var firstResumePending = true
    private var homeDeliveryPendingResume = false

    fun onResume(isHomeIntent: Boolean): HomeEntryDispatch {
        resumed = true
        val countHomePress =
            if (firstResumePending) {
                firstResumePending = false
                isHomeIntent
            } else {
                homeDeliveryPendingResume
            }
        homeDeliveryPendingResume = false
        return HomeEntryDispatch(handleNow = true, countHomePress = countHomePress)
    }

    fun onNewIntent(isHomeIntent: Boolean): HomeEntryDispatch {
        if (!resumed) {
            homeDeliveryPendingResume = isHomeIntent
        }
        return HomeEntryDispatch(
            handleNow = resumed,
            countHomePress = resumed && isHomeIntent,
        )
    }

    fun onPause() {
        resumed = false
    }
}

internal data class HomePressUpdate(
    val retainedHistory: List<Long>,
    val triggered: Boolean,
)

internal fun updateHomePressHistory(
    history: List<Long>,
    now: Long,
    windowMs: Long,
    triggerCount: Int,
): HomePressUpdate {
    val retained = history.filter { timestamp -> now - timestamp < windowMs } + now
    return if (retained.size >= triggerCount) {
        HomePressUpdate(emptyList(), triggered = true)
    } else {
        HomePressUpdate(retained, triggered = false)
    }
}
