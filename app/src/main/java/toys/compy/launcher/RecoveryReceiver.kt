/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/** ADB-triggerable recovery surface isolated from the launcher UI process. */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            val result =
                when (intent.action) {
                    null, ACTION_RECOVER -> LockTaskController.recoverDeviceOwner(appContext)
                    ACTION_CHECK -> LockTaskController.RecoveryResult(true, "Recovery receiver ready")
                    ACTION_ENABLE_KIOSK -> LockTaskController.enableAfterRecovery(appContext)
                    else ->
                        LockTaskController.RecoveryResult(
                            false,
                            "Unsupported recovery action: ${intent.action}",
                        )
                }

            if (!result.success) {
                Log.e(TAG, result.message)
            }
            pendingResult.resultCode =
                if (result.success) Activity.RESULT_OK else Activity.RESULT_CANCELED
            pendingResult.resultData = result.message
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_RECOVER = "toys.compy.launcher.action.RECOVER"
        const val ACTION_CHECK = "toys.compy.launcher.action.CHECK_RECOVERY"
        const val ACTION_ENABLE_KIOSK = "toys.compy.launcher.action.ENABLE_KIOSK"

        private const val TAG = "CompyRecovery"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
