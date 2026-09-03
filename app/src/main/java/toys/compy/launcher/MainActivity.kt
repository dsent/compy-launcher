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
import android.view.KeyEvent
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
    private var cardCheckGeneration = 0
    private var cardCheckResult: CompyCardCheckResult? = null
    private var activeCardWarning: CompyCardCheckResult? = null
    private var cardWarningVisible = false

    private val launchRunnable = Runnable {
        performLaunch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recoverPendingProjectRestores()

        // Minimal blank view
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
    }

    override fun onDestroy() {
        cardCheckExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun recoverPendingProjectRestores() {
        try {
            val card = CompyStorage.removableStorage(this)
            CompyBackupStore.recoverPendingRestoresOnStartup(
                BackupStorageEndpoint(
                    kind = BackupSourceKind.CARD,
                    id = card.id,
                    compyDirectory = card.compyDirectory,
                ),
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not reconcile pending project restores", error)
        }
    }

    override fun onResume() {
        super.onResume()
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

    private fun beginCardCheck() {
        val generation = ++cardCheckGeneration
        cardCheckResult = null
        cardCheckExecutor.execute {
            val result =
                try {
                    CompyCardCheck.inspect(this)
                } catch (error: Exception) {
                    CompyCardCheckResult(
                        condition = CompyCardCondition.UNREADABLE,
                        detail = error.message,
                    )
                }
            runOnUiThread {
                if (generation == cardCheckGeneration && !isFinishing && !isDestroyed) {
                    cardCheckResult = result
                }
            }
        }
        handler.postDelayed(
            {
                if (generation == cardCheckGeneration && cardCheckResult == null) {
                    cardCheckResult =
                        CompyCardCheckResult(
                            condition = CompyCardCondition.UNREADABLE,
                            detail = getString(R.string.card_warning_check_timeout),
                        )
                }
            },
            KioskConfig.CARD_CHECK_TIMEOUT_MS,
        )
    }

    private fun performLaunch() {
        if (KioskState.isMaintenanceActive(this)) {
            openMaintenanceMode()
            return
        }

        val cardResult = cardCheckResult
        if (cardResult == null) {
            handler.postDelayed(launchRunnable, KioskConfig.CARD_CHECK_POLL_MS)
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

    private fun showCardWarning(
        result: CompyCardCheckResult,
        lockPrepared: Boolean = false,
    ) {
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
                        text = getString(R.string.card_warning_question)
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
        return Button(this).apply {
            text = getString(labelRes)
            isAllCaps = false
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { action() }
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
                result.detail ?: getString(R.string.card_warning_unreadable)
            CompyCardCondition.UNINITIALIZED ->
                getString(R.string.card_warning_uninitialized)
            CompyCardCondition.IDENTITY_INVALID ->
                getString(R.string.card_warning_identity)
            CompyCardCondition.UNWRITABLE -> getString(R.string.card_warning_unwritable)
            CompyCardCondition.HEALTHY -> ""
        }
    }

    private fun Intent?.isHomeIntent(): Boolean {
        return this?.action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_HOME)
    }

    companion object {
        private const val TAG = "CompyLauncher"
    }
}
