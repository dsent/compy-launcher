/*
 * Copyright (c) 2026 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */
package toys.compy.launcher

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File

/** The image marker keeps the extra action off ordinary Android installations. */
object RootedSystemBars {
    fun isAvailable(): Boolean = File("/system/etc/compy-rooted-image").isFile

    fun openNotifications(context: Context) {
        check(isAvailable() && KioskState.isMaintenanceActive(context))
        check(LockTaskController.lockTaskModeState(context) == ActivityManager.LOCK_TASK_MODE_NONE)
        try {
            // Android 13 permits this operation with the normal EXPAND_STATUS_BAR
            // permission. Returning to LockTask restores its notification restrictions.
            val manager = checkNotNull(context.getSystemService("statusbar"))
            manager.javaClass.getMethod("expandNotificationsPanel").invoke(manager)
        } catch (error: Exception) {
            Log.e("RootedSystemBars", "Could not open notification panel", error)
            throw IllegalStateException("Compy could not open notifications.", error)
        }
    }
}
