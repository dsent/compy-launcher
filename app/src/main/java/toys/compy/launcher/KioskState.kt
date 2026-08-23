/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit

object KioskState {
    private const val PREFS_NAME = "kiosk_prefs"
    private const val KEY_MAINTENANCE_DEADLINE = "maintenance_deadline_elapsed"
    private const val KEY_HOME_PRESS_HISTORY = "home_press_history"
    private const val KEY_BOOT_COUNT = "monotonic_boot_count"

    private fun getPrefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (prefs.getInt(KEY_BOOT_COUNT, Int.MIN_VALUE) != bootCount) {
            prefs.edit {
                remove(KEY_MAINTENANCE_DEADLINE)
                remove(KEY_HOME_PRESS_HISTORY)
                putInt(KEY_BOOT_COUNT, bootCount)
            }
        }
        return prefs
    }

    fun getMaintenanceDeadline(context: Context): Long {
        return getPrefs(context).getLong(KEY_MAINTENANCE_DEADLINE, 0)
    }

    fun isMaintenanceActive(context: Context): Boolean {
        val deadline = getPrefs(context).getLong(KEY_MAINTENANCE_DEADLINE, 0)
        return SystemClock.elapsedRealtime() < deadline
    }

    fun enableMaintenance(context: Context, durationMs: Long) {
        getPrefs(context).edit {
            putLong(KEY_MAINTENANCE_DEADLINE, SystemClock.elapsedRealtime() + durationMs)
        }
    }

    fun disableMaintenance(context: Context) {
        getPrefs(context).edit {
            remove(KEY_MAINTENANCE_DEADLINE)
        }
    }

    fun toggleMaintenance(context: Context) {
        if (isMaintenanceActive(context)) {
            disableMaintenance(context)
        } else {
            enableMaintenance(context, KioskConfig.MAINTENANCE_DURATION_MS)
        }
    }

    fun recordHomeResumeAndCheckSecret(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        val prefs = getPrefs(context)
        val historyStr = prefs.getString(KEY_HOME_PRESS_HISTORY, "") ?: ""

        val history = historyStr.split(",")
            .asSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull() }
            .toList()
        val update =
            updateHomePressHistory(
                history = history,
                now = now,
                windowMs = KioskConfig.HOME_SECRET_WINDOW_MS,
                triggerCount = KioskConfig.HOME_SECRET_PRESS_COUNT,
            )

        if (update.triggered) {
            enableMaintenance(context, KioskConfig.MAINTENANCE_DURATION_MS)
            prefs.edit { remove(KEY_HOME_PRESS_HISTORY) }
            return true
        }

        prefs.edit {
            putString(KEY_HOME_PRESS_HISTORY, update.retainedHistory.joinToString(","))
        }
        return false
    }
}
