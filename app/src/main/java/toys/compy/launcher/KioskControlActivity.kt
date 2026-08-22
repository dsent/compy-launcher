/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KioskControlActivity : Activity() {
    private data class MaintenanceAction(
        val labelRes: Int,
        val invoke: () -> Unit,
    )

    private data class MaintenanceGroup(
        val titleRes: Int,
        val actions: List<MaintenanceAction>,
    )

    private val actionButtons = mutableListOf<Button>()
    private val expiryHandler = Handler(Looper.getMainLooper())
    private val expiryRunnable = Runnable { handleMaintenanceExpiry() }
    private var operationTitleView: TextView? = null
    private var operationMessageView: TextView? = null
    private var exitingMaintenance = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (LockTaskController.lockTaskModeState(this) != ActivityManager.LOCK_TASK_MODE_NONE) {
            showUnlockingGate()
            KioskState.enableMaintenance(this, KioskConfig.MAINTENANCE_DURATION_MS)
            val appContext = applicationContext
            LockTaskController.disarm(
                appContext,
                onReady = {
                    finish()
                    val maintenanceIntent = Intent(appContext, KioskControlActivity::class.java)
                    maintenanceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    appContext.startActivity(maintenanceIntent)
                },
                onFailure = { message ->
                    if (!isFinishing && !isDestroyed) {
                        showUnlockingFailure(message)
                    }
                },
            )
            return
        }

        buildControls()
    }

    override fun onResume() {
        super.onResume()
        if (LockTaskController.lockTaskModeState(this) != ActivityManager.LOCK_TASK_MODE_NONE) {
            return
        }
        if (!KioskState.isMaintenanceActive(this)) {
            exitMaintenance()
            return
        }
        scheduleMaintenanceExpiry()
    }

    override fun onPause() {
        expiryHandler.removeCallbacks(expiryRunnable)
        super.onPause()
    }

    private fun buildControls() {
        actionButtons.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            weightSum = 2f
        }

        val actionScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }
        val actionColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 24, 32)
        }
        actionScroll.addView(actionColumn)

        actionColumn.addView(
            TextView(this).apply {
                text = getString(R.string.maintenance_title)
                textSize = 26f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 8)
            },
        )

        val statusView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }
        updateStatus(statusView)
        actionColumn.addView(statusView)

        var firstButton: Button? = null
        maintenanceGroups().forEach { group ->
            addGroupHeader(actionColumn, group.titleRes)
            group.actions.forEach { action ->
                val button = addButton(actionColumn, action.labelRes, action.invoke)
                if (firstButton == null) {
                    firstButton = button
                }
            }
        }

        val statusScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
        }
        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor("#171717".toColorInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        statusPanel.addView(
            TextView(this).apply {
                text = getString(R.string.maintenance_status_title)
                textSize = 18f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, 20)
            },
        )
        operationTitleView =
            TextView(this).apply {
                text = getString(R.string.maintenance_ready_title)
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 12)
            }
        statusPanel.addView(operationTitleView)
        operationMessageView =
            TextView(this).apply {
                text = getString(R.string.maintenance_ready_message)
                textSize = 17f
                setTextColor("#D0D0D0".toColorInt())
        }
        statusPanel.addView(operationMessageView)
        statusScroll.addView(statusPanel)

        root.addView(actionScroll)
        root.addView(statusScroll)
        setContentView(root)
        configureActionFocus()
        firstButton?.requestFocus()
    }

    private fun configureActionFocus() {
        actionButtons.forEach { button ->
            button.id = View.generateViewId()
            button.isFocusableInTouchMode = true
        }
        actionButtons.forEachIndexed { index, button ->
            val previous = actionButtons.getOrNull(index - 1)
            val next = actionButtons.getOrNull(index + 1)
            previous?.let { button.nextFocusUpId = it.id }
            next?.let {
                button.nextFocusDownId = it.id
                button.nextFocusForwardId = it.id
            }
        }
    }

    private fun maintenanceGroups(): List<MaintenanceGroup> {
        val groups =
            mutableListOf(
                MaintenanceGroup(
                    R.string.maintenance_group_compy,
                    listOf(
                        MaintenanceAction(R.string.btn_exit_maintenance, ::exitMaintenance),
                    ),
                ),
                MaintenanceGroup(
                    R.string.maintenance_group_device,
                    listOf(
                        MaintenanceAction(R.string.btn_open_settings, ::openAndroidSettings),
                        MaintenanceAction(R.string.btn_open_files, ::openFiles),
                    ),
                ),
            )
        if (LockTaskController.isDeviceOwner(this)) {
            groups +=
                MaintenanceGroup(
                    R.string.maintenance_group_recovery,
                    listOf(
                        MaintenanceAction(R.string.btn_release_device_owner, ::confirmOwnershipRelease),
                    ),
                )
        }
        return groups
    }

    private fun exitMaintenance() {
        if (exitingMaintenance) return
        exitingMaintenance = true
        expiryHandler.removeCallbacks(expiryRunnable)
        KioskState.disableMaintenance(this)
        val launcherIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(launcherIntent)
        finish()
    }

    private fun scheduleMaintenanceExpiry() {
        expiryHandler.removeCallbacks(expiryRunnable)
        val remainingMs = KioskState.getMaintenanceUntil(this) - System.currentTimeMillis()
        if (remainingMs <= 0) {
            expiryHandler.post(expiryRunnable)
        } else {
            expiryHandler.postDelayed(expiryRunnable, remainingMs)
        }
    }

    private fun handleMaintenanceExpiry() {
        if (KioskState.isMaintenanceActive(this)) {
            scheduleMaintenanceExpiry()
        } else {
            exitMaintenance()
        }
    }

    private fun openAndroidSettings() {
        showOperation(
            getString(R.string.maintenance_opening_settings_title),
            getString(R.string.maintenance_opening_settings_message),
        )
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (error: RuntimeException) {
            showOperationFailure(error.message ?: getString(R.string.maintenance_open_failed))
        }
    }

    private fun openFiles() {
        showOperation(
            getString(R.string.maintenance_opening_files_title),
            getString(R.string.maintenance_opening_files_message),
        )
        val intents =
            listOf(
                // Go and AOSP DocumentsUI use different package names on production device variants.
                Intent().setComponent(
                    ComponentName(
                        "com.google.android.go.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    ),
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    ),
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.LauncherActivity",
                    ),
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.android.providers.downloads.ui",
                        "com.android.providers.downloads.ui.DownloadList",
                    ),
                ),
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES)
                } else {
                    null
                },
                packageManager.getLaunchIntentForPackage("com.google.android.apps.nbu.files"),
                packageManager.getLaunchIntentForPackage("com.softwinner.awmanager"),
            )

        for (intent in intents) {
            if (intent == null) continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                continue
            }
        }

        try {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallback)
        } catch (_: Exception) {
            showOperationFailure(getString(R.string.maintenance_no_file_manager))
        }
    }

    private fun addGroupHeader(parent: ViewGroup, titleRes: Int) {
        parent.addView(
            TextView(this).apply {
                text = getString(titleRes)
                textSize = 16f
                setTextColor(Color.GRAY)
                setPadding(0, 12, 0, 8)
            },
        )
    }

    private fun addButton(parent: ViewGroup, labelRes: Int, onClick: () -> Unit): Button {
        val button =
            Button(this).apply {
                text = getString(labelRes)
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(32, 16, 32, 16)
                setOnClickListener { onClick() }
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(0, 0, 0, 10)
                    }
            }
        actionButtons += button
        parent.addView(button)
        return button
    }

    private fun showUnlockingGate() {
        setContentView(
            TextView(this).apply {
                text = getString(R.string.maintenance_unlocking)
                textSize = 24f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                gravity = Gravity.CENTER
            },
        )
    }

    private fun showUnlockingFailure(message: String) {
        setContentView(
            TextView(this).apply {
                text = getString(R.string.maintenance_unlock_failed, message)
                textSize = 18f
                setTextColor(Color.RED)
                setBackgroundColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(32, 32, 32, 32)
            },
        )
    }

    private fun confirmOwnershipRelease() {
        confirmAction(
            titleRes = R.string.release_device_owner_title,
            messageRes = R.string.release_device_owner_message,
            confirmRes = R.string.release_device_owner_confirm,
        ) {
            showOperation(
                getString(R.string.release_device_owner_working),
                getString(R.string.release_device_owner_wait),
                busy = true,
            )
            LockTaskController.releaseDeviceOwner(this) { released, message ->
                if (isFinishing || isDestroyed) {
                    return@releaseDeviceOwner
                }
                if (released) {
                    buildControls()
                    showOperation(
                        getString(R.string.release_device_owner_success),
                        getString(R.string.release_device_owner_success_message),
                    )
                } else {
                    showOperationFailure(
                        getString(
                            R.string.release_device_owner_failed,
                            message ?: getString(R.string.maintenance_unknown_error),
                        ),
                    )
                }
            }
        }
    }

    private fun confirmAction(
        titleRes: Int,
        messageRes: Int,
        confirmRes: Int,
        onConfirmed: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(confirmRes) { _, _ -> onConfirmed() }
            .show()
    }

    private fun showOperation(
        title: String,
        message: String,
        busy: Boolean = false,
    ) {
        operationTitleView?.apply {
            text = title
            setTextColor(Color.WHITE)
        }
        operationMessageView?.apply {
            text = message
            setTextColor("#D0D0D0".toColorInt())
        }
        actionButtons.forEach { it.isEnabled = !busy }
    }

    private fun showOperationFailure(message: String) {
        operationTitleView?.apply {
            text = getString(R.string.maintenance_action_failed)
            setTextColor("#FF6B6B".toColorInt())
        }
        operationMessageView?.apply {
            text = message
            setTextColor("#FFB0B0".toColorInt())
        }
        actionButtons.forEach { it.isEnabled = true }
    }

    private fun updateStatus(view: TextView) {
        val until = KioskState.getMaintenanceUntil(this)
        if (System.currentTimeMillis() < until) {
            val date = Date(until)
            val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            view.text = getString(R.string.maintenance_active_until, format.format(date))
        } else {
            view.text = getString(R.string.maintenance_expired)
        }
    }
}
