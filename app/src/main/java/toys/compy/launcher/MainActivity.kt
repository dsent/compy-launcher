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
import android.widget.FrameLayout

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunchAttemptTime = 0L
    private var backoffDelay = 0L

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

        val secretTriggered = KioskState.recordHomeResumeAndCheckSecret(this)

        if (secretTriggered || KioskState.isMaintenanceActive(this)) {
            openMaintenanceMode()
        } else {
            scheduleLaunch()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(launchRunnable)
    }

    private fun openMaintenanceMode() {
        val intent = Intent(this, KioskControlActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
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

        lastLaunchAttemptTime = System.currentTimeMillis()
        try {
            startActivity(launchIntent)
        } catch (_: Exception) {
            openMaintenanceMode()
        }
    }
}
