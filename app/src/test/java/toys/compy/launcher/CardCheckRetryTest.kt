package toys.compy.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardCheckRetryTest {
    private fun result(condition: CompyCardCondition) = CompyCardCheckResult(condition)

    @Test
    fun accessFailuresRightAfterMountAreCheckedAgainUntilHealthy() {
        var elapsed = 0L
        val results = listOf(
            result(CompyCardCondition.UNWRITABLE),
            result(CompyCardCondition.UNREADABLE),
            result(CompyCardCondition.HEALTHY),
        )
        var checks = 0
        val retried = mutableListOf<CompyCardCondition>()

        val outcome = CardCheckRetry.run(30000, 3000,
            check = { results[checks++] }, now = { elapsed },
            pause = { elapsed += it }, isCurrent = { true },
            onRetry = { retried += it.condition })

        assertEquals(CompyCardCondition.HEALTHY, outcome?.condition)
        assertEquals(listOf(CompyCardCondition.UNWRITABLE, CompyCardCondition.UNREADABLE), retried)
        assertEquals(3, checks)
        assertEquals(6000L, elapsed)
    }

    @Test
    fun persistentAccessFailureIsReportedWhenTheWindowEnds() {
        var elapsed = 0L
        var checks = 0

        val outcome = CardCheckRetry.run(30000, 3000,
            check = { checks++; result(CompyCardCondition.UNWRITABLE) }, now = { elapsed },
            pause = { elapsed += it }, isCurrent = { true })

        assertEquals(CompyCardCondition.UNWRITABLE, outcome?.condition)
        assertEquals(11, checks)
        assertEquals(30000L, elapsed)
    }

    @Test
    fun otherResultsAreReportedAtOnce() {
        for (condition in listOf(CompyCardCondition.HEALTHY, CompyCardCondition.MISSING,
                CompyCardCondition.UNINITIALIZED, CompyCardCondition.IDENTITY_INVALID)) {
            val outcome = CardCheckRetry.run(30000, 3000,
                check = { result(condition) }, now = { 0L },
                pause = { throw AssertionError("unexpected retry for $condition") }, isCurrent = { true })
            assertEquals(condition, outcome?.condition)
        }
    }

    @Test
    fun leavingLauncherStopsCheckingAgain() {
        var current = true
        var checks = 0

        val outcome = CardCheckRetry.run(30000, 3000,
            check = { checks++; result(CompyCardCondition.UNWRITABLE) }, now = { 0L },
            pause = { current = false }, isCurrent = { current })

        assertNull(outcome)
        assertEquals(1, checks)
    }
}
