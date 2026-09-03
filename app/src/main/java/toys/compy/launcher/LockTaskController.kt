/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Sole owner of Device Owner policy and the LockTask lifecycle. */
object LockTaskController {
    private const val TAG = "CompyLockTask"

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationGeneration = AtomicInteger()
    private var pendingConfirmation: PendingConfirmation? = null
    @Volatile
    private var ownershipReleaseInProgress = false

    private data class PendingConfirmation(
        val generation: Int,
        val activityManager: ActivityManager,
        val expectedMode: Int,
        val deadline: Long,
        val onConfirmed: () -> Unit,
        val onTimeout: (String) -> Unit,
        var readyToCheck: Boolean,
    )

    data class RecoveryResult(
        val success: Boolean,
        val message: String,
    )

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            devicePolicyManager(context).isDeviceOwnerApp(context.packageName)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not read Device Owner state", error)
            false
        }
    }

    fun rebootDevice(context: Context) {
        val appContext = context.applicationContext
        if (!isDeviceOwner(appContext)) {
            throw IllegalStateException("Launcher must be Device Owner to restart Compy")
        }
        devicePolicyManager(appContext).reboot(adminComponent(appContext))
    }

    fun lockTaskModeState(context: Context): Int {
        return activityManager(context).lockTaskModeState
    }

    fun isLocked(context: Context): Boolean {
        return lockTaskModeState(context) == ActivityManager.LOCK_TASK_MODE_LOCKED
    }

    fun refreshKeyguardPolicy(context: Context): RecoveryResult {
        val appContext = context.applicationContext
        if (RecoveryState.isRequested(appContext)) {
            return RecoveryResult(true, "Keyguard policy refresh skipped during recovery")
        }
        if (!isDeviceOwner(appContext)) {
            return RecoveryResult(true, "Keyguard policy refresh skipped without Device Owner")
        }
        return try {
            requireKeyguardDisabled(appContext)
            RecoveryResult(true, "Android keyguard disabled")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not refresh Android keyguard policy", error)
            RecoveryResult(
                false,
                error.message ?: "Could not refresh Android keyguard policy",
            )
        }
    }

    /** Stops the target package without clearing its APK or app data. */
    fun stopTargetForRestart(
        context: Context,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        val appContext = context.applicationContext
        if (!isDeviceOwner(appContext)) {
            mainHandler.post {
                onComplete(false, "Launcher must be Device Owner to stop the target app")
            }
            return
        }

        backgroundExecutor.execute {
            val dpm = devicePolicyManager(appContext)
            val admin = adminComponent(appContext)
            val targetPackage = KioskConfig.TARGET_PACKAGE
            try {
                // Android's Device Owner hide path kills the package while preserving its APK and
                // data. Activity teardown alone can leave a wedged native LÖVE thread running.
                setApplicationHiddenState(dpm, admin, targetPackage, hidden = true)
                setApplicationHiddenState(dpm, admin, targetPackage, hidden = false)
                mainHandler.post { onComplete(true, null) }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Could not stop target app for restart", error)
                val visibilityFailure =
                    try {
                        setApplicationHiddenState(dpm, admin, targetPackage, hidden = false)
                        null
                    } catch (visibilityError: RuntimeException) {
                        Log.e(TAG, "Could not restore target app visibility", visibilityError)
                        visibilityError.message ?: "unknown visibility error"
                    }
                val message =
                    error.message ?: "Could not stop target app for restart"
                mainHandler.post {
                    onComplete(
                        false,
                        visibilityFailure?.let {
                            "$message; the target app may remain hidden: $it"
                        } ?: message,
                    )
                }
            }
        }
    }

    /**
     * Applies owner policy and launches the target inside LockTask.
     * Returns false when this package is not Device Owner so callers can retain soft-kiosk behavior.
     */
    fun armAndLaunchTarget(
        context: Context,
        onFailure: (String) -> Unit,
    ): Boolean {
        val appContext = context.applicationContext
        if (RecoveryState.isRequested(appContext)) {
            Log.i(TAG, "Kiosk arm skipped because recovery is requested")
            return true
        }
        if (ownershipReleaseInProgress) {
            Log.i(TAG, "Kiosk arm skipped while Device Owner release is in progress")
            return true
        }
        if (!isDeviceOwner(appContext)) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onFailure("Launcher-driven LockTask requires Android 9 or newer")
            return true
        }

        val generation = beginOperation()
        backgroundExecutor.execute {
            try {
                armPolicies(appContext)
            } catch (error: RuntimeException) {
                failOperation(generation, "Could not arm kiosk policy", error, onFailure)
                return@execute
            }

            mainHandler.post {
                if (operationGeneration.get() != generation) {
                    return@post
                }

                val launchIntent =
                    appContext.packageManager.getLaunchIntentForPackage(KioskConfig.TARGET_PACKAGE)
                if (launchIntent == null) {
                    failOperation(
                        generation,
                        "Target app has no launch activity: ${KioskConfig.TARGET_PACKAGE}",
                        null,
                        onFailure,
                    )
                    return@post
                }

                val alreadyLocked = isLocked(appContext)
                val launch = lockTaskTargetLaunch(alreadyLocked)
                launchIntent.addFlags(launch.flags)

                if (!alreadyLocked) {
                    beginConfirmation(
                        generation = generation,
                        context = appContext,
                        expectedMode = ActivityManager.LOCK_TASK_MODE_LOCKED,
                        readyToCheck = true,
                        onConfirmed = { Log.i(TAG, "LockTask entry confirmed") },
                        onTimeout = onFailure,
                    )
                }

                try {
                    if (launch.enablesLockTask) {
                        val options = ActivityOptions.makeBasic().apply {
                            setLockTaskEnabled(true)
                        }
                        appContext.startActivity(launchIntent, options.toBundle())
                    } else {
                        appContext.startActivity(launchIntent)
                    }
                    if (alreadyLocked) {
                        Log.i(TAG, "Target resumed inside existing LockTask session")
                    }
                } catch (error: RuntimeException) {
                    failOperation(generation, "Could not launch target into LockTask", error, onFailure)
                }
            }
        }
        return true
    }

    /** Arms the launcher activity so a pre-launch gate stays inside LockTask. */
    fun armLauncherActivity(
        activity: Activity,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ): Boolean {
        val appContext = activity.applicationContext
        if (!isDeviceOwner(appContext)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onFailure("Launcher-driven LockTask requires Android 9 or newer")
            return true
        }
        if (isLocked(appContext)) {
            mainHandler.post(onReady)
            return true
        }

        val generation = beginOperation()
        backgroundExecutor.execute {
            try {
                armPolicies(appContext)
            } catch (error: RuntimeException) {
                failOperation(
                    generation,
                    "Could not arm launcher safety gate",
                    error,
                    onFailure,
                )
                return@execute
            }
            mainHandler.post {
                if (
                    operationGeneration.get() != generation ||
                    activity.isFinishing || activity.isDestroyed
                ) {
                    return@post
                }
                beginConfirmation(
                    generation = generation,
                    context = appContext,
                    expectedMode = ActivityManager.LOCK_TASK_MODE_LOCKED,
                    readyToCheck = false,
                    onConfirmed = onReady,
                    onTimeout = onFailure,
                )
                try {
                    activity.startLockTask()
                    pendingConfirmation
                        ?.takeIf { it.generation == generation }
                        ?.let { pending ->
                            pending.readyToCheck = true
                            checkPendingConfirmation(generation)
                        }
                } catch (error: RuntimeException) {
                    failOperation(
                        generation,
                        "Could not enter launcher safety gate",
                        error,
                        onFailure,
                    )
                }
            }
        }
        return true
    }

    /** Clears owner policy and confirms LockTask exit before exposing maintenance UI. */
    fun disarm(
        context: Context,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        disarmInternal(
            context = context,
            allowDuringOwnershipRelease = false,
            onReady = onReady,
            onFailure = onFailure,
        )
    }

    private fun disarmInternal(
        context: Context,
        allowDuringOwnershipRelease: Boolean,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val appContext = context.applicationContext
        if (ownershipReleaseInProgress && !allowDuringOwnershipRelease) {
            mainHandler.post { onFailure("Device Owner release is already in progress") }
            return
        }
        val generation = beginOperation()

        if (!isDeviceOwner(appContext)) {
            if (lockTaskModeState(appContext) == ActivityManager.LOCK_TASK_MODE_NONE) {
                mainHandler.post { onReady() }
            } else {
                mainHandler.post { onFailure("LockTask is active but the launcher is not Device Owner") }
            }
            return
        }

        mainHandler.post {
            beginConfirmation(
                generation = generation,
                context = appContext,
                expectedMode = ActivityManager.LOCK_TASK_MODE_NONE,
                readyToCheck = false,
                onConfirmed = onReady,
                onTimeout = onFailure,
            )
        }

        backgroundExecutor.execute {
            try {
                val dpm = devicePolicyManager(appContext)
                val admin = adminComponent(appContext)
                // Removing the active package from the allowlist exits LockTask on API 23+.
                dpm.setLockTaskPackages(admin, emptyArray())
                dpm.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
            } catch (error: RuntimeException) {
                failOperation(generation, "Could not disarm kiosk policy", error, onFailure)
                return@execute
            }

            mainHandler.post {
                val pending = pendingConfirmation
                if (pending?.generation == generation) {
                    pending.readyToCheck = true
                    checkPendingConfirmation(generation)
                }
            }
        }
    }

    fun releaseDeviceOwner(
        context: Context,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        val appContext = context.applicationContext
        if (ownershipReleaseInProgress) {
            mainHandler.post { onComplete(false, "Device Owner release is already in progress") }
            return
        }
        ownershipReleaseInProgress = true
        val complete = { released: Boolean, message: String? ->
            ownershipReleaseInProgress = false
            onComplete(released, message)
        }
        disarmInternal(
            appContext,
            allowDuringOwnershipRelease = true,
            onReady = {
                backgroundExecutor.execute {
                    try {
                        val dpm = devicePolicyManager(appContext)
                        if (dpm.isDeviceOwnerApp(appContext.packageName)) {
                            @Suppress("DEPRECATION")
                            dpm.clearDeviceOwnerApp(appContext.packageName)
                        }
                        val released = !dpm.isDeviceOwnerApp(appContext.packageName)
                        mainHandler.post {
                            complete(
                                released,
                                if (released) null else "Android still reports the launcher as Device Owner",
                            )
                        }
                    } catch (error: RuntimeException) {
                        Log.e(TAG, "Could not release Device Owner", error)
                        mainHandler.post { complete(false, error.message) }
                    }
                }
            },
            onFailure = { message -> complete(false, message) },
        )
    }

    /**
     * Clears launcher-owned policy from the isolated recovery process.
     * The durable gate is written first so a live or restarting UI process cannot re-arm.
     */
    fun recoverDeviceOwner(context: Context): RecoveryResult {
        val appContext = context.applicationContext
        try {
            RecoveryState.request(appContext)
        } catch (error: IOException) {
            Log.e(TAG, "Could not persist recovery request", error)
            return RecoveryResult(false, error.message ?: "Could not persist recovery request")
        }

        return try {
            val dpm = devicePolicyManager(appContext)
            if (dpm.isDeviceOwnerApp(appContext.packageName)) {
                val admin = adminComponent(appContext)
                dpm.setLockTaskPackages(admin, emptyArray())
                dpm.clearPackagePersistentPreferredActivities(admin, appContext.packageName)
                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(appContext.packageName)
            }

            if (dpm.isDeviceOwnerApp(appContext.packageName)) {
                RecoveryResult(false, "Android still reports the launcher as Device Owner")
            } else {
                RecoveryResult(true, "Device Owner released; kiosk arming remains disabled")
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not recover Device Owner", error)
            RecoveryResult(false, error.message ?: "Could not recover Device Owner")
        }
    }

    /** Clears the recovery gate after the utility has re-provisioned this package as Device Owner. */
    fun enableAfterRecovery(context: Context): RecoveryResult {
        val appContext = context.applicationContext
        if (!isDeviceOwner(appContext)) {
            return RecoveryResult(false, "Launcher must be Device Owner before enabling kiosk")
        }
        return try {
            RecoveryState.clear(appContext)
            RecoveryResult(true, "Kiosk arming enabled")
        } catch (error: IOException) {
            Log.e(TAG, "Could not clear recovery request", error)
            RecoveryResult(false, error.message ?: "Could not clear recovery request")
        }
    }

    /** DeviceAdmin callbacks wake the bounded poll; ActivityManager remains authoritative. */
    fun onLockTaskStateChanged() {
        mainHandler.post {
            val pending = pendingConfirmation ?: return@post
            checkPendingConfirmation(pending.generation)
        }
    }

    private fun armPolicies(context: Context) {
        requireKeyguardDisabled(context)
        val dpm = devicePolicyManager(context)
        val admin = adminComponent(context)
        dpm.setLockTaskPackages(admin, KioskConfig.LOCK_TASK_PACKAGES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, KioskConfig.LOCK_TASK_FEATURES)
        }

        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_HOME)
        }
        dpm.addPersistentPreferredActivity(
            admin,
            homeFilter,
            ComponentName(context, MainActivity::class.java),
        )
    }

    private fun requireKeyguardDisabled(context: Context) {
        val dpm = devicePolicyManager(context)
        val admin = adminComponent(context)
        check(dpm.setKeyguardDisabled(admin, true)) {
            "Could not disable Android keyguard"
        }
    }

    private fun setApplicationHiddenState(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        hidden: Boolean,
    ) {
        if (dpm.isApplicationHidden(admin, packageName) == hidden) {
            return
        }
        val changed = dpm.setApplicationHidden(admin, packageName, hidden)
        if (!changed || dpm.isApplicationHidden(admin, packageName) != hidden) {
            val state = if (hidden) "hide" else "unhide"
            throw IllegalStateException("Android could not $state $packageName")
        }
    }

    private fun beginOperation(): Int {
        val generation = operationGeneration.incrementAndGet()
        mainHandler.post {
            pendingConfirmation = null
        }
        return generation
    }

    private fun beginConfirmation(
        generation: Int,
        context: Context,
        expectedMode: Int,
        readyToCheck: Boolean,
        onConfirmed: () -> Unit,
        onTimeout: (String) -> Unit,
    ) {
        if (operationGeneration.get() != generation) {
            return
        }
        pendingConfirmation =
            PendingConfirmation(
                generation = generation,
                activityManager = activityManager(context),
                expectedMode = expectedMode,
                deadline = SystemClock.elapsedRealtime() + KioskConfig.LOCK_TASK_CONFIRM_TIMEOUT_MS,
                onConfirmed = onConfirmed,
                onTimeout = onTimeout,
                readyToCheck = readyToCheck,
            )
        mainHandler.postDelayed(
            { checkPendingConfirmation(generation) },
            KioskConfig.LOCK_TASK_CONFIRM_INTERVAL_MS,
        )
    }

    private fun checkPendingConfirmation(generation: Int) {
        val pending = pendingConfirmation ?: return
        if (pending.generation != generation || operationGeneration.get() != generation) {
            return
        }
        if (!pending.readyToCheck) {
            return
        }

        val actualMode = pending.activityManager.lockTaskModeState
        if (actualMode == pending.expectedMode) {
            pendingConfirmation = null
            pending.onConfirmed()
            return
        }

        if (SystemClock.elapsedRealtime() >= pending.deadline) {
            pendingConfirmation = null
            pending.onTimeout(
                "Timed out waiting for LockTask mode ${pending.expectedMode}; current mode is $actualMode",
            )
            return
        }

        mainHandler.postDelayed(
            { checkPendingConfirmation(generation) },
            KioskConfig.LOCK_TASK_CONFIRM_INTERVAL_MS,
        )
    }

    private fun failOperation(
        generation: Int,
        message: String,
        error: RuntimeException?,
        onFailure: (String) -> Unit,
    ) {
        Log.e(TAG, message, error)
        mainHandler.post {
            if (operationGeneration.get() == generation) {
                pendingConfirmation = null
                onFailure(error?.message?.let { "$message: $it" } ?: message)
            }
        }
    }

    private fun adminComponent(context: Context): ComponentName {
        return ComponentName(context, KioskDeviceAdminReceiver::class.java)
    }

    private fun devicePolicyManager(context: Context): DevicePolicyManager {
        return context.getSystemService(DevicePolicyManager::class.java)
    }

    private fun activityManager(context: Context): ActivityManager {
        return context.getSystemService(ActivityManager::class.java)
    }
}
