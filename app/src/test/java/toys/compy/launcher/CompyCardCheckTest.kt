package toys.compy.launcher

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CompyCardCheckTest {
    @Test
    fun absentAndUnmountedVolumesAreDistinguished() {
        assertEquals(CompyCardCondition.MISSING, CompyCardCheck.inspect(null).condition)

        val unmounted =
            RemovableVolumeSnapshot(
                state = Environment.MEDIA_UNMOUNTED,
                root = null,
                uuid = null,
            )
        assertEquals(CompyCardCondition.UNREADABLE, CompyCardCheck.inspect(unmounted).condition)
    }

    @Test
    fun mountedCardWithoutCompyLayoutIsRepairable() = withRoot { root ->
        val result = CompyCardCheck.inspect(mounted(root))

        assertEquals(CompyCardCondition.UNINITIALIZED, result.condition)
        assertTrue(result.repairable)
        assertTrue(result.missingDestinations.containsAll(CompyStorageContract.INITIALIZED_DESTINATION_PATHS))
    }

    @Test
    fun unwritableBlankCardIsReportedBeforeInitializationIsOffered() = withRoot { root ->
        var probedDirectory: File? = null

        val result =
            CompyCardCheck.inspect(mounted(root)) { directory ->
                probedDirectory = directory
                throw IOException("read-only fixture")
            }

        assertEquals(root, probedDirectory)
        assertEquals(CompyCardCondition.UNWRITABLE, result.condition)
        assertFalse(result.repairable)
    }

    @Test
    fun malformedIdentityIsReportedBeforeLayoutUse() = withRoot { root ->
        File(root, "Broken.sdcard.json").writeText("not json")

        val result = CompyCardCheck.inspect(mounted(root))

        assertEquals(CompyCardCondition.IDENTITY_INVALID, result.condition)
        assertFalse(result.repairable)
    }

    @Test
    fun actualWriteProbeControlsWritabilityVerdict() = withRoot { root ->
        initialize(root)
        var probeCalled = false

        val result =
            CompyCardCheck.inspect(mounted(root)) {
                probeCalled = true
                throw IOException("read-only fixture")
            }

        assertTrue(probeCalled)
        assertEquals(CompyCardCondition.UNWRITABLE, result.condition)
        assertFalse(result.repairable)
    }

    @Test
    fun initializedWritableCardIsHealthyAndProbeLeavesNoStage() = withRoot { root ->
        initialize(root)
        val projects = File(root, "Documents/compy/projects")

        val result = CompyCardCheck.inspect(mounted(root))

        assertEquals(CompyCardCondition.HEALTHY, result.condition)
        assertEquals("fs:7aff-7538", result.cardId)
        assertFalse(projects.listFiles().orEmpty().any { it.name.startsWith(".incoming.") })
    }

    @Test
    fun warningAcknowledgementIsScopedToConditionAndCard() {
        val missing = CompyCardCheckResult(CompyCardCondition.MISSING)
        val firstCard =
            CompyCardCheckResult(CompyCardCondition.UNWRITABLE, cardId = "fs:1111-2222")
        val secondCard =
            CompyCardCheckResult(CompyCardCondition.UNWRITABLE, cardId = "fs:3333-4444")

        assertEquals("MISSING|", KioskState.cardWarningFingerprint(missing))
        assertFalse(
            KioskState.cardWarningFingerprint(firstCard) ==
                KioskState.cardWarningFingerprint(secondCard),
        )
    }

    private fun mounted(root: File): RemovableVolumeSnapshot =
        RemovableVolumeSnapshot(
            state = Environment.MEDIA_MOUNTED,
            root = root,
            uuid = "7AFF-7538",
        )

    private fun initialize(root: File) {
        CompyStorageContract.INITIALIZED_DESTINATION_PATHS.forEach { relativePath ->
            assertTrue(File(File(root, CompyStorageContract.ROOT), relativePath).mkdirs())
        }
    }

    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("compy-card-check-test").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
