/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.content.Intent

internal data class LockTaskTargetLaunch(
    val flags: Int,
    val enablesLockTask: Boolean,
)

/**
 * A new LockTask session needs a fresh task. An existing session must preserve
 * the target task: destroying an Android-hosted native runtime can wedge its
 * replacement activity in the same process.
 */
internal fun lockTaskTargetLaunch(alreadyLocked: Boolean): LockTaskTargetLaunch {
    return if (alreadyLocked) {
        LockTaskTargetLaunch(
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            enablesLockTask = false,
        )
    } else {
        LockTaskTargetLaunch(
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            enablesLockTask = true,
        )
    }
}
