package toys.compy.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class RootedSetupTest {
    // Synthetic control records exercise the parser, not Android getprop.
    private val intent = "1".repeat(32)
    private val job = "2".repeat(32)

    @Test fun legacyCarrierCanLaunch() {
        assertEquals(RootedSetup.State.READY, RootedSetup.state("", "", ""))
    }

    @Test fun carrierHoldsHomeUntilMatchingCompletion() {
        assertEquals(RootedSetup.State.WAITING, RootedSetup.state("1", "", ""))
        assertEquals(RootedSetup.State.WAITING, RootedSetup.state("1", intent, "p:$intent:$job"))
        assertEquals(RootedSetup.State.READY, RootedSetup.state("1", intent, "c:$intent"))
    }

    @Test fun malformedOrMismatchedRecordsKeepIdeClosed() {
        for (control in listOf("", "c:$job", "p:$job:$intent", "p:$intent:bad")) {
            assertEquals(RootedSetup.State.FAILED, RootedSetup.state("1", intent, control))
        }
        assertEquals(RootedSetup.State.FAILED, RootedSetup.state("1", "error", "c:$intent"))
        assertEquals(RootedSetup.State.FAILED, RootedSetup.state("2", intent, "c:$intent"))
    }
}
