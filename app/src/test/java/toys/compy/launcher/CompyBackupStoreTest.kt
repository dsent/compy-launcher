package toys.compy.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

// Warranted by the testing policy: backup and restore can otherwise destroy child-owned work.
class CompyBackupStoreTest {
    @Test
    fun backupUsesOrdinalsCopiesApksAndKeepsNewestThreeSets() = withFixture { fixture ->
        fixture.writeProject("paint", "version 0")
        val apks = fixture.installedApks()
        val store = CompyBackupStore(fixture.compyDirectory)

        repeat(4) { index ->
            fixture.writeProject("paint", "version ${index + 1}")
            fixture.launcherApk.writeText("launcher ${index + 1}")
            val result = store.createBackup(apks)
            assertEquals((index + 1).toLong(), result.backupSet.ordinal)
            assertEquals(1, result.projectEntries)
        }

        assertEquals(listOf(4L, 3L, 2L), store.listBackupSets().map { it.ordinal })
        assertFalse(File(fixture.backupsDirectory, "1").exists())

        val newest = File(fixture.backupsDirectory, "4")
        assertEquals("version 4", File(newest, "projects/paint/main.lua").readText())
        assertEquals("launcher 4", File(newest, "apk/toys.compy.launcher.apk").readText())
        assertEquals("ide", File(newest, "apk/toys.compy.ide.apk").readText())
        assertEquals(
            "format\t1\n" +
                "ordinal\t4\n" +
                "package\ttoys.compy.ide\t20260821\tide-version\ttoys.compy.ide.apk\n" +
                "package\ttoys.compy.launcher\t20260823\tlauncher-version\ttoys.compy.launcher.apk\n",
            File(newest, "manifest.txt").readText(),
        )
    }

    @Test
    fun incompleteDirectoriesAreIgnoredAndRemovedBeforeBackup() = withFixture { fixture ->
        val incomplete = File(fixture.backupsDirectory, ".incoming.9")
        assertTrue(incomplete.mkdirs())
        File(incomplete, "partial").writeText("partial")

        val store = CompyBackupStore(fixture.compyDirectory)
        val result = store.createBackup(fixture.installedApks())

        assertEquals(1L, result.backupSet.ordinal)
        assertFalse(incomplete.exists())
        assertEquals(listOf(1L), store.listBackupSets().map { it.ordinal })
    }

    @Test
    fun backupMissingAnInstalledApkIsNotComplete() = withFixture { fixture ->
        val incomplete = File(fixture.backupsDirectory, "7")
        assertTrue(File(incomplete, "projects").mkdirs())
        assertTrue(File(incomplete, "apk").mkdirs())
        File(incomplete, "manifest.txt").writeText("format\t1\nordinal\t7\n")
        File(incomplete, "apk/toys.compy.ide.apk").writeText("ide")

        assertTrue(CompyBackupStore(fixture.compyDirectory).listBackupSets().isEmpty())
    }

    @Test
    fun restorePreservesCollisionsAndLeavesOtherProjectsInPlace() = withFixture { fixture ->
        fixture.writeProject("paint", "backup paint")
        fixture.writeProject("maze", "backup maze")
        val store = CompyBackupStore(fixture.compyDirectory)
        val backup = store.createBackup(fixture.installedApks()).backupSet

        fixture.writeProject("paint", "current paint")
        fixture.writeProject("paint.old", "older paint")
        fixture.writeProject("new-project", "keep me")

        val result = store.restoreProjects(backup)

        assertEquals(2, result.restoredEntries)
        assertEquals(2, result.preservedEntries)
        assertEquals("backup paint", fixture.readProject("paint"))
        assertEquals("current paint", fixture.readProject("paint.old.1"))
        assertEquals("older paint", fixture.readProject("paint.old"))
        assertEquals("backup maze", fixture.readProject("maze"))
        assertEquals("keep me", fixture.readProject("new-project"))
    }

    @Test
    fun failedBackupDoesNotPruneCompleteSets() = withFixture { fixture ->
        val store = CompyBackupStore(fixture.compyDirectory)
        repeat(3) { store.createBackup(fixture.installedApks()) }
        val unreadable = fixture.installedApks().toMutableList()
        unreadable[0] = unreadable[0].copy(sourceApk = File(fixture.root, "missing.apk"))

        try {
            store.createBackup(unreadable)
            fail("Expected an unreadable installed APK to fail the backup")
        } catch (_: IOException) {
            // Expected: existing complete sets remain untouched.
        }

        assertEquals(listOf(3L, 2L, 1L), store.listBackupSets().map { it.ordinal })
    }

    @Test
    fun backupRefusesACardWithoutTheCompyProjectsLayout() {
        val root = Files.createTempDirectory("compy-backup-empty-card-test").toFile()
        try {
            val ideApk = File(root, "installed-ide.apk").apply { writeText("ide") }
            val launcherApk = File(root, "installed-launcher.apk").apply { writeText("launcher") }
            val store = CompyBackupStore(File(root, "Documents/compy"))
            try {
                store.createBackup(
                    listOf(
                        InstalledApkSnapshot("toys.compy.ide", "test", 1, ideApk),
                        InstalledApkSnapshot("toys.compy.launcher", "test", 1, launcherApk),
                    ),
                )
                fail("Expected a card without Documents/compy/projects to be rejected")
            } catch (_: IOException) {
                // Expected: backup must not silently initialize an unrecognised card.
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("compy-backup-test").toFile()
        try {
            test(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(val root: File) {
        val compyDirectory = File(root, "Documents/compy")
        val projectsDirectory = File(compyDirectory, "projects")
        val backupsDirectory = File(compyDirectory, "backups")
        val launcherApk = File(root, "installed-launcher.apk")
        private val ideApk = File(root, "installed-ide.apk")

        init {
            assertTrue(projectsDirectory.mkdirs())
            launcherApk.writeText("launcher")
            ideApk.writeText("ide")
        }

        fun installedApks(): List<InstalledApkSnapshot> {
            return listOf(
                InstalledApkSnapshot(
                    packageName = "toys.compy.ide",
                    versionName = "ide-version",
                    versionCode = 20260821,
                    sourceApk = ideApk,
                ),
                InstalledApkSnapshot(
                    packageName = "toys.compy.launcher",
                    versionName = "launcher-version",
                    versionCode = 20260823,
                    sourceApk = launcherApk,
                ),
            )
        }

        fun writeProject(name: String, contents: String) {
            val project = File(projectsDirectory, name)
            if (!project.isDirectory) assertTrue(project.mkdirs())
            File(project, "main.lua").writeText(contents)
        }

        fun readProject(name: String): String {
            return File(projectsDirectory, "$name/main.lua").readText()
        }
    }
}
