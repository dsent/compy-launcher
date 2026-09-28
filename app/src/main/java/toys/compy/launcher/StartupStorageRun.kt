/*
 * Copyright (c) 2026 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import java.util.concurrent.atomic.AtomicInteger

/**
 * One run of the storage work the launcher finishes before Compy IDE starts: finish project restores
 * on built-in storage that a power cut or crash interrupted, wait for the SD card to mount, check it
 * until Android lets apps write to it, then finish the card's interrupted restores. The wait and the
 * check run only when [prepare] is asked to wait for the card (KioskConfig.STARTUP_CARD_WAIT_ENABLED);
 * otherwise card recovery follows built-in storage at once, as it did in v0.4.2, and fails without
 * harm when the card is not mounted yet.
 *
 * Recovery renames project folders, and moves any folder in its way aside under an .old name. At
 * boot Android refuses writes to a freshly mounted card for a few seconds, so card recovery waits
 * for the check. Built-in storage needs no such wait, and goes first so that a card step that times
 * out cannot leave it unrecovered. A child working in the IDE while recovery runs would find a
 * project replaced under them, so [RecoveryGate] keeps recovery and the IDE apart.
 *
 * Card I/O can block without end on a failing card. Each mount-state query, each check attempt, and
 * the wait for an earlier run on the single card executor run under a hang timeout, after which the
 * launch decision goes ahead without this run. While a recovery runs, the timeout waits for it
 * instead, since the IDE waits for recovery anyway. The mount wait and the pauses between attempts
 * are bounded on their own. Recovery has no such timeout: the IDE waits for it, and the launcher
 * tells the person so once it runs longer than the hang timeout.
 */
internal class StartupStorageRun(
    private val timeoutMs: Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    val isCurrent: () -> Boolean,
    /** Handles an overrun step; returns false to be asked again after another timeout. */
    private val onTimeout: (step: String) -> Boolean,
) {
    private val steps = AtomicInteger()
    private val stepInFlight = AtomicInteger()

    /** Marks the step [name] in flight and arms its hang timeout. Callable from any thread. */
    fun beginStep(name: String): Int {
        val step = steps.incrementAndGet()
        stepInFlight.set(step)
        armTimeout(step, name)
        return step
    }

    private fun armTimeout(step: Int, name: String) {
        schedule(timeoutMs) {
            if (isCurrent() && stepInFlight.get() == step && !onTimeout(name)) armTimeout(step, name)
        }
    }

    fun endStep(step: Int) {
        stepInFlight.compareAndSet(step, 0)
    }

    fun <T> bounded(name: String, block: () -> T): T {
        val step = beginStep(name)
        try {
            return block()
        } finally {
            endStep(step)
        }
    }

    /**
     * Returns the card's condition after recovery, or null when the launcher left this run behind.
     * [awaitMount] polls the state it is given; [retry] repeats the check it is given while the card
     * reports an access failure. Without [waitForCard] none of those three runs, and the card's
     * condition is [NOT_CHECKED].
     */
    fun prepare(
        gate: RecoveryGate,
        recoverInternal: () -> Unit,
        awaitMount: (state: () -> String?) -> Boolean,
        mountState: () -> String?,
        retry: (check: () -> CompyCardCheckResult) -> CompyCardCheckResult?,
        inspect: () -> CompyCardCheckResult,
        recoverCard: (card: CompyCardCheckResult) -> Unit,
        waitForCard: Boolean,
    ): CompyCardCheckResult? {
        if (!gate.runRecovery(isCurrent, recoverInternal)) return null
        val card =
            if (waitForCard) {
                if (!awaitMount { bounded(MOUNT_STATE_STEP, mountState) }) return null
                retry { bounded(CHECK_STEP, inspect) } ?: return null
            } else {
                NOT_CHECKED
            }
        if (!gate.runRecovery(isCurrent) { recoverCard(card) }) return null
        return card
    }

    companion object {
        const val QUEUED_STEP = "Waiting for an earlier card check"
        const val MOUNT_STATE_STEP = "Card mount state query"
        const val CHECK_STEP = "Card check"

        /** The card as reported when startup does not wait for it: not looked at, so not warned about. */
        val NOT_CHECKED =
            CompyCardCheckResult(CompyCardCondition.HEALTHY, detail = "not checked at startup")

        /**
         * Whether a launcher entry has storage work to do. Recovery ends with the first launch, so
         * afterwards only an enabled check has anything to report.
         */
        fun hasWork(checkEnabled: Boolean, launched: Boolean): Boolean = checkEnabled || !launched

        /** Card recovery needs a card; one the check reports as failing may still accept it. */
        fun cardNeedsRecovery(card: CompyCardCheckResult): Boolean =
            card.condition != CompyCardCondition.MISSING

        /**
         * What the launch decision sees. With the startup check switched off a card never produces a
         * warning; recovery, and the mount wait and the check when switched on, still run first.
         */
        fun reportedResult(card: CompyCardCheckResult, checkEnabled: Boolean): CompyCardCheckResult =
            if (checkEnabled) card else CompyCardCheckResult(CompyCardCondition.HEALTHY)

        /**
         * Sleeps [durationMs] in slices of at most [sliceMs] and returns early once [isCurrent] turns
         * false, so a run the launcher left behind frees the executor for the next one within a slice.
         */
        fun pauseWhileCurrent(
            durationMs: Long,
            sliceMs: Long,
            isCurrent: () -> Boolean,
            now: () -> Long,
            sleep: (Long) -> Unit,
        ) {
            val deadline = now() + durationMs
            while (isCurrent()) {
                val remaining = deadline - now()
                if (remaining <= 0) return
                sleep(minOf(sliceMs, remaining))
            }
        }
    }
}

/**
 * The card's mount wait and its check's retry window, each started once per process. A later
 * launcher entry, such as a Home press, whether it arrives as a new intent or after a pause,
 * continues them instead of starting them over, so neither can hold the IDE back for longer than
 * its own length in total.
 */
internal class StartupWindows(private val now: () -> Long) {
    private var mountDeadline: Long? = null
    private var retryDeadline: Long? = null

    /** What is left of the mount wait, starting it at [lengthMs] on the first call. */
    @Synchronized
    fun mountWaitMs(lengthMs: Long): Long = remaining(mountDeadline ?: (now() + lengthMs).also { mountDeadline = it })

    /** What is left of the retry window, starting it at [lengthMs] on the first call. */
    @Synchronized
    fun retryWindowMs(lengthMs: Long): Long = remaining(retryDeadline ?: (now() + lengthMs).also { retryDeadline = it })

    private fun remaining(deadline: Long): Long = (deadline - now()).coerceAtLeast(0L)
}

/**
 * Keeps restore recovery and Compy IDE apart. The IDE starts only while no recovery runs, and
 * startup recovery runs only before the launcher first starts the IDE in this process. After that,
 * recovery would run beside an IDE that is starting or open, or replay a restore over what a child
 * changed, so it is left to the next launcher start and to every Maintenance restore, which
 * recovers first. A new launcher process cannot tell whether the IDE ran before it, so when Android
 * restarts the launcher while the IDE is open, that process recovers once before its first launch,
 * beside the IDE in the background. One instance serves the whole process, because a recreated
 * launcher activity gets a new card executor while a recovery from the old one may still run.
 */
internal class RecoveryGate(
    private val now: () -> Long,
    private val pause: (Long) -> Unit,
    private val pollMs: Long,
) {
    private var runningSince: Long? = null
    private var launchClaimed = false

    /**
     * Runs [recover] once no other recovery runs. Returns false without running it when
     * [stillWanted] turns false first, and true without running it once the IDE has been launched.
     * Blocks the calling thread while another recovery runs.
     */
    fun runRecovery(stillWanted: () -> Boolean, recover: () -> Unit): Boolean {
        while (true) {
            val started =
                synchronized(this) {
                    if (!stillWanted()) return false
                    if (launchClaimed) return true
                    if (runningSince != null) {
                        false
                    } else {
                        runningSince = now()
                        true
                    }
                }
            if (started) break
            pause(pollMs)
        }
        try {
            recover()
        } finally {
            synchronized(this) { runningSince = null }
        }
        return true
    }

    /** Whether the IDE has been launched in this process, which ends startup recovery. */
    fun launched(): Boolean = synchronized(this) { launchClaimed }

    /** How long the running recovery has taken so far, or null when none runs. */
    fun runningForMs(): Long? = synchronized(this) { runningSince?.let { now() - it } }

    /**
     * Returns false while a recovery runs. Otherwise ends startup recovery for this process, calls
     * [invalidate] to stop the current run, and returns true: the IDE may start.
     */
    fun claimLaunch(invalidate: () -> Unit): Boolean =
        synchronized(this) {
            if (runningSince != null) return false
            launchClaimed = true
            invalidate()
            true
        }
}
