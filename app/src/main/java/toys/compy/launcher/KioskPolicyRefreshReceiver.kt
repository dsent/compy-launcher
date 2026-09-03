/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/** Restores owner policy that an OEM may drop across boot or package replacement. */
class KioskPolicyRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            val result = LockTaskController.refreshKeyguardPolicy(appContext)
            if (!result.success) Log.e(TAG, result.message)
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "CompyPolicyRefresh"
        private val SUPPORTED_ACTIONS =
            setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        private val executor = Executors.newSingleThreadExecutor()
    }
}
