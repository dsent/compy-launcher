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
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunchAttemptTime = 0L
    private var backoffDelay = 0L
    private var maintenanceOpening = false
    private val homeEntryGate = HomeEntryGate()

    private val launchRunnable = Runnable {
        performLaunch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal blank view
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
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

    private fun handleLauncherEntry(dispatch: HomeEntryDispatch) {
        val secretTriggered =
            dispatch.countHomePress && KioskState.recordHomeResumeAndCheckSecret(this)
        if (secretTriggered || KioskState.isMaintenanceActive(this)) {
            openMaintenanceMode()
        } else {
            maintenanceOpening = false
            scheduleLaunch()
        }
    }

    private fun openMaintenanceMode() {
        if (maintenanceOpening) {
            return
        }
        maintenanceOpening = true
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
                Log.e(TAG, message)
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun scheduleLaunch() {
        handler.removeCallbacks(launchRunnable)

        val now = System.currentTimeMillis()
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

    private fun performLaunch() {
        if (KioskState.isMaintenanceActive(this)) {
            openMaintenanceMode()
            return
        }

        lastLaunchAttemptTime = System.currentTimeMillis()
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

    private fun Intent?.isHomeIntent(): Boolean {
        return this?.action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_HOME)
    }

    companion object {
        private const val TAG = "CompyLauncher"
    }
}
