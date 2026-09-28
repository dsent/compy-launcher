/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunchAttemptTime = 0L
    private var backoffDelay = 0L
    private var maintenanceOpening = false
    private val homeEntryGate = HomeEntryGate()
    private val cardCheckExecutor = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    @Volatile private var cardCheckGeneration = 0
    // When the launcher started waiting for a result; a new run while the wait goes on keeps it.
    private var cardCheckStartedAt = 0L
    private var launcherResumed = false
    private var cardCheckResult: CompyCardCheckResult? = null
    private var activeCardWarning: CompyCardCheckResult? = null
    private var cardWarningVisible = false
    private var restoreWaitView: TextView? = null

    private val launchRunnable = Runnable {
        performLaunch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LockTaskController.configureWakeVisibility(this)

        // Minimal blank view
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
    }

    override fun onDestroy() {
        cardCheckGeneration++
        cardCheckExecutor.shutdownNow()
        super.onDestroy()
    }

    // Runs on the card check executor, inside the recovery gate. A failed recovery is logged; the
    // next launcher start after a reboot and every Maintenance restore try again.
    private fun recoverPendingProjectRestores(kind: BackupSourceKind, findStorage: () -> MountedCompyStorage) {
        handler.postDelayed(recoveryOverrunCheck, KioskConfig.CARD_CHECK_TIMEOUT_MS)
        try {
            val storage = findStorage()
            CompyBackupStore.recoverPendingRestoresOnStartup(
                BackupStorageEndpoint(
                    kind = kind,
                    id = storage.id,
                    compyDirectory = storage.compyDirectory,
                ),
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not reconcile ${kind.wireName} project restores", error)
        }
    }

    // Shows the waiting message when a recovery outlasts the hang timeout, whether or not the
    // launch decision is polling: the card warning stops that polling.
    private val recoveryOverrunCheck = Runnable {
        if (recoveryOverran() && !isFinishing && !isDestroyed) showRestoreWait()
    }

    private fun recoveryOverran(): Boolean =
        (recoveryGate.runningForMs() ?: 0L) >= KioskConfig.CARD_CHECK_TIMEOUT_MS

    override fun onResume() {
        super.onResume()
        launcherResumed = true
        LockTaskController.configureWakeVisibility(this)
        handleLauncherEntry(homeEntryGate.onResume(intent.isHomeIntent()))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val dispatch = homeEntryGate.onNewIntent(intent.isHomeIntent())
        if (dispatch.handleNow) {
            handleLauncherEntry(dispatch)
        }
    }

    override fun onPause() {
        launcherResumed = false
        cardCheckGeneration++
        homeEntryGate.onPause()
        super.onPause()
        handler.removeCallbacks(launchRunnable)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cardWarningVisible) return super.dispatchKeyEvent(event)
        val result = activeCardWarning ?: return true
        when (cardWarningKeyAction(event.keyCode, event.action, event.isCanceled)) {
            CardWarningKeyAction.CONTINUE -> {
                continueAfterCardWarning(result)
                return true
            }
            CardWarningKeyAction.OPEN_MAINTENANCE -> {
                openMaintenanceAfterCardWarning(result)
                return true
            }
            CardWarningKeyAction.IGNORE -> Unit
        }
        if (
            event.keyCode == KeyEvent.KEYCODE_Y ||
            event.keyCode == KeyEvent.KEYCODE_N ||
            event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (!cardWarningVisible) super.onBackPressed()
    }

    private fun handleLauncherEntry(dispatch: HomeEntryDispatch) {
        if (KioskState.pendingCardInitialization(this) != null) {
            KioskState.enableMaintenance(this, KioskConfig.MAINTENANCE_DURATION_MS)
            openMaintenanceMode()
            return
        }
        val secretTriggered =
            dispatch.countHomePress && KioskState.recordHomeResumeAndCheckSecret(this)
        if (secretTriggered || KioskState.isMaintenanceActive(this)) {
            openMaintenanceMode()
        } else {
            maintenanceOpening = false
            beginCardCheck()
            scheduleLaunch()
        }
    }

    private fun openMaintenanceMode(cardInitializationRequested: Boolean = false) {
        if (maintenanceOpening) {
            return
        }
        maintenanceOpening = true
        if (cardInitializationRequested) {
            KioskState.requestCardInitialization(this)
        }
        handler.removeCallbacks(launchRunnable)
        val appContext = applicationContext
        LockTaskController.disarm(
            appContext,
            onReady = {
                maintenanceOpening = false
                val maintenanceIntent = Intent(appContext, KioskControlActivity::class.java)
                maintenanceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(maintenanceIntent)
            },
            onFailure = { message ->
                maintenanceOpening = false
                if (cardInitializationRequested) {
                    KioskState.clearCardInitializationRequest(appContext)
                }
                Log.e(TAG, message)
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun scheduleLaunch() {
        handler.removeCallbacks(launchRunnable)

        val now = SystemClock.elapsedRealtime()
        val timeSinceLastLaunch = now - lastLaunchAttemptTime

        var delay = KioskConfig.NORMAL_LAUNCH_DELAY_MS

        // If the target returns very quickly, apply backoff
        if (timeSinceLastLaunch < KioskConfig.MIN_LAUNCH_INTERVAL_MS) {
            backoffDelay = (backoffDelay + 2000L).coerceAtMost(KioskConfig.MAX_BACKOFF_DELAY_MS)
            delay = backoffDelay
        } else {
            backoffDelay = 0L
        }

        handler.postDelayed(launchRunnable, delay)
    }

    // The IDE starts only once cardCheckResult is set and no restore recovery runs.
    // StartupStorageRun explains the order of the work and which steps run under the hang timeout.
    private fun beginCardCheck() {
        val generation = ++cardCheckGeneration
        if (!StartupStorageRun.hasWork(KioskConfig.STARTUP_CARD_CHECK_ENABLED, recoveryGate.launched())) {
            // v0.4.2 bypass after the first launch: nothing to recover and no warning to show.
            cardCheckResult = CompyCardCheckResult(CompyCardCondition.HEALTHY)
            return
        }
        if (cardCheckResult != null || cardCheckStartedAt == 0L) {
            cardCheckStartedAt = SystemClock.elapsedRealtime()
        }
        cardCheckResult = null
        val run =
            StartupStorageRun(
                timeoutMs = KioskConfig.CARD_CHECK_TIMEOUT_MS,
                schedule = { delayMs, action -> handler.postDelayed(action, delayMs) },
                isCurrent = { generation == cardCheckGeneration },
                onTimeout = { step -> onCardStepTimeout(generation, step) },
            )
        // The executor runs one check at a time, so an earlier check stuck on card I/O would hold
        // this one back without a timeout of its own.
        val queued = run.beginStep(StartupStorageRun.QUEUED_STEP)
        cardCheckExecutor.execute {
            run.endStep(queued)
            val card =
                try {
                    prepareStartupStorage(run)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } catch (error: Throwable) {
                    // Even an Error on this thread must reach the launch decision.
                    Log.e(TAG, "Startup storage work failed", error)
                    CompyCardCheckResult(
                        condition = CompyCardCondition.UNREADABLE,
                        detail = error.message ?: error.javaClass.name,
                    )
                }
            if (card == null) {
                Log.i(TAG, "Startup storage work stopped early; restores it did not reach wait for a later start or Maintenance")
                return@execute
            }
            val result =
                StartupStorageRun.reportedResult(card, KioskConfig.STARTUP_CARD_CHECK_ENABLED)
            runOnUiThread {
                if (generation == cardCheckGeneration && !isFinishing && !isDestroyed) {
                    cardCheckResult = result
                    // A slow check may complete after its timeout warning was shown.
                    // Re-enter the launch decision using the actual result.
                    if (cardWarningVisible) scheduleLaunch()
                }
            }
        }
    }

    // Runs on the card check executor. Boot can start Home before Android mounts portable storage,
    // so with STARTUP_CARD_WAIT_ENABLED a card that is still mounting or refusing writes is waited
    // for, not reported at once. Without it the card is neither waited for nor checked.
    private fun prepareStartupStorage(run: StartupStorageRun): CompyCardCheckResult? =
        run.prepare(
            gate = recoveryGate,
            recoverInternal = {
                recoverPendingProjectRestores(BackupSourceKind.INTERNAL) { CompyStorage.internalStorage(this) }
            },
            awaitMount = { state ->
                CardMountWait.await(
                    timeoutMs = startupWindows.mountWaitMs(KioskConfig.CARD_MOUNT_TIMEOUT_MS),
                    pollMs = KioskConfig.CARD_MOUNT_POLL_MS,
                    state = state,
                    now = SystemClock::elapsedRealtime,
                    pause = Thread::sleep,
                    isCurrent = run.isCurrent,
                )
            },
            mountState = { runCatching { CompyCardCheck.removableVolume(this)?.state }.getOrNull() },
            retry = { check ->
                val checked = CardCheckRetry.run(
                    windowMs = startupWindows.retryWindowMs(KioskConfig.CARD_CHECK_RETRY_WINDOW_MS),
                    intervalMs = KioskConfig.CARD_CHECK_RETRY_INTERVAL_MS,
                    check = check,
                    now = SystemClock::elapsedRealtime,
                    pause = { durationMs ->
                        StartupStorageRun.pauseWhileCurrent(
                            durationMs = durationMs,
                            sliceMs = KioskConfig.CARD_MOUNT_POLL_MS,
                            isCurrent = run.isCurrent,
                            now = SystemClock::elapsedRealtime,
                            sleep = Thread::sleep,
                        )
                    },
                    isCurrent = run.isCurrent,
                    onRetry = { failed ->
                        Log.i(TAG, "SD card check ${failed.condition}: ${failed.detail}; checking again")
                    },
                )
                    // The device's record of a failed initialization is applied after the retries,
                    // which can never clear it.
                    ?.let { CompyCardCheck.withInitializationRecord(this, it) }
                // Logged here, before recovery, which can take long and which later runs skip.
                if (checked != null && !checked.healthy && checked.detail != null) {
                    Log.w(TAG, "SD card check ${checked.condition}: ${checked.detail}")
                }
                checked
            },
            inspect = {
                try {
                    CompyCardCheck.inspectCard(this)
                } catch (error: Exception) {
                    CompyCardCheckResult(
                        condition = CompyCardCondition.UNREADABLE,
                        detail = error.message,
                    )
                }
            },
            recoverCard = { card ->
                // A card whose check failed may still accept recovery: the failure may be a record
                // kept on this device, or the card may have become writable after the last attempt.
                if (StartupStorageRun.cardNeedsRecovery(card)) {
                    recoverPendingProjectRestores(BackupSourceKind.CARD) { CompyStorage.removableStorage(this) }
                }
            },
            waitForCard = KioskConfig.STARTUP_CARD_WAIT_ENABLED,
        )

    // Returns false to be asked again later.
    private fun onCardStepTimeout(generation: Int, step: String): Boolean {
        if (generation != cardCheckGeneration || cardCheckResult != null || isFinishing || isDestroyed) {
            return true
        }
        // Waiting behind a recovery is not a hang: the IDE waits for recovery anyway, and a fallback
        // now would let the launch claim end this run before its card recovery. Ask again later.
        if (recoveryGate.runningForMs() != null) return false
        val detail = "$step timed out after ${KioskConfig.CARD_CHECK_TIMEOUT_MS} ms"
        Log.w(TAG, detail)
        cardCheckResult =
            StartupStorageRun.reportedResult(
                CompyCardCheckResult(condition = CompyCardCondition.UNREADABLE, detail = detail),
                KioskConfig.STARTUP_CARD_CHECK_ENABLED,
            )
        return true
    }

    private fun performLaunch() {
        if (KioskState.isMaintenanceActive(this)) {
            openMaintenanceMode()
            return
        }

        val cardResult = cardCheckResult
        if (cardResult == null || recoveryGate.runningForMs() != null) {
            waitForStartupStorage()
            return
        }
        if (cardResult.healthy) {
            cardWarningVisible = false
            activeCardWarning = null
            KioskState.clearCardWarningAcknowledgement(this)
        } else {
            if (!KioskState.isCardWarningAcknowledged(this, cardResult)) {
                showCardWarning(cardResult)
                return
            }
        }

        // Restore recovery changes project folders the IDE lets a child change. The IDE starts only
        // while none runs, and the claim ends the current run so none of it starts afterwards.
        if (!recoveryGate.claimLaunch { cardCheckGeneration++ }) {
            waitForStartupStorage()
            return
        }
        hideRestoreWait()
        lastLaunchAttemptTime = SystemClock.elapsedRealtime()
        val ownerLaunchStarted =
            LockTaskController.armAndLaunchTarget(this) { message ->
                Log.e(TAG, message)
                KioskState.enableMaintenance(this, KioskConfig.MAINTENANCE_DURATION_MS)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                openMaintenanceMode()
            }
        if (ownerLaunchStarted) {
            return
        }

        val targetPackage = KioskConfig.TARGET_PACKAGE
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)

        if (launchIntent == null) {
            openMaintenanceMode()
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )

        try {
            startActivity(launchIntent)
        } catch (_: Exception) {
            openMaintenanceMode()
        }
    }

    // Recovery has no hang timeout, because the IDE must not start while it runs, and the card wait
    // can take up to a minute. Once either has taken longer than the hang timeout, the screen says
    // what is happening instead of staying black.
    private fun waitForStartupStorage() {
        val waitedMs = SystemClock.elapsedRealtime() - cardCheckStartedAt
        if (recoveryOverran() ||
            (cardCheckResult == null && waitedMs >= KioskConfig.CARD_CHECK_TIMEOUT_MS)
        ) {
            showRestoreWait()
        } else {
            hideRestoreWait()
        }
        handler.removeCallbacks(launchRunnable)
        handler.postDelayed(launchRunnable, KioskConfig.CARD_CHECK_POLL_MS)
    }

    // Every call on a resumed launcher arms the launch polling, so the result after recovery or after
    // the card wait is acted on even when a card warning, which stops the polling, was being prepared
    // or shown.
    private fun showRestoreWait() {
        // A paused launcher polls again from its next resume; polling now could start Maintenance
        // twice or the IDE from the background.
        if (launcherResumed) {
            handler.removeCallbacks(launchRunnable)
            handler.postDelayed(launchRunnable, KioskConfig.CARD_CHECK_POLL_MS)
        }
        if (restoreWaitView != null) return
        cardWarningVisible = false
        activeCardWarning = null
        root.removeAllViews()
        val message =
            TextView(this).apply {
                text = getString(R.string.restore_wait_message)
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(72, 48, 72, 48)
            }
        root.addView(
            message,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        restoreWaitView = message
    }

    private fun hideRestoreWait() {
        restoreWaitView?.let(root::removeView)
        restoreWaitView = null
    }

    private fun showCardWarning(
        result: CompyCardCheckResult,
        lockPrepared: Boolean = false,
    ) {
        // A warning drawn late, for example by a delayed LockTask callback, must not hide the
        // message for a recovery that is taking long; the result after recovery decides again.
        if (recoveryOverran()) {
            showRestoreWait()
            return
        }
        handler.removeCallbacks(launchRunnable)
        val launcherUnlocked =
            LockTaskController.lockTaskModeState(this) ==
                android.app.ActivityManager.LOCK_TASK_MODE_NONE
        if (!lockPrepared && launcherUnlocked) {
            val handled =
                LockTaskController.armLauncherActivity(
                    activity = this,
                    onReady = {
                        if (!isFinishing && !isDestroyed) {
                            showCardWarning(result, lockPrepared = true)
                        }
                    },
                    onFailure = { message ->
                        Log.e(TAG, message)
                        KioskState.enableMaintenance(this, KioskConfig.MAINTENANCE_DURATION_MS)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        openMaintenanceMode(
                            cardInitializationRequested = result.repairable,
                        )
                    },
                )
            if (handled) return
        }
        cardWarningVisible = true
        activeCardWarning = result
        root.removeAllViews()
        restoreWaitView = null
        val panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(72, 48, 72, 48)
                addView(
                    TextView(this@MainActivity).apply {
                        text = getString(R.string.card_warning_title)
                        textSize = 30f
                        gravity = Gravity.CENTER
                        setTextColor(Color.rgb(255, 184, 48))
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = cardWarningMessage(result)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setPadding(0, 24, 0, 28)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = cardWarningQuestion(result)
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setTextColor(Color.LTGRAY)
                        setPadding(0, 0, 0, 12)
                    },
                )
                addView(
                    confirmationButton(R.string.card_warning_continue) {
                        continueAfterCardWarning(result)
                    },
                )
                addView(
                    confirmationButton(R.string.card_warning_open_maintenance) {
                        openMaintenanceAfterCardWarning(result)
                    },
                )
                post { requestFocus() }
            }
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun confirmationButton(labelRes: Int, action: () -> Unit): Button {
        var primaryMouseDown = false
        return Button(this).apply {
            text = getString(labelRes)
            isAllCaps = false
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { action() }
            setOnTouchListener { view, event ->
                if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        primaryMouseDown =
                            event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val shouldClick = primaryMouseDown
                        primaryMouseDown = false
                        if (shouldClick) view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        primaryMouseDown = false
                        true
                    }
                    else -> true
                }
            }
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
        }
    }

    private fun continueAfterCardWarning(result: CompyCardCheckResult) {
        KioskState.acknowledgeCardWarning(this, result)
        cardWarningVisible = false
        activeCardWarning = null
        root.removeAllViews()
        restartTargetAfterCardChoice(result)
    }

    private fun openMaintenanceAfterCardWarning(result: CompyCardCheckResult) {
        cardWarningVisible = false
        activeCardWarning = null
        KioskState.enableMaintenance(this, KioskConfig.MAINTENANCE_DURATION_MS)
        openMaintenanceMode(cardInitializationRequested = result.repairable)
    }

    private fun restartTargetAfterCardChoice(result: CompyCardCheckResult) {
        if (!LockTaskController.isDeviceOwner(this)) {
            scheduleLaunch()
            return
        }
        LockTaskController.stopTargetForRestart(this) { success, message ->
            if (success) {
                scheduleLaunch()
            } else {
                KioskState.clearCardWarningAcknowledgement(this)
                showCardWarning(result, lockPrepared = true)
                Toast.makeText(
                    this,
                    message ?: getString(R.string.card_warning_restart_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun cardWarningMessage(result: CompyCardCheckResult): String {
        return when (result.condition) {
            CompyCardCondition.MISSING -> getString(R.string.card_warning_missing)
            CompyCardCondition.UNREADABLE ->
                getString(R.string.card_warning_unreadable)
            CompyCardCondition.UNINITIALIZED ->
                getString(R.string.card_warning_uninitialized)
            CompyCardCondition.IDENTITY_INVALID ->
                getString(R.string.card_warning_identity)
            CompyCardCondition.UNWRITABLE -> getString(R.string.card_warning_unwritable)
            CompyCardCondition.HEALTHY -> ""
        }
    }

    private fun cardWarningQuestion(result: CompyCardCheckResult): String {
        return when (result.condition) {
            CompyCardCondition.MISSING,
            CompyCardCondition.UNREADABLE,
            CompyCardCondition.UNWRITABLE,
            -> getString(R.string.card_warning_question_internal)
            CompyCardCondition.UNINITIALIZED,
            CompyCardCondition.IDENTITY_INVALID,
            -> getString(R.string.card_warning_question_limited_card)
            CompyCardCondition.HEALTHY -> ""
        }
    }

    private fun Intent?.isHomeIntent(): Boolean {
        return this?.action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_HOME)
    }

    companion object {
        private const val TAG = "CompyLauncher"

        // Process-wide, like the gate: every launcher entry, such as a Home press, starts a new run.
        private val startupWindows = StartupWindows(SystemClock::elapsedRealtime)

        // Process-wide: a recreated activity must see a recovery its predecessor started.
        private val recoveryGate =
            RecoveryGate(
                now = SystemClock::elapsedRealtime,
                pause = Thread::sleep,
                pollMs = KioskConfig.CARD_CHECK_POLL_MS,
            )
    }
}
