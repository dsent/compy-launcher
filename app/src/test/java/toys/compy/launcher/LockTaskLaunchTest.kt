package toys.compy.launcher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTaskLaunchTest {
    @Test
    fun unlockedSessionStartsFreshLockedTask() {
        val launch = lockTaskTargetLaunch(alreadyLocked = false)

        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            launch.flags,
        )
        assertTrue(launch.enablesLockTask)
    }

    @Test
    fun lockedSessionResumesExistingTask() {
        val launch = lockTaskTargetLaunch(alreadyLocked = true)

        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            launch.flags,
        )
        assertFalse(launch.enablesLockTask)
    }
}
