package toys.compy.launcher

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMountWaitTest {
    @Test
    fun bootWaitsThroughAbsentUnmountedAndCheckingUntilMounted() {
        var elapsed = 0L
        val states = listOf(null, Environment.MEDIA_UNMOUNTED, Environment.MEDIA_CHECKING, Environment.MEDIA_MOUNTED)
        var index = 0
        assertTrue(CardMountWait.await(30000, 250,
            state = { states[index] }, now = { elapsed },
            pause = { elapsed += it; index++ }, isCurrent = { true }))
        assertEquals(750L, elapsed)
        assertEquals(Environment.MEDIA_MOUNTED, states[index])
    }

    @Test
    fun permanentlyAbsentOrUnmountedCardReachesBoundedCheck() {
        for (state in listOf(null, Environment.MEDIA_REMOVED, Environment.MEDIA_UNMOUNTED)) {
            var elapsed = 0L
            assertTrue(CardMountWait.await(1000, 300,
                state = { state }, now = { elapsed },
                pause = { elapsed += it }, isCurrent = { true }))
            assertEquals(1000L, elapsed)
        }
    }

    @Test
    fun mountedReadOnlyAndBrokenCardsProceedToTheirActualChecksImmediately() {
        for (state in listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY,
                Environment.MEDIA_UNMOUNTABLE, Environment.MEDIA_BAD_REMOVAL)) {
            assertTrue(CardMountWait.await(30000, 250,
                state = { state }, now = { 0L },
                pause = { throw AssertionError("unexpected mount wait for $state") }, isCurrent = { true }))
        }
    }

    @Test
    fun leavingLauncherCancelsWaitBeforeAnyCardCheck() {
        var current = true
        var pauses = 0
        assertFalse(CardMountWait.await(30000, 250,
            state = { Environment.MEDIA_CHECKING }, now = { 0L },
            pause = { pauses++; current = false }, isCurrent = { current }))
        assertEquals(1, pauses)
    }

    @Test
    fun supersededCheckNeverReadsStorage() {
        assertFalse(CardMountWait.await(30000, 250,
            state = { throw AssertionError("superseded check read storage") }, now = { 0L },
            pause = { throw AssertionError("superseded check waited") }, isCurrent = { false }))
    }
}
