package toys.compy.launcher

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStorageRunTest {
    /** Holds armed hang timeouts until the test lets them expire. */
    private class Timers {
        private val armed = mutableListOf<() -> Unit>()

        fun schedule(@Suppress("UNUSED_PARAMETER") delayMs: Long, action: () -> Unit) {
            armed += action
        }

        fun expireAll() {
            val due = armed.toList()
            armed.clear()
            due.forEach { it() }
        }
    }

    private class Paused : RuntimeException()

    private val timers = Timers()
    private val timedOut = mutableListOf<String>()
    private var current = true
    private var handled = true
    private var clock = 0L
    private val gate = RecoveryGate(now = { clock }, pause = { throw Paused() }, pollMs = 100)

    private fun newRun() =
        StartupStorageRun(
            timeoutMs = 3000,
            schedule = timers::schedule,
            isCurrent = { current },
            onTimeout = { timedOut += it; handled },
        )

    private fun result(condition: CompyCardCondition) = CompyCardCheckResult(condition)

    private fun StartupStorageRun.prepareWith(
        recoverInternal: () -> Unit = {},
        awaitMount: (state: () -> String?) -> Boolean = { state -> state(); true },
        mountState: () -> String? = { Environment.MEDIA_MOUNTED },
        retry: (check: () -> CompyCardCheckResult) -> CompyCardCheckResult? = { check -> check() },
        inspect: () -> CompyCardCheckResult = { result(CompyCardCondition.HEALTHY) },
        recover: (CompyCardCheckResult) -> Unit = {},
        waitForCard: Boolean = true,
    ) = prepare(gate, recoverInternal, awaitMount, mountState, retry, inspect, recover, waitForCard)

    @Test
    fun withoutWaitingTheCardIsNeitherWaitedForNorCheckedAndStillRecovered() {
        val events = mutableListOf<String>()

        val card = newRun().prepareWith(
            recoverInternal = { events += "recover internal" },
            awaitMount = { throw AssertionError("waited for the card") },
            mountState = { throw AssertionError("asked for the mount state") },
            retry = { throw AssertionError("retried the check") },
            inspect = { throw AssertionError("checked the card") },
            recover = { events += "recover card" },
            waitForCard = false,
        )
        timers.expireAll()

        assertEquals(listOf("recover internal", "recover card"), events)
        assertSame(StartupStorageRun.NOT_CHECKED, card)
        assertTrue(StartupStorageRun.cardNeedsRecovery(StartupStorageRun.NOT_CHECKED))
        // An unchecked card is never warned about, even once the startup check is switched on.
        assertTrue(StartupStorageRun.reportedResult(StartupStorageRun.NOT_CHECKED, true).healthy)
        assertEquals(emptyList<String>(), timedOut)
    }

    @Test
    fun withoutWaitingTheIdeStillCannotStartWhileCardRecoveryRuns() {
        var claimedDuringRecovery: Boolean? = null
        newRun().prepareWith(
            recover = { claimedDuringRecovery = gate.claimLaunch { current = false } },
            waitForCard = false,
        )
        assertEquals(false, claimedDuringRecovery)
    }

    @Test
    fun builtInStorageRecoversFirstAndTheCardOnlyAfterItsCheck() {
        val results = mutableListOf(
            result(CompyCardCondition.UNWRITABLE),
            result(CompyCardCondition.HEALTHY),
        )
        val events = mutableListOf<String>()

        val card = newRun().prepareWith(
            recoverInternal = { events += "recover internal" },
            awaitMount = { state -> events += "mount ${state()}"; true },
            retry = { check ->
                var checked = check()
                while (CardCheckRetry.isAccessFailure(checked)) checked = check()
                checked
            },
            inspect = { results.removeAt(0).also { events += "check ${it.condition}" } },
            recover = { events += "recover ${it.condition}" },
        )

        assertEquals(
            listOf("recover internal", "mount mounted", "check UNWRITABLE", "check HEALTHY", "recover HEALTHY"),
            events,
        )
        assertEquals(CompyCardCondition.HEALTHY, card?.condition)
    }

    @Test
    fun aMountStateQueryOrCheckAttemptThatHangsTimesOut() {
        newRun().prepareWith(
            mountState = { timers.expireAll(); Environment.MEDIA_MOUNTED },
            inspect = { timers.expireAll(); result(CompyCardCondition.HEALTHY) },
        )

        assertEquals(listOf(StartupStorageRun.MOUNT_STATE_STEP, StartupStorageRun.CHECK_STEP), timedOut)
    }

    @Test
    fun recoveryTheWaitsBetweenStepsAndFinishedStepsNeverTimeOut() {
        newRun().prepareWith(
            recoverInternal = { timers.expireAll() },
            awaitMount = { state -> state(); timers.expireAll(); state(); true },
            retry = { check ->
                check()
                timers.expireAll()
                check()
            },
            recover = { timers.expireAll() },
        )
        timers.expireAll()

        assertEquals(emptyList<String>(), timedOut)
    }

    @Test
    fun theIdeCannotStartWhileRecoveryRuns() {
        var claimedDuringRecovery = true
        var recoveringFor: Long? = null

        newRun().prepareWith(
            recover = {
                clock += 5000
                recoveringFor = gate.runningForMs()
                claimedDuringRecovery = gate.claimLaunch { current = false }
            },
        )

        assertFalse(claimedDuringRecovery)
        assertEquals(5000L, recoveringFor)
        assertTrue(current)
        assertNull(gate.runningForMs())
        assertTrue(gate.claimLaunch { current = false })
        assertFalse(current)
    }

    @Test
    fun aCardStepThatTimesOutLeavesBuiltInStorageRecoveredAndTheCardAlone() {
        var internalRecovered = false
        var cardRecovered = false

        // A check attempt times out, the launch decision goes ahead, and the attempt returns later.
        val card = newRun().prepareWith(
            recoverInternal = { internalRecovered = true },
            inspect = {
                timers.expireAll()
                assertTrue(gate.claimLaunch { current = false })
                result(CompyCardCondition.HEALTHY)
            },
            recover = { cardRecovered = true },
        )

        assertEquals(listOf(StartupStorageRun.CHECK_STEP), timedOut)
        assertNull(card)
        assertTrue(internalRecovered)
        assertFalse(cardRecovered)
    }

    @Test
    fun afterTheFirstLaunchLaterRunsStillReportTheCardButNeverRecover() {
        assertTrue(gate.claimLaunch {})
        var recovered = false

        // A Home press while the launch is still in flight starts a new run.
        val card = newRun().prepareWith(
            recoverInternal = { recovered = true },
            inspect = { result(CompyCardCondition.UNWRITABLE) },
            recover = { recovered = true },
        )

        assertEquals(CompyCardCondition.UNWRITABLE, card?.condition)
        assertFalse(recovered)
    }

    @Test
    fun aSecondRecoveryWaitsForTheFirst() {
        var secondRan = false

        gate.runRecovery({ true }) {
            assertThrows(Paused::class.java) {
                gate.runRecovery({ true }) { secondRan = true }
            }
        }

        assertFalse(secondRan)
        assertTrue(gate.runRecovery({ true }) { secondRan = true })
        assertTrue(secondRan)
    }

    @Test
    fun aClaimNeverSlipsBetweenTheWantedCheckAndTheStartOfRecovery() {
        // The recovery thread reads "still wanted" and then pauses inside that check. A launch claim
        // made meanwhile must wait for the gate, so recovery and claim never both succeed.
        val threadGate = RecoveryGate(now = System::nanoTime, pause = { Thread.sleep(it) }, pollMs = 1)
        val wanted = java.util.concurrent.atomic.AtomicBoolean(true)
        val insideCheck = java.util.concurrent.CountDownLatch(1)
        val leaveCheck = java.util.concurrent.CountDownLatch(1)
        val finishRecovery = java.util.concurrent.CountDownLatch(1)
        val recovered = java.util.concurrent.atomic.AtomicBoolean(false)
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val recovery = Thread {
            threadGate.runRecovery(
                stillWanted = {
                    val answer = wanted.get()
                    insideCheck.countDown()
                    leaveCheck.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    answer
                },
                recover = {
                    recovered.set(true)
                    finishRecovery.await(5, java.util.concurrent.TimeUnit.SECONDS)
                },
            )
        }
        recovery.start()
        assertTrue(insideCheck.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val claim = Thread { claimed.set(threadGate.claimLaunch { wanted.set(false) }) }
        claim.start()
        val deadline = System.currentTimeMillis() + 5000
        while (claim.state != Thread.State.BLOCKED && claim.state != Thread.State.TERMINATED &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(1)
        }
        leaveCheck.countDown()
        claim.join(5000)
        finishRecovery.countDown()
        recovery.join(5000)

        assertTrue(recovered.get())
        assertFalse(claimed.get())
    }

    @Test
    fun theMountWaitAndRetryWindowStartOncePerProcess() {
        val windows = StartupWindows(now = { clock })

        assertEquals(30000L, windows.mountWaitMs(30000))
        clock += 10000
        assertEquals(20000L, windows.mountWaitMs(30000))
        assertEquals(30000L, windows.retryWindowMs(30000))
        clock += 40000
        assertEquals(0L, windows.mountWaitMs(30000))
        assertEquals(0L, windows.retryWindowMs(30000))
    }

    @Test
    fun leavingTheLauncherDuringTheCheckSkipsCardRecovery() {
        var recovered = false

        val card = newRun().prepareWith(
            inspect = { current = false; result(CompyCardCondition.HEALTHY) },
            recover = { recovered = true },
        )

        assertNull(card)
        assertFalse(recovered)
    }

    @Test
    fun aMountWaitOrRetryThatGivesUpSkipsTheRest() {
        var checked = false
        var recovered = false

        assertNull(newRun().prepareWith(
            awaitMount = { false },
            inspect = { checked = true; result(CompyCardCondition.HEALTHY) },
            recover = { recovered = true },
        ))
        assertNull(newRun().prepareWith(
            retry = { null },
            recover = { recovered = true },
        ))

        assertFalse(checked)
        assertFalse(recovered)
    }

    @Test
    fun aRunTheLauncherLeftNeverTimesOut() {
        val run = newRun()
        run.beginStep(StartupStorageRun.CHECK_STEP)
        current = false

        timers.expireAll()

        assertEquals(emptyList<String>(), timedOut)
    }

    @Test
    fun aTimeoutDeferredWhileAnotherRecoveryRunsIsAskedAgainUntilHandled() {
        val run = newRun()
        run.beginStep(StartupStorageRun.QUEUED_STEP)

        handled = false
        timers.expireAll()
        timers.expireAll()
        handled = true
        timers.expireAll()
        timers.expireAll()

        assertEquals(List(3) { StartupStorageRun.QUEUED_STEP }, timedOut)
    }

    @Test
    fun aRunQueuedBehindAStuckCheckTimesOutUntilItStarts() {
        val stuck = newRun()
        stuck.beginStep(StartupStorageRun.QUEUED_STEP)
        timers.expireAll()
        assertEquals(listOf(StartupStorageRun.QUEUED_STEP), timedOut)

        timedOut.clear()
        val started = newRun()
        started.endStep(started.beginStep(StartupStorageRun.QUEUED_STEP))
        timers.expireAll()
        assertEquals(emptyList<String>(), timedOut)
    }

    @Test
    fun everyCardPresentIsRecoveredEvenWhenItsCheckFailed() {
        val skipped = CompyCardCondition.entries.filterNot {
            StartupStorageRun.cardNeedsRecovery(result(it))
        }

        assertEquals(listOf(CompyCardCondition.MISSING), skipped)
    }

    @Test
    fun withTheCheckOffOnlyEntriesBeforeTheFirstLaunchWaitForTheCard() {
        assertTrue(StartupStorageRun.hasWork(checkEnabled = false, launched = gate.launched()))
        assertTrue(gate.claimLaunch {})

        assertFalse(StartupStorageRun.hasWork(checkEnabled = false, launched = gate.launched()))
        assertTrue(StartupStorageRun.hasWork(checkEnabled = true, launched = gate.launched()))
    }

    @Test
    fun withTheCheckOffTheLaunchDecisionNeverSeesAWarning() {
        CompyCardCondition.entries.forEach { condition ->
            val card = result(condition)
            assertTrue(StartupStorageRun.reportedResult(card, checkEnabled = false).healthy)
            assertSame(card, StartupStorageRun.reportedResult(card, checkEnabled = true))
        }
    }

    @Test
    fun pausingSleepsTheWholeIntervalInSlices() {
        var elapsed = 0L
        val sleeps = mutableListOf<Long>()

        StartupStorageRun.pauseWhileCurrent(
            durationMs = 3000, sliceMs = 250, isCurrent = { true },
            now = { elapsed }, sleep = { sleeps += it; elapsed += it },
        )

        assertEquals(3000L, elapsed)
        assertEquals(12, sleeps.size)
    }

    @Test
    fun pausingStopsWithinASliceOnceTheLauncherLeaves() {
        var elapsed = 0L

        StartupStorageRun.pauseWhileCurrent(
            durationMs = 3000, sliceMs = 250, isCurrent = { current },
            now = { elapsed }, sleep = { elapsed += it; if (elapsed >= 500) current = false },
        )

        assertEquals(500L, elapsed)
    }
}
