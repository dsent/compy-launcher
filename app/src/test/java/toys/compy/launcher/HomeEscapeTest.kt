package toys.compy.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEscapeTest {
    @Test
    fun coldHomeResumeCountsOnce() {
        val gate = HomeEntryGate()

        assertEquals(HomeEntryDispatch(handleNow = true, countHomePress = true), gate.onResume(true))
    }

    @Test
    fun warmHomeIntentCountsImmediately() {
        val gate = HomeEntryGate()
        gate.onResume(true)

        assertEquals(HomeEntryDispatch(handleNow = true, countHomePress = true), gate.onNewIntent(true))
    }

    @Test
    fun pausedHomeIntentWaitsForResumeAndCountsOnce() {
        val gate = HomeEntryGate()
        gate.onResume(true)
        gate.onPause()

        assertEquals(HomeEntryDispatch(handleNow = false, countHomePress = false), gate.onNewIntent(true))
        assertEquals(HomeEntryDispatch(handleNow = true, countHomePress = true), gate.onResume(true))
    }

    @Test
    fun laterResumeWithoutNewHomeDeliveryDoesNotCount() {
        val gate = HomeEntryGate()
        gate.onResume(true)
        gate.onPause()

        assertEquals(HomeEntryDispatch(handleNow = true, countHomePress = false), gate.onResume(true))
    }

    @Test
    fun nonHomeResumeDoesNotCount() {
        val gate = HomeEntryGate()

        assertEquals(HomeEntryDispatch(handleNow = true, countHomePress = false), gate.onResume(false))
    }

    @Test
    fun fifthPressWithinWindowTriggersAndClearsHistory() {
        var history = emptyList<Long>()
        repeat(4) { index ->
            val update = updateHomePressHistory(history, index * 500L, 5000L, 5)
            assertFalse(update.triggered)
            history = update.retainedHistory
        }

        val fifth = updateHomePressHistory(history, 2000L, 5000L, 5)

        assertTrue(fifth.triggered)
        assertTrue(fifth.retainedHistory.isEmpty())
    }

    @Test
    fun expiredPressesDoNotTrigger() {
        val update = updateHomePressHistory(listOf(0L, 500L, 1000L, 1500L), 6500L, 5000L, 5)

        assertFalse(update.triggered)
        assertEquals(listOf(6500L), update.retainedHistory)
    }
}
