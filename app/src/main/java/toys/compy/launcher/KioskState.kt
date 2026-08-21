/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object KioskState {
    private const val PREFS_NAME = "kiosk_prefs"
    private const val KEY_MAINTENANCE_UNTIL = "maintenance_until"
    private const val KEY_HOME_PRESS_HISTORY = "home_press_history"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getMaintenanceUntil(context: Context): Long {
        return getPrefs(context).getLong(KEY_MAINTENANCE_UNTIL, 0)
    }

    fun isMaintenanceActive(context: Context): Boolean {
        val until = getPrefs(context).getLong(KEY_MAINTENANCE_UNTIL, 0)
        return System.currentTimeMillis() < until
    }

    fun enableMaintenance(context: Context, durationMs: Long) {
        getPrefs(context).edit {
            putLong(KEY_MAINTENANCE_UNTIL, System.currentTimeMillis() + durationMs)
        }
    }

    fun disableMaintenance(context: Context) {
        getPrefs(context).edit {
            remove(KEY_MAINTENANCE_UNTIL)
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
        val now = System.currentTimeMillis()
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
