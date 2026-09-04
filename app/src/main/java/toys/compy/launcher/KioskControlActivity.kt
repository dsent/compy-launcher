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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

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
    private val maintenanceExecutor = Executors.newSingleThreadExecutor()
    private var maintenanceStatusView: TextView? = null
    private var operationTitleView: TextView? = null
    private var operationMessageView: TextView? = null
    private var exitingMaintenance = false
    private var operationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockTaskController.configureWakeVisibility(this)

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
        if (!showPendingCardInitializationVerification()) {
            showPendingCardInitializationRequest()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (LockTaskController.lockTaskModeState(this) == ActivityManager.LOCK_TASK_MODE_NONE) {
            if (!showPendingCardInitializationVerification()) {
                showPendingCardInitializationRequest()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LockTaskController.configureWakeVisibility(this)
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

    override fun onDestroy() {
        maintenanceExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) recordMaintenanceInteraction()
        if (
            event.action == KeyEvent.ACTION_UP &&
            event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
            !event.isCanceled
        ) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        exitMaintenance()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
        ) {
            recordMaintenanceInteraction()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Pointer hover and movement must not keep an unattended maintenance session alive.
        if (
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) {
            recordMaintenanceInteraction()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun recordMaintenanceInteraction() {
        if (
            !exitingMaintenance &&
            LockTaskController.lockTaskModeState(this) == ActivityManager.LOCK_TASK_MODE_NONE
        ) {
            rearmMaintenanceExpiry()
        }
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

        maintenanceStatusView =
            TextView(this).apply {
                setTextColor(Color.YELLOW)
                textSize = 14f
                setPadding(0, 0, 0, 20)
            }
        maintenanceStatusView?.let { statusView ->
            updateStatus(statusView)
            actionColumn.addView(statusView)
        }

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
                        MaintenanceAction(R.string.btn_initialize_sd_card) {
                            confirmInitializeCard(resumeAfterSuccess = false)
                        },
                    ),
                ),
                MaintenanceGroup(
                    R.string.maintenance_group_backup,
                    listOf(
                        MaintenanceAction(R.string.btn_create_backup, ::confirmCreateBackup),
                        MaintenanceAction(R.string.btn_restore_backup, ::chooseBackupToRestore),
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

        if (LockTaskController.isDeviceOwner(this)) {
            showOperation(
                getString(R.string.maintenance_restarting_ide_title),
                getString(R.string.maintenance_restarting_ide_message),
                busy = true,
            )
            LockTaskController.stopTargetForRestart(this) { stopped, message ->
                if (isFinishing || isDestroyed) {
                    return@stopTargetForRestart
                }
                if (stopped) {
                    finishMaintenanceExit()
                } else {
                    exitingMaintenance = false
                    showOperationFailure(
                        getString(
                            R.string.maintenance_restart_ide_failed,
                            message ?: getString(R.string.maintenance_unknown_error),
                        ),
                    )
                }
            }
            return
        }

        finishMaintenanceExit()
    }

    private fun finishMaintenanceExit() {
        KioskState.disableMaintenance(this)
        val launcherIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(launcherIntent)
        finish()
    }

    private fun scheduleMaintenanceExpiry() {
        expiryHandler.removeCallbacks(expiryRunnable)
        val remainingMs = KioskState.getMaintenanceDeadline(this) - SystemClock.elapsedRealtime()
        if (remainingMs <= 0) {
            expiryHandler.post(expiryRunnable)
        } else {
            expiryHandler.postDelayed(expiryRunnable, remainingMs)
        }
    }

    private fun rearmMaintenanceExpiry() {
        KioskState.enableMaintenance(this, KioskConfig.MAINTENANCE_DURATION_MS)
        maintenanceStatusView?.let(::updateStatus)
        scheduleMaintenanceExpiry()
    }

    private fun handleMaintenanceExpiry() {
        if (operationInProgress) {
            rearmMaintenanceExpiry()
        } else if (KioskState.isMaintenanceActive(this)) {
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

    private data class CardInitializationPlan(
        val cardRoot: File,
        val internalCompyDirectory: File,
        val cardId: String,
        val label: String?,
        val identityFilePresent: Boolean,
    )

    private fun confirmInitializeCard(resumeAfterSuccess: Boolean) {
        val plan =
            try {
                cardInitializationPlan()
            } catch (error: Exception) {
                showOperationFailure(error.message ?: getString(R.string.maintenance_unknown_error))
                return
            }
        val label = plan.label ?: getString(R.string.sd_initialize_unassigned)
        val identityState =
            getString(
                if (plan.identityFilePresent) {
                    R.string.sd_initialize_identity_present
                } else {
                    R.string.sd_initialize_identity_absent
                },
            )
        val uuid = plan.cardId.removePrefix(CompyStorageContract.CARD_ID_PREFIX)
        val signals = getString(R.string.sd_initialize_signals, label, uuid, identityState)
        confirmAction(
            title = getString(R.string.sd_initialize_title),
            message = getString(R.string.sd_initialize_message, signals),
            confirm = getString(R.string.sd_initialize_confirm),
        ) {
            runInitializeCard(plan, resumeAfterSuccess)
        }
    }

    private fun showPendingCardInitializationRequest() {
        val statusView = operationMessageView ?: return
        if (!KioskState.consumeCardInitializationRequest(this)) return
        statusView.post { confirmInitializeCard(resumeAfterSuccess = true) }
    }

    private fun showPendingCardInitializationVerification(): Boolean {
        val pending = KioskState.pendingCardInitialization(this) ?: return false
        val statusView = operationMessageView ?: return true
        statusView.post { runCardInitializationVerification(pending) }
        return true
    }

    private fun cardInitializationPlan(): CardInitializationPlan {
        val card = CompyStorage.removableStorage(this)
        val cardRoot = card.rootDirectory
        val identityFiles = cardRoot.listFiles()
            ?.filter { file ->
                file.isFile && file.name.endsWith(CompyStorageContract.CARD_IDENTITY_FILE_SUFFIX)
            }
            ?: throw IOException("The mounted removable storage root is unreadable")
        val label =
            identityFiles.singleOrNull()?.name?.removeSuffix(CompyStorageContract.CARD_IDENTITY_FILE_SUFFIX)
        return CardInitializationPlan(
            cardRoot = cardRoot,
            internalCompyDirectory = CompyStorage.internalStorage(this).compyDirectory,
            cardId = card.id,
            label = label,
            identityFilePresent = identityFiles.isNotEmpty(),
        )
    }

    private fun runInitializeCard(
        plan: CardInitializationPlan,
        resumeAfterSuccess: Boolean,
    ) {
        showOperation(
            getString(R.string.sd_initialize_working),
            getString(R.string.sd_initialize_wait),
            busy = true,
        )
        runMaintenanceOperation(
            operation = {
                val result =
                    CompyCardInitializer.initialize(
                        internalCompyDirectory = plan.internalCompyDirectory,
                        cardRoot = plan.cardRoot,
                        operationId = UUID.randomUUID().toString().lowercase(Locale.US),
                    )
                KioskState.clearCardInitializationFailure(this, plan.cardId)
                val check = CompyCardCheck.inspect(this)
                if (!check.healthy) {
                    throw IOException(
                        check.detail
                            ?: "SD card remained ${check.condition.name.lowercase(Locale.US)} after initialization",
                    )
                }
                syncStorageWrites()
                KioskState.recordPendingCardInitialization(
                    context = this,
                    cardId = plan.cardId,
                    result = result,
                    resumeAfterSuccess = resumeAfterSuccess,
                )
                syncStorageWrites()
                LockTaskController.rebootDevice(this)
                result
            },
            onSuccess = {
                showOperation(
                    getString(R.string.sd_initialize_restarting),
                    getString(R.string.sd_initialize_restarting_message),
                    busy = true,
                )
            },
            onFailure = { error ->
                if (KioskState.pendingCardInitialization(this) == null) {
                    showOperationFailure(
                        error.message ?: getString(R.string.maintenance_unknown_error),
                    )
                } else {
                    showOperation(
                        getString(R.string.sd_initialize_restart_required),
                        getString(R.string.sd_initialize_restart_required_message),
                    )
                }
            },
        )
    }

    private fun runCardInitializationVerification(pending: PendingCardInitialization) {
        val expectedUuid = pending.cardId.removePrefix(CompyStorageContract.CARD_ID_PREFIX)
        showOperation(
            getString(R.string.sd_initialize_verifying),
            getString(R.string.sd_initialize_verifying_message, expectedUuid),
            busy = true,
        )
        var matchingCardSeen = false
        runMaintenanceOperation(
            operation = {
                val card = awaitRemountedCard()
                if (card.id != pending.cardId) {
                    throw IOException(
                        getString(R.string.sd_initialize_wrong_card, expectedUuid),
                    )
                }
                matchingCardSeen = true
                val internalCompyDirectory =
                    CompyStorage.internalStorage(this).compyDirectory
                CompyCardInitializer.verify(internalCompyDirectory, card.rootDirectory)
                pending
            },
            onSuccess = { result ->
                KioskState.clearPendingCardInitialization(this)
                KioskState.clearCardInitializationFailure(this, result.cardId)
                if (result.resumeAfterSuccess) {
                    exitMaintenance()
                } else {
                    showOperation(
                        getString(R.string.sd_initialize_success),
                        getString(
                            R.string.sd_initialize_success_message,
                            result.seededProjects,
                            result.copiedFiles,
                            result.reusedFiles,
                        ),
                    )
                }
            },
            onFailure = { error ->
                val detail =
                    if (matchingCardSeen) {
                        getString(
                            R.string.sd_initialize_persistence_failed,
                            error.message ?: getString(R.string.maintenance_unknown_error),
                        )
                    } else {
                        error.message ?: getString(R.string.sd_initialize_wrong_card, expectedUuid)
                    }
                if (matchingCardSeen) {
                    KioskState.clearPendingCardInitialization(this)
                    KioskState.recordCardInitializationFailure(
                        this,
                        pending.cardId,
                        detail,
                    )
                }
                showOperationFailure(detail)
            },
        )
    }

    private fun awaitRemountedCard(): MountedCompyStorage {
        val deadline = SystemClock.elapsedRealtime() + CARD_REMOUNT_TIMEOUT_MS
        var lastError: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return CompyStorage.removableStorage(this)
            } catch (error: Exception) {
                lastError = error
                try {
                    Thread.sleep(CARD_REMOUNT_POLL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("SD verification was interrupted", interrupted)
                }
            }
        }
        throw IOException(
            lastError?.message ?: "The SD card did not remount in time",
            lastError,
        )
    }

    private fun syncStorageWrites() {
        val process = ProcessBuilder("/system/bin/sync")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val status = process.waitFor()
        if (status != 0) {
            throw IOException(
                "Could not sync SD-card writes" + output.takeIf(String::isNotEmpty)
                    ?.let { ": $it" }.orEmpty(),
            )
        }
    }

    private fun confirmCreateBackup() {
        confirmAction(
            titleRes = R.string.create_backup_title,
            messageRes = R.string.create_backup_message,
            confirmRes = R.string.create_backup_confirm,
        ) {
            runCreateBackup()
        }
    }

    private fun runCreateBackup(confirmedBusyLocks: List<ObservedBackupLock> = emptyList()) {
        showOperation(
            getString(R.string.create_backup_working),
            getString(R.string.create_backup_wait),
            busy = true,
        )
        runMaintenanceOperation(
            operation = {
                backupStore().createBackup(installedApkSnapshots(), confirmedBusyLocks)
            },
            onSuccess = { result ->
                val cleanupWarning =
                    if (result.cleanupWarnings.isEmpty()) {
                        ""
                    } else {
                        "\n\n" + getString(R.string.create_backup_cleanup_warning, result.cleanupWarnings.size)
                    }
                showOperation(
                    getString(R.string.create_backup_success),
                    getString(
                        R.string.create_backup_success_message,
                        result.backupSet.ordinal,
                        result.projectEntries,
                        result.retainedSets,
                    ) + cleanupWarning,
                )
            },
            onFailure = { error ->
                when (error) {
                    is BackupStoreBusyException -> confirmBreakBackupLocks(error.busyLocks)
                    is MissingInternalIdentityException -> confirmInternalIdentityAdoption(confirmedBusyLocks)
                    else -> showOperationFailure(error.message ?: getString(R.string.maintenance_unknown_error))
                }
            },
        )
    }

    private fun confirmInternalIdentityAdoption(confirmedBusyLocks: List<ObservedBackupLock>) {
        val proposal =
            try {
                CompyStorage.planInternalStorageAdoption(this)
            } catch (error: Exception) {
                showOperationFailure(error.message ?: getString(R.string.maintenance_unknown_error))
                return
            }
        confirmAction(
            title = getString(R.string.create_backup_adopt_internal_title),
            message =
                getString(
                    R.string.create_backup_adopt_internal_message,
                    proposal.deviceId,
                    proposal.hardwareSerial,
                ),
            confirm = getString(R.string.create_backup_adopt_internal_confirm),
        ) {
            showOperation(
                getString(R.string.create_backup_adopt_internal_working),
                getString(R.string.create_backup_adopt_internal_wait),
                busy = true,
            )
            runMaintenanceOperation(
                operation = { CompyStorage.adoptInternalStorage(proposal) },
                onSuccess = { runCreateBackup(confirmedBusyLocks) },
            )
        }
    }

    private fun confirmBreakBackupLocks(locks: List<ObservedBackupLock>) {
        val details =
            locks.joinToString("\n\n") { lock ->
                val destination =
                    when (lock.destination.kind) {
                        BackupSourceKind.CARD -> "SD card ${lock.destination.id.removePrefix(CompyStorageContract.CARD_ID_PREFIX)}"
                        BackupSourceKind.INTERNAL -> "internal ${lock.destination.id.take(8)}"
                    }
                "$destination\nWriter: ${lock.writer ?: "unknown"}\nOperation: ${lock.operationId ?: "unreadable"}\nSource: ${lock.sourceKind ?: "unknown"} ${lock.sourceId ?: "unknown"}"
            }
        showOperation(getString(R.string.create_backup_busy_title), details)
        confirmAction(
            title = getString(R.string.create_backup_busy_title),
            message = details,
            confirm = getString(R.string.create_backup_busy_confirm),
        ) {
            runCreateBackup(locks)
        }
    }

    private fun chooseBackupToRestore() {
        showOperation(
            getString(R.string.restore_backup_loading),
            getString(R.string.restore_backup_loading_message),
            busy = true,
        )
        runMaintenanceOperation(
            operation = {
                val store = backupStore()
                store to store.listBackupSets()
            },
            onSuccess = { (store, backupSets) ->
                if (backupSets.isEmpty()) {
                    showOperation(
                        getString(R.string.restore_backup_none),
                        getString(R.string.restore_backup_none_message),
                    )
                    return@runMaintenanceOperation
                }

                showOperation(
                    getString(R.string.restore_backup_choose),
                    getString(R.string.restore_backup_choose_message),
                )
                val labels = backupSets.map(::snapshotDisplayLabel)
                AlertDialog.Builder(this)
                    .setTitle(R.string.restore_backup_choose)
                    .setItems(labels.toTypedArray()) { _, which ->
                        val backupSet = backupSets[which]
                        if (backupSet.restorable) {
                            confirmRestoreBackup(store, backupSet)
                        } else {
                            showOperation(
                                getString(R.string.restore_backup_unavailable),
                                getString(
                                    R.string.restore_backup_unavailable_message,
                                    backupSet.problem ?: getString(R.string.maintenance_unknown_error),
                                ),
                            )
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        )
    }

    private fun confirmRestoreBackup(store: CompyBackupStore, backupSet: CompyBackupSet) {
        confirmAction(
            title = getString(R.string.restore_backup_title, backupSet.ordinal),
            message = getString(R.string.restore_backup_message),
            confirm = getString(R.string.restore_backup_confirm),
        ) {
            showOperation(
                getString(R.string.restore_backup_working, backupSet.ordinal),
                getString(R.string.restore_backup_wait),
                busy = true,
            )
            runMaintenanceOperation(
                operation = { store.restoreProjects(backupSet) },
                onSuccess = { result ->
                    showOperation(
                        getString(R.string.restore_backup_success),
                        getString(
                            R.string.restore_backup_success_message,
                            backupSet.ordinal,
                            result.restoredEntries,
                            result.preservedEntries,
                        ),
                    )
                },
            )
        }
    }

    private fun snapshotDisplayLabel(snapshot: CompyBackupSet): String {
        val source =
            when (snapshot.sourceKind) {
                BackupSourceKind.CARD -> "SD card ${snapshot.sourceId.removePrefix(CompyStorageContract.CARD_ID_PREFIX)}"
                BackupSourceKind.INTERNAL -> "internal ${snapshot.sourceId.take(8)}"
            }
        val timestamp = snapshot.createdAt ?: "legacy"
        val details = snapshot.label?.let { "$it · $timestamp" } ?: timestamp
        if (!snapshot.restorable) {
            return getString(
                R.string.restore_backup_unavailable_set_label,
                snapshot.directory.name,
                source,
            )
        }
        return getString(R.string.restore_backup_set_label, snapshot.ordinal, source, details)
    }

    private fun backupStore(): CompyBackupStore {
        val card = CompyStorage.removableStorage(this)
        val internal = CompyStorage.internalStorage(this)
        val cardEndpoint =
            BackupStorageEndpoint(
                kind = BackupSourceKind.CARD,
                id = card.id,
                compyDirectory = card.compyDirectory,
            )
        return CompyBackupStore(
            source = cardEndpoint,
            destinationEndpoints =
                listOf(
                    cardEndpoint,
                    BackupStorageEndpoint(
                        kind = BackupSourceKind.INTERNAL,
                        id = internal.id,
                        compyDirectory = internal.compyDirectory,
                    ),
                ),
            apkInspector = ApkArchiveInspector(::inspectApkArchive),
        )
    }

    private fun installedApkSnapshots(): List<InstalledApkSnapshot> {
        return listOf(KioskConfig.TARGET_PACKAGE, KioskConfig.LAUNCHER_PACKAGE).map { packageName ->
            val applicationInfo = installedApplicationInfo(packageName)
            if (!applicationInfo.splitSourceDirs.isNullOrEmpty()) {
                throw IllegalStateException("Split APKs are not supported for $packageName")
            }
            val packageInfo = installedPackageInfo(packageName)
            InstalledApkSnapshot(
                packageName = packageName,
                versionName = packageInfo.versionName
                    ?: throw IllegalStateException("Installed package has no version name: $packageName"),
                versionCode = packageVersionCode(packageInfo),
                sourceApk = File(applicationInfo.sourceDir),
                debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                signingCertificateSha256 = signingCertificateSha256(packageInfo),
            )
        }
    }

    private fun inspectApkArchive(apk: File): ApkArchiveMetadata {
        val packageInfo = archivedPackageInfo(apk)
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IOException("APK has no application metadata: $apk")
        return ApkArchiveMetadata(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName ?: throw IOException("APK has no version name: $apk"),
            versionCode = packageVersionCode(packageInfo),
            debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            signingCertificateSha256 = signingCertificateSha256(packageInfo),
        )
    }

    private fun installedApplicationInfo(packageName: String): ApplicationInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
    }

    private fun installedPackageInfo(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, packageSignatureFlags())
        }
    }

    private fun archivedPackageInfo(apk: File): PackageInfo {
        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    apk.path,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apk.path, packageSignatureFlags())
            }
        return packageInfo ?: throw IOException("Could not parse APK metadata: $apk")
    }

    @Suppress("DEPRECATION")
    private fun packageSignatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha256(packageInfo: PackageInfo): String {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                packageInfo.signatures
            }
        if (signatures == null || signatures.size != 1) {
            throw IOException("Package must have exactly one signing certificate: ${packageInfo.packageName}")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(signatures.single().toByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun <T> runMaintenanceOperation(
        operation: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: ((Exception) -> Unit)? = null,
    ) {
        maintenanceExecutor.execute {
            try {
                val result = operation()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) onSuccess(result)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        onFailure?.invoke(error)
                            ?: showOperationFailure(
                                error.message ?: getString(R.string.maintenance_unknown_error),
                            )
                    }
                }
            }
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
                    LockTaskController.configureWakeVisibility(this)
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
        confirmAction(
            title = getString(titleRes),
            message = getString(messageRes),
            confirm = getString(confirmRes),
            onConfirmed = onConfirmed,
        )
    }

    private fun confirmAction(
        title: String,
        message: String,
        confirm: String,
        onConfirmed: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(confirm) { _, _ -> onConfirmed() }
            .show()
    }

    private fun showOperation(
        title: String,
        message: String,
        busy: Boolean = false,
    ) {
        val operationFinished = operationInProgress && !busy
        operationInProgress = busy
        if (busy || operationFinished) {
            rearmMaintenanceExpiry()
        }
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
        operationInProgress = false
        rearmMaintenanceExpiry()
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
        val remainingMs = KioskState.getMaintenanceDeadline(this) - SystemClock.elapsedRealtime()
        if (remainingMs > 0) {
            // Wall time is presentation only; expiry remains monotonic.
            val date = Date(System.currentTimeMillis() + remainingMs)
            val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            view.text = getString(R.string.maintenance_active_until, format.format(date))
        } else {
            view.text = getString(R.string.maintenance_expired)
        }
    }

    companion object {
        private const val CARD_REMOUNT_TIMEOUT_MS = 45_000L
        private const val CARD_REMOUNT_POLL_MS = 500L
    }
}
