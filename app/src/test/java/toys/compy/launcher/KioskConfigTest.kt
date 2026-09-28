package toys.compy.launcher

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Warranted by the testing policy: this mask is the locked device's entire SystemUI policy.
class KioskConfigTest {
    @Test
    fun lockTaskExposesOnlyHomeAndGlobalActions() {
        assertEquals(
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
            KioskConfig.LOCK_TASK_FEATURES,
        )
    }

    // The startup card check stays off until it passes cold boots on the cards known to meet the
    // boot-time denial; switching it on is a deliberate change to this test.
    @Test
    fun startupCardCheckIsOff() {
        assertFalse(KioskConfig.STARTUP_CARD_CHECK_ENABLED)
    }
}
