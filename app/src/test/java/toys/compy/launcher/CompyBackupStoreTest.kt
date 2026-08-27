package toys.compy.launcher

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompyBackupStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun captureWritesContractCopiesAndOnlyLiveProjects() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "print('alpha')", mapOf("assets/pixel.txt" to "blue"))
        fixture.writeProject("alpha.old", "stale")
        File(fixture.projects, "notes.txt").writeText("not a project")

        val result = fixture.store().createBackup(fixture.installedApks("1.2.3+abc"))

        assertEquals(1L, result.backupSet.ordinal)
        assertEquals(1, result.projectEntries)
        assertEquals(listOf(BackupSourceKind.CARD, BackupSourceKind.INTERNAL), result.destinationCopies.map { it.destination?.kind })
        result.destinationCopies.forEach { copy ->
            assertTrue(File(copy.directory, "manifest.json").isFile)
            assertTrue(File(copy.directory, "projects/alpha/main.lua").isFile)
            assertTrue(File(copy.directory, "projects/alpha/assets/pixel.txt").isFile)
            assertFalse(File(copy.directory, "projects/alpha.old").exists())
            assertFalse(File(copy.directory, "projects/notes.txt").exists())
            assertFalse(File(copy.directory, "apk").exists())
            assertFalse(File(copy.destination!!.compyDirectory, "backups/.locks/store").exists())
        }
        fixture.destinations.forEach { destination ->
            assertTrue(File(destination.compyDirectory, "backups/apk/Compy-IDE-1.2.3-abc.apk").isFile)
            assertTrue(File(destination.compyDirectory, "backups/apk/toys.compy.launcher-1.2.3-abc.apk").isFile)
        }
        assertEquals(1, fixture.store().listBackupSets().size)
    }

    @Test
    fun ordinalScansOmittedReachableDestinationsAndListingDiscoversOtherSources() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val store = fixture.store()
        val first = store.createBackup(fixture.installedApks("1")).destinationCopies
        val internalCopy = first.single { it.destination?.kind == BackupSourceKind.INTERNAL }
        val sourceTree = internalCopy.directory.parentFile!!
        val ordinalSeven = File(sourceTree, "7-19700101-000000")
        internalCopy.directory.copyRecursively(ordinalSeven)
        mutateManifest(ordinalSeven) {
            it.put("ordinal", 7)
                .put("capture_id", "00000000-0000-0000-0000-000000000777")
        }

        val foreignTree = File(fixture.internalCompy, "backups/snapshots/card/dead-beef")
        val foreign = File(foreignTree, "4-19700101-000000")
        internalCopy.directory.copyRecursively(foreign)
        mutateManifest(foreign) {
            it.put("ordinal", 4)
                .put("source_id", "fs:dead-beef")
                .put("capture_id", "00000000-0000-0000-0000-000000000444")
        }

        fixture.writeProject("alpha", "two")
        val second = store.createBackupTo(fixture.installedApks("2"), listOf(fixture.cardDestination))

        assertEquals(8L, second.backupSet.ordinal)
        assertEquals(1, second.destinationCopies.size)
        assertFalse(File(sourceTree, "8-19700101-000000").exists())
        assertTrue(store.listBackupSets().any { it.sourceId == "fs:dead-beef" && it.ordinal == 4L })
    }

    @Test
    fun exactBusyLockConfirmationDoesNotDeleteForeignStages() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val store = fixture.store()
        val foreignStage = File(fixture.cardCompy, "backups/snapshots/card/7aff-7538/.incoming.foreign")
        foreignStage.mkdirs()
        File(foreignStage, "owner.txt").writeText("host")
        val lock = File(fixture.cardCompy, "backups/.locks/store")
        lock.parentFile!!.mkdirs()
        lock.writeText("foreign owner")

        val busy = expectThrows<BackupStoreBusyException> { store.createBackup(fixture.installedApks("1")) }
        assertEquals(1, busy.busyLocks.size)
        lock.writeText("different owner")
        expectThrows<BackupStoreBusyException> {
            store.createBackup(fixture.installedApks("1"), busy.busyLocks)
        }
        assertEquals("different owner", lock.readText())

        val current = expectThrows<BackupStoreBusyException> { store.createBackup(fixture.installedApks("1")) }
        store.createBackup(fixture.installedApks("1"), current.busyLocks)
        assertTrue(foreignStage.isDirectory)
        assertEquals("host", File(foreignStage, "owner.txt").readText())
    }

    @Test
    fun lockOwnershipLossAbortsAndLeavesForeignMarker() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        fixture.inspector.beforeInspect = { count ->
            if (count == 3) {
                File(fixture.cardCompy, "backups/.locks/store").writeText("new foreign owner")
            }
        }

        expectThrows<BackupLockLostException> { fixture.store().createBackup(fixture.installedApks("1")) }

        assertEquals("new foreign owner", File(fixture.cardCompy, "backups/.locks/store").readText())
        assertTrue(
            File(fixture.cardCompy, "backups/snapshots/card/7aff-7538")
                .listFiles()
                .orEmpty()
                .none { !it.name.startsWith(".incoming.") },
        )
    }

    @Test
    fun collisionIsParkedWithoutOverwritingTheOtherCapture() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val store = fixture.store()
        val first = store.createBackup(fixture.installedApks("1"))
        fixture.writeProject("alpha", "two")
        fixture.inspector.reset { count ->
            if (count == 3) {
                val firstCard = first.destinationCopies.single { it.destination?.kind == BackupSourceKind.CARD }
                val collision = File(firstCard.directory.parentFile, "2-19700101-000000")
                firstCard.directory.copyRecursively(collision)
                mutateManifest(collision) {
                    it.put("ordinal", 2)
                        .put("capture_id", "00000000-0000-0000-0000-000000000999")
                }
            }
        }

        val second = store.createBackup(fixture.installedApks("2"))

        val cardCopy = second.destinationCopies.single { it.destination?.kind == BackupSourceKind.CARD }
        assertTrue(cardCopy.directory.name.startsWith(".recovered."))
        assertTrue(File(cardCopy.directory.parentFile, "2-19700101-000000").isDirectory)
    }

    @Test
    fun metadataReconciliationMergesPinsAndLabelsAndRequiresConflictChoice() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val store = fixture.store()
        val copies = store.createBackup(fixture.installedApks("1")).destinationCopies
        mutateManifest(copies.first().directory) { it.put("pinned", true).put("label", "Lesson A") }

        val reconciled = store.reconcileSnapshotMetadata(copies.last())

        assertEquals(2, reconciled.size)
        assertTrue(reconciled.all { it.pinned && it.label == "Lesson A" })
        mutateManifest(reconciled[0].directory) { it.put("label", "First") }
        mutateManifest(reconciled[1].directory) { it.put("label", "Second") }
        expectThrows<SnapshotLabelConflictException> { store.reconcileSnapshotMetadata(reconciled[0]) }
        val chosen = store.updateSnapshotMetadata(reconciled[0], pinned = true, label = "Chosen")
        assertTrue(chosen.all { it.pinned && it.label == "Chosen" })
        val cleared = store.updateSnapshotMetadata(chosen[0], pinned = false, label = "  ")
        assertTrue(cleared.all { !it.pinned && it.label == null })
    }

    @Test
    fun retentionPreservesPinsAndGarbageCollectsOnlyUnreferencedApks() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val store = fixture.store(retentionLimit = 3)
        val first = store.createBackup(fixture.installedApks("1")).backupSet
        store.updateSnapshotMetadata(first, pinned = true, label = null)
        (2..5).forEach { version ->
            fixture.writeProject("alpha", "version $version")
            store.createBackup(fixture.installedApks(version.toString()))
        }

        fixture.destinations.forEach { destination ->
            val sourceDirectory = File(destination.compyDirectory, "backups/snapshots/card/7aff-7538")
            val manifests = sourceDirectory.listFiles().orEmpty().filter { File(it, "manifest.json").isFile }
            assertEquals(4, manifests.size)
            assertTrue(
                manifests.any {
                    val manifest = JSONObject(File(it, "manifest.json").readText())
                    manifest.getLong("ordinal") == 1L && manifest.getBoolean("pinned")
                },
            )
            assertFalse(File(destination.compyDirectory, "backups/apk/Compy-IDE-2.apk").exists())
            assertTrue(File(destination.compyDirectory, "backups/apk/Compy-IDE-1.apk").isFile)
            assertTrue(File(destination.compyDirectory, "backups/apk/Compy-IDE-5.apk").isFile)
        }
    }

    @Test
    fun unreadableSnapshotBlocksApkGarbageCollectionAndHashTamperingBlocksListing() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val store = fixture.store()
        val first = store.createBackup(fixture.installedApks("1"))
        val staleApk = File(fixture.cardCompy, "backups/apk/unreferenced.apk")
        staleApk.writeText("keep conservatively")
        File(fixture.cardCompy, "backups/snapshots/card/7aff-7538/90-19700101-000000").mkdirs()
        fixture.writeProject("alpha", "two")
        expectThrows<IOException> { store.createBackup(fixture.installedApks("2")) }
        assertTrue(staleApk.isFile)

        val project = File(first.backupSet.directory, "projects/alpha/main.lua")
        project.writeText("tampered")
        expectThrows<IOException> { store.listBackupSets() }
    }

    @Test
    fun restorePreservesExistingTargetSupportsFreshTargetAndIsIdempotent() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "snapshot")
        val store = fixture.store()
        val backup = store.createBackup(fixture.installedApks("1")).backupSet
        fixture.writeProject("alpha", "live")

        val replaced = store.restoreProject(backup, "alpha")

        assertEquals(ProjectRestoreResult(1, 1), replaced)
        assertEquals("snapshot", File(fixture.projects, "alpha/main.lua").readText())
        assertEquals("live", File(fixture.projects, "alpha.old/main.lua").readText())
        assertEquals(ProjectRestoreResult(0, 0), store.restoreProject(backup, "alpha"))

        File(fixture.projects, "alpha").deleteRecursively()
        val fresh = store.restoreProject(backup, "alpha")
        assertEquals(ProjectRestoreResult(1, 0), fresh)
        assertEquals("snapshot", File(fixture.projects, "alpha/main.lua").readText())
        assertFalse(File(fixture.projects, "alpha.old.1").exists())
    }

    @Test
    fun restoreAllocatesPreservedTargetAfterLargestSuffixWithoutReusingGaps() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "snapshot")
        val backup = fixture.store().createBackup(fixture.installedApks("1")).backupSet
        fixture.writeProject("alpha", "live")
        File(fixture.projects, "alpha.old").mkdirs()
        File(fixture.projects, "alpha.old/main.lua").writeText("oldest")
        File(fixture.projects, "alpha.old.2").mkdirs()
        File(fixture.projects, "alpha.old.2/main.lua").writeText("newer")

        fixture.store().restoreProject(backup, "alpha")

        assertFalse(File(fixture.projects, "alpha.old.1").exists())
        assertEquals("live", File(fixture.projects, "alpha.old.3/main.lua").readText())
    }

    @Test
    fun restoreRecoveryRetriesAfterOldTargetWasPreserved() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "live")
        val target = File(fixture.projects, "alpha")
        val preserved = File(fixture.projects, "alpha.old")
        assertTrue(target.renameTo(preserved))
        val operation = "00000000-0000-0000-0000-000000000555"
        val stage = File(fixture.projects, ".incoming.$operation.alpha")
        stage.mkdirs()
        File(stage, "main.lua").writeText("snapshot")
        val contents = "snapshot".toByteArray(StandardCharsets.UTF_8)
        val journal =
            JSONObject()
                .put("format", CompyStorageContract.RESTORE_JOURNAL_FORMAT)
                .put("format_ver", CompyStorageContract.RESTORE_JOURNAL_FORMAT_VERSION)
                .put("operation_id", operation)
                .put("source_manifest_sha256", "a".repeat(64))
                .put("target", "alpha")
                .put("target_existed", true)
                .put("backup_path", "alpha.old")
                .put("staged_path", stage.name)
                .put("phase", "staged")
                .put(
                    "files",
                    JSONArray().put(
                        JSONObject()
                            .put("path", "projects/alpha/main.lua")
                            .put("size", contents.size)
                            .put("sha256", sha256(contents)),
                    ),
                )
        File(fixture.projects, ".restore.$operation.json").writeText(journal.toString())

        fixture.store().recoverPendingRestores()

        assertEquals("snapshot", File(fixture.projects, "alpha/main.lua").readText())
        assertEquals("live", File(fixture.projects, "alpha.old/main.lua").readText())
        assertFalse(stage.exists())
        assertFalse(File(fixture.projects, ".restore.$operation.json").exists())
    }

    @Test
    fun legacySnapshotIsValidatedAndCanRestore() {
        val fixture = Fixture(temporaryFolder.newFolder())
        val legacy = File(fixture.cardCompy, "backups/12")
        File(legacy, "projects/alpha").mkdirs()
        File(legacy, "projects/alpha/main.lua").writeText("legacy")
        val apks = fixture.installedApks("legacy")
        File(legacy, "apk").mkdirs()
        apks.forEach { apk -> apk.sourceApk.copyTo(File(legacy, "apk/${apk.sourceApk.name}")) }
        File(legacy, "manifest.txt").writeText(
            buildString {
                appendLine("format\t1")
                appendLine("ordinal\t12")
                apks.forEach { apk ->
                    appendLine("package\t${apk.packageName}\t${apk.versionCode}\t${apk.versionName}\t${apk.sourceApk.name}")
                }
            },
        )
        val store = fixture.store()

        val selected = store.listBackupSets().single { it.legacy }
        assertEquals(12L, selected.ordinal)
        assertNotNull(selected.manifest)
        File(fixture.projects, "alpha").deleteRecursively()
        assertEquals(ProjectRestoreResult(1, 0), store.restoreProjects(selected))
        assertEquals("legacy", File(fixture.projects, "alpha/main.lua").readText())
    }

    @Test
    fun archiveNameCollisionWithDifferentBytesFails() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val store = fixture.store()
        store.createBackup(fixture.installedApks("same", payload = "first"))
        fixture.writeProject("alpha", "two")

        expectThrows<IOException> {
            store.createBackup(fixture.installedApks("same", payload = "different"))
        }
    }

    @Test
    fun archiveNamesCollapseInvalidVersionCharacterRuns() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")

        fixture.store().createBackup(fixture.installedApks("rélease+версия"))

        fixture.destinations.forEach { destination ->
            val archive = File(destination.compyDirectory, "backups/apk")
            assertTrue(File(archive, "Compy-IDE-r-lease-.apk").isFile)
            assertTrue(File(archive, "toys.compy.launcher-r-lease-.apk").isFile)
        }
    }

    @Test
    fun freeSpacePreflightReportsEveryValueBeforeCreatingBackupState() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeProject("alpha", "one")
        val installed = fixture.installedApks("1")
        val available = 2L * 1024L * 1024L * 1024L
        val error =
            expectThrows<InsufficientBackupSpaceException> {
                fixture.store(usableSpace = { available })
                    .createBackupTo(installed, listOf(fixture.cardDestination))
            }

        assertEquals(1, error.shortfalls.size)
        val shortfall = error.shortfalls.single()
        val bytesToWrite =
            File(fixture.projects, "alpha/main.lua").length() + installed.sumOf { it.sourceApk.length() }
        assertEquals(fixture.cardDestination, shortfall.destination)
        assertEquals(available + bytesToWrite, shortfall.requiredBytes)
        assertEquals(available, shortfall.availableBytes)
        assertEquals(available - bytesToWrite, shortfall.projectedRemainingBytes)
        assertFalse(File(fixture.cardCompy, "backups").exists())
    }

    private class Fixture(
        val root: File,
    ) {
        val cardCompy = File(root, "card/Documents/compy")
        val internalCompy = File(root, "internal/Documents/compy")
        val projects = File(cardCompy, "projects")
        val cardDestination = BackupStorageEndpoint(BackupSourceKind.CARD, "fs:7aff-7538", cardCompy)
        val internalDestination =
            BackupStorageEndpoint(
                BackupSourceKind.INTERNAL,
                "f99bc7a6-0f44-4df1-884e-1f804ae40210",
                internalCompy,
            )
        val destinations = listOf(cardDestination, internalDestination)
        val inspector = TestApkInspector()
        private var uuid = 0L

        init {
            projects.mkdirs()
            internalCompy.mkdirs()
        }

        fun store(
            retentionLimit: Int = 3,
            usableSpace: (File) -> Long = { it.usableSpace },
        ): CompyBackupStore =
            CompyBackupStore(
                source = cardDestination,
                destinationEndpoints = listOf(internalDestination, cardDestination),
                apkInspector = inspector,
                clock = { Date(0) },
                uuidGenerator = { UUID(0, ++uuid) },
                usableSpace = usableSpace,
                retentionLimit = retentionLimit,
            )

        fun writeProject(name: String, main: String, otherFiles: Map<String, String> = emptyMap()) {
            val project = File(projects, name)
            project.mkdirs()
            File(project, "main.lua").writeText(main)
            otherFiles.forEach { (path, contents) ->
                File(project, path).also { it.parentFile!!.mkdirs() }.writeText(contents)
            }
        }

        fun installedApks(version: String, payload: String = version): List<InstalledApkSnapshot> =
            listOf(
                testApk(CompyStorageContract.IDE_PACKAGE, version, 41, payload),
                testApk(CompyStorageContract.LAUNCHER_PACKAGE, version, 73, payload),
            )

        private fun testApk(
            packageName: String,
            version: String,
            versionCode: Long,
            payload: String,
        ): InstalledApkSnapshot {
            val certificate = if (packageName == CompyStorageContract.IDE_PACKAGE) "1".repeat(64) else "2".repeat(64)
            val file = File(root, "installed-${packageName.substringAfterLast('.')}-${version}-${payload.hashCode()}.apk")
            file.writeText("$packageName\t$version\t$versionCode\tfalse\t$certificate\n$payload")
            return InstalledApkSnapshot(packageName, version, versionCode, file, false, certificate)
        }
    }

    private class TestApkInspector : ApkArchiveInspector {
        private var count = 0
        var beforeInspect: ((Int) -> Unit)? = null

        override fun inspect(apk: File): ApkArchiveMetadata {
            val next = ++count
            beforeInspect?.invoke(next)
            val fields = apk.readLines().first().split('\t')
            if (fields.size != 5) throw IOException("Invalid test APK")
            return ApkArchiveMetadata(
                packageName = fields[0],
                versionName = fields[1],
                versionCode = fields[2].toLong(),
                debuggable = fields[3].toBooleanStrict(),
                signingCertificateSha256 = fields[4],
            )
        }

        fun reset(callback: (Int) -> Unit) {
            count = 0
            beforeInspect = callback
        }
    }

    companion object {
        private fun mutateManifest(directory: File, mutation: (JSONObject) -> JSONObject) {
            val manifest = File(directory, "manifest.json")
            manifest.writeText(mutation(JSONObject(manifest.readText())).toString(2) + "\n")
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private inline fun <reified T : Throwable> expectThrows(action: () -> Unit): T {
            try {
                action()
            } catch (error: Throwable) {
                if (error is T) return error
                throw error
            }
            fail("Expected ${T::class.java.simpleName}")
            throw AssertionError("unreachable")
        }
    }
}
