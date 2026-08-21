/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "LockTask entering for $pkg")
        LockTaskController.onLockTaskStateChanged()
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "LockTask exiting")
        LockTaskController.onLockTaskStateChanged()
    }

    companion object {
        private const val TAG = "CompyLockTask"
    }
}
