package toys.compy.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CompyCardInitializerTest {
    @Test
    fun blankCardReceivesVerifiedSeedAndNoIdentityFile() = withRoots { internal, card ->
        createSeed(internal)

        val result = CompyCardInitializer.initialize(internal, card, OPERATION_ID)

        CompyStorageContract.CARD_MEDIA_DIRECTORIES.forEach { name ->
            assertTrue(File(card, name).isDirectory)
        }
        CompyStorageContract.INITIALIZED_DESTINATION_PATHS.forEach { path ->
            assertTrue(File(File(card, CompyStorageContract.ROOT), path).isDirectory)
        }
        assertEquals("print('stock maze')\n", File(card, "Documents/compy/projects/maze/main.lua").readText())
        assertEquals("20260801-11111111\n", File(card, "Documents/compy/stock/programs/maze/stock.txt").readText())
        assertEquals(
            "launcher apk",
            File(card, "Documents/compy/launcher/apk/toys.compy.launcher-debug-test.apk").readText(),
        )
        assertEquals(
            "repair bundle",
            File(card, "Documents/compy/repair/Compy-Repair-test-linux-amd64.zip").readText(),
        )
        assertTrue(result.copiedFiles > 0)
        assertEquals(1, result.seededProjects)
        assertFalse(card.listFiles().orEmpty().any { it.name.endsWith(".sdcard.json") })
        assertNoIncoming(card)
    }

    @Test
    fun retryReusesIdenticalContent() = withRoots { internal, card ->
        createSeed(internal)
        val first = CompyCardInitializer.initialize(internal, card, OPERATION_ID)

        val second = CompyCardInitializer.initialize(internal, card, SECOND_OPERATION_ID)

        assertEquals(0, second.copiedFiles)
        assertTrue(second.reusedFiles >= first.copiedFiles)
        assertNoIncoming(card)
    }

    @Test
    fun remountedSeedVerifiesByteForByte() = withRoots { internal, card ->
        createSeed(internal)
        CompyCardInitializer.initialize(internal, card, OPERATION_ID)

        CompyCardInitializer.verify(internal, card)
    }

    @Test
    fun remountedSeedRejectsSilentContentCorruption() = withRoots { internal, card ->
        createSeed(internal)
        CompyCardInitializer.initialize(internal, card, OPERATION_ID)
        File(card, "Documents/compy/projects/maze/main.lua").writeText("\u0000".repeat(20))

        expectIOException {
            CompyCardInitializer.verify(internal, card)
        }
    }

    @Test
    fun remountedSeedRejectsMissingRequiredDirectory() = withRoots { internal, card ->
        createSeed(internal)
        CompyCardInitializer.initialize(internal, card, OPERATION_ID)
        assertTrue(File(card, "Movies").delete())

        expectIOException {
            CompyCardInitializer.verify(internal, card)
        }
    }

    @Test
    fun conflictFailsBeforeCreatingAnyInitializationDirectory() = withRoots { internal, card ->
        createSeed(internal)
        val conflicting = File(card, "Documents/compy/repair/Compy-Repair-test-linux-amd64.zip")
        assertTrue(conflicting.parentFile!!.mkdirs())
        conflicting.writeText("different")

        expectIOException {
            CompyCardInitializer.initialize(internal, card, OPERATION_ID)
        }

        assertFalse(File(card, "Download").exists())
        assertFalse(File(card, "Documents/compy/projects").exists())
        assertEquals("different", conflicting.readText())
    }

    @Test
    fun layoutCollisionFailsBeforeCreatingAnyInitializationDirectory() = withRoots { internal, card ->
        createSeed(internal)
        File(card, "Download").writeText("collision")

        expectIOException {
            CompyCardInitializer.initialize(internal, card, OPERATION_ID)
        }

        assertFalse(File(card, "Movies").exists())
        assertFalse(File(card, CompyStorageContract.ROOT).exists())
    }

    @Test
    fun incompleteStockFailsBeforeMutation() = withRoots { internal, card ->
        createSeed(internal)
        File(internal, "stock/programs/maze/stock.txt").writeText("missing-version\n")

        expectIOException {
            CompyCardInitializer.initialize(internal, card, OPERATION_ID)
        }

        assertFalse(File(card, "Documents").exists())
    }

    @Test
    fun incompleteRecoverySeedFailsBeforeMutation() = withRoots { internal, card ->
        createSeed(internal)
        assertTrue(File(internal, "launcher/apk/Compy-IDE-debug-test.apk").delete())

        expectIOException {
            CompyCardInitializer.initialize(internal, card, OPERATION_ID)
        }

        assertFalse(File(card, "Documents").exists())
    }

    @Test
    fun missingSupportedRepairBundleFailsBeforeMutation() = withRoots { internal, card ->
        createSeed(internal)
        assertTrue(
            File(internal, "repair/Compy-Repair-test-linux-amd64.zip").delete(),
        )

        expectIOException {
            CompyCardInitializer.initialize(internal, card, OPERATION_ID)
        }

        assertFalse(File(card, "Documents").exists())
    }

    private fun createSeed(internal: File) {
        val program = File(internal, "stock/programs/maze")
        val version = File(program, "20260801-11111111")
        assertTrue(version.mkdirs())
        File(version, "main.lua").writeText("print('stock maze')\n")
        File(version, "README.md").writeText("Maze\n")
        File(program, "versions.txt").writeText("20260801-11111111\n")
        File(program, "stock.txt").writeText("20260801-11111111\n")
        val staged = File(program, ".incoming.ignored")
        assertTrue(staged.mkdirs())
        File(staged, "main.lua").writeText("partial")

        val apk = File(internal, "launcher/apk")
        assertTrue(apk.mkdirs())
        File(apk, "Compy-IDE-debug-test.apk").writeText("ide apk")
        File(apk, "toys.compy.launcher-debug-test.apk").writeText("launcher apk")
        File(apk, "Launcher.success.buildinfo").writeText("status=success\n")

        val repair = File(internal, "repair")
        assertTrue(repair.mkdirs())
        File(repair, "README.txt").writeText("repair instructions")
        File(repair, "Compy-Repair-test-linux-amd64.zip").writeText("repair bundle")
    }

    private fun assertNoIncoming(root: File) {
        assertFalse(root.walkTopDown().any { it.name.startsWith(".incoming.") })
    }

    private fun expectIOException(action: () -> Unit) {
        try {
            action()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun withRoots(test: (File, File) -> Unit) {
        val parent = Files.createTempDirectory("compy-card-initializer-test").toFile()
        val internal = File(parent, "internal")
        val card = File(parent, "card")
        assertTrue(internal.mkdirs())
        assertTrue(card.mkdirs())
        try {
            test(internal, card)
        } finally {
            parent.deleteRecursively()
        }
    }

    companion object {
        private const val OPERATION_ID = "11111111-2222-4333-8444-555555555555"
        private const val SECOND_OPERATION_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    }
}
