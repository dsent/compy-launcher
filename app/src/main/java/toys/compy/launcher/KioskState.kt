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

data class PendingCardInitialization(
    val cardId: String,
    val seededProjects: Int,
    val copiedFiles: Int,
    val reusedFiles: Int,
    val resumeAfterSuccess: Boolean,
)

object KioskState {
    private const val PREFS_NAME = "kiosk_prefs"
    private const val KEY_MAINTENANCE_DEADLINE = "maintenance_deadline_elapsed"
    private const val KEY_HOME_PRESS_HISTORY = "home_press_history"
    private const val KEY_BOOT_COUNT = "monotonic_boot_count"
    private const val KEY_CARD_WARNING_ACKNOWLEDGEMENT =
        "card_warning_acknowledgement"
    private const val KEY_CARD_INITIALIZATION_REQUESTED =
        "card_initialization_requested"
    private const val KEY_CARD_INITIALIZATION_PENDING_ID =
        "card_initialization_pending_id"
    private const val KEY_CARD_INITIALIZATION_PENDING_PROJECTS =
        "card_initialization_pending_projects"
    private const val KEY_CARD_INITIALIZATION_PENDING_COPIED =
        "card_initialization_pending_copied"
    private const val KEY_CARD_INITIALIZATION_PENDING_REUSED =
        "card_initialization_pending_reused"
    private const val KEY_CARD_INITIALIZATION_PENDING_RESUME =
        "card_initialization_pending_resume"
    private const val KEY_CARD_INITIALIZATION_FAILED_PREFIX =
        "card_initialization_failed_"

    private fun getPrefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (prefs.getInt(KEY_BOOT_COUNT, Int.MIN_VALUE) != bootCount) {
            prefs.edit {
                remove(KEY_MAINTENANCE_DEADLINE)
                remove(KEY_HOME_PRESS_HISTORY)
                remove(KEY_CARD_WARNING_ACKNOWLEDGEMENT)
                remove(KEY_CARD_INITIALIZATION_REQUESTED)
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

    fun isCardWarningAcknowledged(
        context: Context,
        result: CompyCardCheckResult,
    ): Boolean {
        return getPrefs(context).getString(KEY_CARD_WARNING_ACKNOWLEDGEMENT, null) ==
            cardWarningFingerprint(result)
    }

    fun acknowledgeCardWarning(context: Context, result: CompyCardCheckResult) {
        getPrefs(context).edit {
            putString(
                KEY_CARD_WARNING_ACKNOWLEDGEMENT,
                cardWarningFingerprint(result),
            )
        }
    }

    fun clearCardWarningAcknowledgement(context: Context) {
        getPrefs(context).edit { remove(KEY_CARD_WARNING_ACKNOWLEDGEMENT) }
    }

    fun requestCardInitialization(context: Context) {
        getPrefs(context).edit(commit = true) {
            putBoolean(KEY_CARD_INITIALIZATION_REQUESTED, true)
        }
    }

    fun clearCardInitializationRequest(context: Context) {
        getPrefs(context).edit(commit = true) {
            remove(KEY_CARD_INITIALIZATION_REQUESTED)
        }
    }

    fun consumeCardInitializationRequest(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_CARD_INITIALIZATION_REQUESTED, false)) return false
        prefs.edit(commit = true) { remove(KEY_CARD_INITIALIZATION_REQUESTED) }
        return true
    }

    fun recordPendingCardInitialization(
        context: Context,
        cardId: String,
        result: CompyCardInitializationResult,
        resumeAfterSuccess: Boolean,
    ) {
        getPrefs(context).edit(commit = true) {
            putString(KEY_CARD_INITIALIZATION_PENDING_ID, cardId)
            putInt(KEY_CARD_INITIALIZATION_PENDING_PROJECTS, result.seededProjects)
            putInt(KEY_CARD_INITIALIZATION_PENDING_COPIED, result.copiedFiles)
            putInt(KEY_CARD_INITIALIZATION_PENDING_REUSED, result.reusedFiles)
            putBoolean(KEY_CARD_INITIALIZATION_PENDING_RESUME, resumeAfterSuccess)
        }
    }

    fun pendingCardInitialization(context: Context): PendingCardInitialization? {
        val prefs = getPrefs(context)
        val cardId = prefs.getString(KEY_CARD_INITIALIZATION_PENDING_ID, null) ?: return null
        return PendingCardInitialization(
            cardId = cardId,
            seededProjects = prefs.getInt(KEY_CARD_INITIALIZATION_PENDING_PROJECTS, 0),
            copiedFiles = prefs.getInt(KEY_CARD_INITIALIZATION_PENDING_COPIED, 0),
            reusedFiles = prefs.getInt(KEY_CARD_INITIALIZATION_PENDING_REUSED, 0),
            resumeAfterSuccess = prefs.getBoolean(KEY_CARD_INITIALIZATION_PENDING_RESUME, false),
        )
    }

    fun clearPendingCardInitialization(context: Context) {
        getPrefs(context).edit(commit = true) {
            remove(KEY_CARD_INITIALIZATION_PENDING_ID)
            remove(KEY_CARD_INITIALIZATION_PENDING_PROJECTS)
            remove(KEY_CARD_INITIALIZATION_PENDING_COPIED)
            remove(KEY_CARD_INITIALIZATION_PENDING_REUSED)
            remove(KEY_CARD_INITIALIZATION_PENDING_RESUME)
        }
    }

    fun recordCardInitializationFailure(
        context: Context,
        cardId: String,
        detail: String,
    ) {
        getPrefs(context).edit(commit = true) {
            putString(cardInitializationFailureKey(cardId), detail)
        }
    }

    fun cardInitializationFailure(context: Context, cardId: String): String? {
        return getPrefs(context).getString(cardInitializationFailureKey(cardId), null)
    }

    fun clearCardInitializationFailure(context: Context, cardId: String) {
        getPrefs(context).edit(commit = true) {
            remove(cardInitializationFailureKey(cardId))
        }
    }

    private fun cardInitializationFailureKey(cardId: String): String =
        KEY_CARD_INITIALIZATION_FAILED_PREFIX + cardId

    internal fun cardWarningFingerprint(result: CompyCardCheckResult): String =
        "${result.condition.name}|${result.cardId.orEmpty()}"

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
