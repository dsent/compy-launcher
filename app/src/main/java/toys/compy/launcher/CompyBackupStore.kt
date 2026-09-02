/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class BackupSourceKind(val wireName: String) {
    CARD("card"),
    INTERNAL("internal"),
}

data class BackupStorageEndpoint(
    val kind: BackupSourceKind,
    val id: String,
    val compyDirectory: File,
)

data class ApkArchiveMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val debuggable: Boolean,
    val signingCertificateSha256: String,
)

fun interface ApkArchiveInspector {
    @Throws(IOException::class)
    fun inspect(apk: File): ApkArchiveMetadata
}

data class InstalledApkSnapshot(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sourceApk: File,
    val debuggable: Boolean = false,
    val signingCertificateSha256: String = "",
) {
    fun metadata(): ApkArchiveMetadata =
        ApkArchiveMetadata(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            debuggable = debuggable,
            signingCertificateSha256 = signingCertificateSha256,
        )
}

data class SnapshotFileEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

data class SnapshotApkEntry(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val debuggable: Boolean,
    val signingCertificateSha256: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
) {
    fun metadata(): ApkArchiveMetadata =
        ApkArchiveMetadata(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            debuggable = debuggable,
            signingCertificateSha256 = signingCertificateSha256,
        )
}

data class SnapshotManifest(
    val sourceKind: BackupSourceKind,
    val sourceId: String,
    val captureId: String,
    val ordinal: Long,
    val pinned: Boolean,
    val label: String?,
    val createdAt: String,
    val files: List<SnapshotFileEntry>,
    val apks: List<SnapshotApkEntry>,
)

data class CompyBackupSet(
    val ordinal: Long,
    val directory: File,
    val sourceKind: BackupSourceKind = BackupSourceKind.CARD,
    val sourceId: String = "",
    val captureId: String? = null,
    val createdAt: String? = null,
    val pinned: Boolean = false,
    val label: String? = null,
    val destination: BackupStorageEndpoint? = null,
    val manifest: SnapshotManifest? = null,
    val manifestSha256: String = "",
    val legacy: Boolean = false,
    val restorable: Boolean = true,
    val problem: String? = null,
)

data class BackupCreateResult(
    val backupSet: CompyBackupSet,
    val projectEntries: Int,
    val retainedSets: Int,
    val destinationCopies: List<CompyBackupSet> = listOf(backupSet),
    val cleanupWarnings: List<String> = emptyList(),
)

data class ProjectRestoreResult(
    val restoredEntries: Int,
    val preservedEntries: Int,
)

data class ObservedBackupLock(
    val destination: BackupStorageEndpoint,
    val operationId: String?,
    val sourceKind: String?,
    val sourceId: String?,
    val writer: String?,
    val markerSha256: String,
)

data class BackupSpaceShortfall(
    val destination: BackupStorageEndpoint,
    val requiredBytes: Long,
    val availableBytes: Long,
    val projectedRemainingBytes: Long,
)

class InsufficientBackupSpaceException(
    val shortfalls: List<BackupSpaceShortfall>,
) : IOException(
    "Insufficient backup space on: " +
        shortfalls.joinToString { it.destination.compyDirectory.path },
)

class BackupStoreBusyException(
    val busyLocks: List<ObservedBackupLock>,
) : IOException(
    "Backup store is busy on: " +
        busyLocks.joinToString { it.destination.compyDirectory.path },
)

class BackupLockLostException(destination: BackupStorageEndpoint) :
    IOException("Backup lock ownership was lost on ${destination.compyDirectory}")

class SnapshotLabelConflictException(labels: Set<String>) :
    IOException("Snapshot copies have conflicting labels: ${labels.sorted().joinToString()}")

/**
 * Contract-v1 snapshot storage. Android-specific volume and package inspection
 * stay outside this class so destructive behavior remains hermetically testable.
 */
class CompyBackupStore(
    private val source: BackupStorageEndpoint,
    destinationEndpoints: List<BackupStorageEndpoint>,
    private val apkInspector: ApkArchiveInspector,
    private val clock: () -> Date = { Date() },
    private val uuidGenerator: () -> UUID = { UUID.randomUUID() },
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val retentionLimit: Int = DEFAULT_RETENTION_LIMIT,
) {
    private val destinations =
        destinationEndpoints.sortedWith(
            compareBy<BackupStorageEndpoint> {
                CompyStorageContract.DESTINATION_ORDER.indexOf(it.kind.wireName)
            }.thenBy(::endpointKey),
        )
    private val projectsDirectory = File(source.compyDirectory, PROJECTS_DIRECTORY_NAME)

    init {
        require(retentionLimit > 0) { "retentionLimit must be positive" }
        require(destinations.isNotEmpty()) { "At least one backup destination is required" }
        require(destinations.map(::endpointKey).distinct().size == destinations.size) {
            "Backup destination directories must be unique"
        }
        validateEndpoint(source)
        destinations.forEach(::validateEndpoint)
    }

    @Synchronized
    fun listBackupSets(): List<CompyBackupSet> {
        val seenCaptures = mutableSetOf<String>()
        return listAllBackupCopies(verifyContents = true).filter { snapshot ->
            snapshot.legacy || snapshot.captureId == null || seenCaptures.add(snapshot.captureId)
        }
    }

    private fun listAllBackupCopies(verifyContents: Boolean): List<CompyBackupSet> {
        val snapshots = mutableListOf<CompyBackupSet>()
        destinations.forEach { destination ->
            snapshots += listContractSnapshots(destination, verifyContents)
            snapshots += listLegacySnapshots(destination)
        }
        return snapshots.sortedWith(
            compareByDescending<CompyBackupSet> { it.ordinal }
                .thenBy { it.destination?.let(::destinationOrder) ?: Int.MAX_VALUE }
                .thenBy { it.destination?.let(::endpointKey) ?: "" }
                .thenBy { it.captureId ?: "" },
        )
    }

    @Synchronized
    fun createBackup(installedApks: List<InstalledApkSnapshot>): BackupCreateResult =
        createBackupTo(installedApks, destinations, confirmedBusyLocks = emptyList())

    /** Replaces only the exact lock markers previously shown to the caller. */
    @Synchronized
    fun createBackup(
        installedApks: List<InstalledApkSnapshot>,
        confirmedBusyLocks: List<ObservedBackupLock>,
    ): BackupCreateResult = createBackupTo(installedApks, destinations, confirmedBusyLocks)

    /**
     * [destinations] always contains every readable reachable store so ordinal
     * allocation and locking see omitted destinations. This method selects the
     * subset that receives the new snapshot.
     */
    @Synchronized
    fun createBackupTo(
        installedApks: List<InstalledApkSnapshot>,
        selectedDestinations: List<BackupStorageEndpoint>,
        confirmedBusyLocks: List<ObservedBackupLock> = emptyList(),
    ): BackupCreateResult {
        if (!source.compyDirectory.isDirectory || !projectsDirectory.isDirectory) {
            throw IOException("The source does not contain Documents/compy/projects")
        }
        val selectedKeys = selectedDestinations.map(::endpointKey)
        require(selectedKeys.isNotEmpty()) { "At least one destination must be selected" }
        require(selectedKeys.distinct().size == selectedKeys.size) { "Selected destinations must be unique" }
        val captureDestinations =
            destinations.filter { endpointKey(it) in selectedKeys }.also { selected ->
                require(selected.size == selectedKeys.size) {
                    "Every selected destination must be in the reachable destination set"
                }
            }

        val sourceFiles = captureSourceFiles()
        val apkEntries = inspectInstalledApks(installedApks)
        preflightAvailableSpace(captureDestinations, sourceFiles, apkEntries)
        val operationId = canonicalUuid(uuidGenerator())
        val captureId = canonicalUuid(uuidGenerator())
        val ownedLocks = acquireLocks(operationId, confirmedBusyLocks)
        val ownedStages = mutableListOf<File>()

        try {
            val ordinal = allocateOrdinal()
            val captureTime = clock()
            val directoryTimestamp = formatDirectoryTimestamp(captureTime)
            val createdAt = formatCreatedAt(captureTime)
            val manifest =
                SnapshotManifest(
                    sourceKind = source.kind,
                    sourceId = source.id,
                    captureId = captureId,
                    ordinal = ordinal,
                    pinned = false,
                    label = null,
                    createdAt = createdAt,
                    files = sourceFiles.map { it.entry },
                    apks = apkEntries.map { it.entry },
                )
            val manifestBytes = encodeManifest(manifest)
            val completedCopies = mutableListOf<CompyBackupSet>()

            captureDestinations.forEach { destination ->
                ensureApkArchive(destination, operationId, apkEntries, ownedLocks, ownedStages)

                val sourceSnapshots = sourceSnapshotsDirectory(destination)
                ensureDirectory(sourceSnapshots)
                val finalName = "$ordinal-$directoryTimestamp"
                val staging = File(sourceSnapshots, "$INCOMING_PREFIX$finalName")
                if (staging.exists()) {
                    throw IOException("Foreign or interrupted snapshot stage already exists: $staging")
                }
                ensureDirectory(staging)
                ownedStages += staging

                sourceFiles.forEach { sourceFile ->
                    val target = resolveRelativeFile(staging, sourceFile.entry.path)
                    copyFileDurably(sourceFile.file, target)
                    verifyFile(target, sourceFile.entry.size, sourceFile.entry.sha256)
                }
                writeBytesDurably(File(staging, MANIFEST_FILE_NAME), manifestBytes)
                val promoted =
                    promoteSnapshot(
                        staging,
                        File(sourceSnapshots, finalName),
                        manifest,
                        ownedLocks,
                    )
                ownedStages.remove(staging)
                completedCopies +=
                    CompyBackupSet(
                        ordinal = ordinal,
                        directory = promoted,
                        sourceKind = source.kind,
                        sourceId = source.id,
                        captureId = captureId,
                        createdAt = createdAt,
                        pinned = false,
                        destination = destination,
                        manifest = manifest,
                        manifestSha256 = sha256(manifestBytes),
                    )
            }

            val reconciledCopies = reconcileCaptureMetadata(captureId, operationId, ownedLocks)
            completedCopies.clear()
            completedCopies +=
                reconciledCopies.filter { copy ->
                    captureDestinations.any { endpoint ->
                        copy.destination?.let(::endpointKey) == endpointKey(endpoint)
                    }
                }

            val cleanupWarnings = mutableListOf<String>()
            destinations.forEach { destination ->
                try {
                    pruneSnapshots(destination, ownedLocks)
                    cleanupApkArchive(destination, ownedLocks)
                } catch (error: BackupLockLostException) {
                    throw error
                } catch (error: Exception) {
                    cleanupWarnings +=
                        "${destination.compyDirectory}: ${error.message ?: error.javaClass.simpleName}"
                }
            }

            val retainedCount =
                destinations.sumOf { destination ->
                    countRetainedSnapshots(destination)
                }
            return BackupCreateResult(
                backupSet = completedCopies.first(),
                projectEntries = sourceFiles.map(::topLevelProjectName).distinct().size,
                retainedSets = retainedCount,
                destinationCopies = completedCopies,
                cleanupWarnings = cleanupWarnings,
            )
        } finally {
            ownedStages.asReversed().forEach { stage ->
                if (stage.exists()) deleteTreeChecked(stage)
            }
            releaseLocks(ownedLocks)
        }
    }

    @Synchronized
    fun restoreProjects(backupSet: CompyBackupSet): ProjectRestoreResult {
        recoverPendingRestores()
        val selected = resolveBackupSet(backupSet)
        val projectNames = selectedProjectNames(selected)
        var restored = 0
        var preserved = 0
        projectNames.forEach { projectName ->
            val result = restoreProjectInternal(selected, projectName)
            restored += result.restoredEntries
            preserved += result.preservedEntries
        }
        return ProjectRestoreResult(restored, preserved)
    }

    @Synchronized
    fun restoreProject(backupSet: CompyBackupSet, projectName: String): ProjectRestoreResult {
        recoverPendingRestores()
        return restoreProjectInternal(resolveBackupSet(backupSet), projectName)
    }

    @Synchronized
    fun recoverPendingRestores() {
        if (!projectsDirectory.exists()) return
        listDirectory(projectsDirectory)
            .filter { it.isFile && RESTORE_JOURNAL_PATTERN.matches(it.name) }
            .sortedBy { it.name }
            .forEach(::recoverRestoreJournal)
    }

    @Synchronized
    fun updateSnapshotMetadata(
        backupSet: CompyBackupSet,
        pinned: Boolean,
        label: String?,
        confirmedBusyLocks: List<ObservedBackupLock> = emptyList(),
    ): List<CompyBackupSet> {
        val selected = resolveBackupSet(backupSet)
        if (selected.legacy) throw IOException("Legacy snapshots do not carry pin or label metadata")
        if (selected.sourceKind != source.kind || selected.sourceId != source.id) {
            throw IOException("Snapshot metadata updates require a store for the snapshot source")
        }
        val normalizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        val operationId = canonicalUuid(uuidGenerator())
        val ownedLocks = acquireLocks(operationId, confirmedBusyLocks)
        try {
            val copies = snapshotsForCapture(selected.captureId ?: throw IOException("Snapshot has no capture ID"))
            requireImmutableAgreement(copies)
            copies.forEach { copy ->
                replaceManifestMetadata(copy, pinned, normalizedLabel, operationId, ownedLocks)
            }
            return snapshotsForCapture(selected.captureId)
        } finally {
            releaseLocks(ownedLocks)
        }
    }

    /**
     * Reconciles mutable metadata on every reachable copy of one capture.
     * Pins merge conservatively, and a single non-empty label propagates. A
     * caller must resolve conflicting non-empty labels explicitly through
     * [updateSnapshotMetadata].
     */
    @Synchronized
    fun reconcileSnapshotMetadata(
        backupSet: CompyBackupSet,
        confirmedBusyLocks: List<ObservedBackupLock> = emptyList(),
    ): List<CompyBackupSet> {
        val selected = resolveBackupSet(backupSet)
        if (selected.legacy) throw IOException("Legacy snapshots do not carry pin or label metadata")
        if (selected.sourceKind != source.kind || selected.sourceId != source.id) {
            throw IOException("Snapshot metadata reconciliation requires a store for the snapshot source")
        }
        val operationId = canonicalUuid(uuidGenerator())
        val ownedLocks = acquireLocks(operationId, confirmedBusyLocks)
        return try {
            reconcileCaptureMetadata(
                selected.captureId ?: throw IOException("Snapshot has no capture ID"),
                operationId,
                ownedLocks,
            )
        } finally {
            releaseLocks(ownedLocks)
        }
    }

    private fun restoreProjectInternal(
        backupSet: CompyBackupSet,
        projectName: String,
    ): ProjectRestoreResult {
        validateProjectName(projectName)
        ensureDirectory(projectsDirectory)

        val sourceProject = File(File(backupSet.directory, PROJECTS_DIRECTORY_NAME), projectName)
        if (!sourceProject.exists()) throw IOException("Snapshot has no project entry: $projectName")
        val manifest = backupSet.manifest ?: throw IOException("Snapshot manifest is unavailable")
        val expected = entriesForProject(manifest.files, projectName)

        val target = File(projectsDirectory, projectName)
        if (verifyProjectItem(target, projectName, expected)) {
            return ProjectRestoreResult(restoredEntries = 0, preservedEntries = 0)
        }

        val operationId = canonicalUuid(uuidGenerator())
        val staging = File(projectsDirectory, "$RESTORE_INCOMING_PREFIX$operationId.$projectName")
        val journal = File(projectsDirectory, "$RESTORE_JOURNAL_PREFIX$operationId.json")
        if (staging.exists() || journal.exists()) {
            throw IOException("Restore operation path already exists: $operationId")
        }
        copyEntry(sourceProject, staging)
        if (!verifyProjectItem(staging, projectName, expected)) {
            deleteTreeChecked(staging)
            throw IOException("Staged project verification failed: $projectName")
        }

        val targetExisted = target.exists()
        val backup = if (targetExisted) nextOldPath(target) else null
        val state =
            RestoreJournal(
                operationId = operationId,
                sourceManifestSha256 = backupSet.manifestSha256,
                targetName = projectName,
                targetExisted = targetExisted,
                backupName = backup?.name,
                stagedName = staging.name,
                phase = RestorePhase.STAGED,
                expectedFiles = expected,
            )
        writeRestoreJournal(journal, state)
        completeRestore(journal, state)
        return ProjectRestoreResult(
            restoredEntries = 1,
            preservedEntries = if (targetExisted) 1 else 0,
        )
    }

    private fun recoverRestoreJournal(journalFile: File) {
        val state = decodeRestoreJournal(readBytes(journalFile))
        if (journalFile.name != "$RESTORE_JOURNAL_PREFIX${state.operationId}.json") {
            throw IOException("Restore journal name does not match its operation: $journalFile")
        }
        completeRestore(journalFile, state)
    }

    private fun completeRestore(journalFile: File, initialState: RestoreJournal) {
        var state = initialState
        val target = File(projectsDirectory, state.targetName)
        val staging = checkedOperationPath(state.stagedName, RESTORE_INCOMING_PREFIX)
        val backup = state.backupName?.let { checkedBackupPath(state.targetName, it) }

        if (state.phase == RestorePhase.STAGED) {
            val stageValid = verifyProjectItem(staging, state.targetName, state.expectedFiles)
            if (!stageValid) {
                recoverInvalidStage(state, target, backup)
                if (staging.exists()) deleteTreeChecked(staging)
                if (!journalFile.delete()) throw IOException("Could not remove failed restore journal: $journalFile")
                return
            }

            if (state.targetExisted) {
                when {
                    target.exists() && backup != null && !backup.exists() -> {
                        if (!target.renameTo(backup)) {
                            throw IOException("Could not preserve ${target.name} as ${backup.name}")
                        }
                    }
                    !target.exists() && backup != null && backup.exists() -> Unit
                    else -> throw IOException("Restore requires repair at staged: ${state.targetName}")
                }
            } else if (target.exists()) {
                throw IOException("Restore requires repair: fresh target appeared: ${state.targetName}")
            }
            state = state.copy(phase = RestorePhase.OLD_PRESERVED)
            writeRestoreJournal(journalFile, state)
        }

        if (state.phase == RestorePhase.OLD_PRESERVED) {
            if (!verifyProjectItem(target, state.targetName, state.expectedFiles)) {
                if (!verifyProjectItem(staging, state.targetName, state.expectedFiles)) {
                    rollbackRestore(state, target, backup)
                    if (journalFile.exists() && !journalFile.delete()) {
                        throw IOException("Could not remove failed restore journal: $journalFile")
                    }
                    throw IOException("Restore stage and target are invalid: ${state.targetName}")
                }
                if (target.exists()) deleteTreeChecked(target)
                if (!staging.renameTo(target)) {
                    rollbackRestore(state, target, backup)
                    throw IOException("Could not promote restored project: ${state.targetName}")
                }
            }
            state = state.copy(phase = RestorePhase.PROMOTED)
            writeRestoreJournal(journalFile, state)
        }

        if (!verifyProjectItem(target, state.targetName, state.expectedFiles)) {
            rollbackRestore(state, target, backup)
            if (journalFile.exists() && !journalFile.delete()) {
                throw IOException("Could not remove failed restore journal: $journalFile")
            }
            throw IOException("Promoted project verification failed: ${state.targetName}")
        }

        if (staging.exists()) deleteTreeChecked(staging)
        if (!journalFile.delete()) throw IOException("Could not remove restore journal: $journalFile")
    }

    private fun recoverInvalidStage(
        state: RestoreJournal,
        target: File,
        backup: File?,
    ) {
        if (state.targetExisted) {
            when {
                target.exists() && (backup == null || !backup.exists()) -> Unit
                !target.exists() && backup != null && backup.exists() -> {
                    if (!backup.renameTo(target)) {
                        throw IOException("Could not restore preserved target: ${state.targetName}")
                    }
                }
                else -> throw IOException("Restore requires repair after invalid stage: ${state.targetName}")
            }
        } else if (target.exists()) {
            throw IOException("Restore requires repair after invalid fresh stage: ${state.targetName}")
        }
    }

    private fun rollbackRestore(
        state: RestoreJournal,
        target: File,
        backup: File?,
    ) {
        if (target.exists()) deleteTreeChecked(target)
        if (state.targetExisted) {
            if (backup == null || !backup.exists() || !backup.renameTo(target)) {
                throw IOException("Could not roll back project restore: ${state.targetName}")
            }
        }
    }

    private fun acquireLocks(
        operationId: String,
        confirmedBusyLocks: List<ObservedBackupLock>,
    ): List<OwnedBackupLock> {
        val confirmed = confirmedBusyLocks.associateBy { endpointKey(it.destination) }
        val owned = mutableListOf<OwnedBackupLock>()
        try {
            destinations.forEach { destination ->
                val lockDirectory = File(backupsDirectory(destination), LOCKS_DIRECTORY_NAME)
                ensureDirectory(lockDirectory)
                val marker = File(lockDirectory, STORE_LOCK_FILE_NAME)
                val contents = encodeLock(operationId)
                val expected = confirmed[endpointKey(destination)]

                if (marker.createNewFile()) {
                    try {
                        writeBytesToExistingFileDurably(marker, contents)
                    } catch (error: Exception) {
                        marker.delete()
                        throw error
                    }
                } else if (expected != null && observedLock(destination, marker).markerSha256 == expected.markerSha256) {
                    val replacement = File(lockDirectory, "$INCOMING_PREFIX$operationId.lock")
                    if (replacement.exists()) {
                        throw IOException("Lock replacement stage already exists: $replacement")
                    }
                    writeBytesDurably(replacement, contents)
                    val current = observedLock(destination, marker)
                    if (current.markerSha256 != expected.markerSha256) {
                        replacement.delete()
                        throw BackupStoreBusyException(listOf(current))
                    }
                    if (!replacement.renameTo(marker)) {
                        replacement.delete()
                        throw IOException("Could not replace confirmed backup lock: $marker")
                    }
                } else {
                    throw BackupStoreBusyException(observeBusyLocks(owned))
                }
                owned += OwnedBackupLock(destination, marker, sha256(contents))
            }
            return owned
        } catch (error: Exception) {
            releaseLocks(owned)
            throw error
        }
    }

    private fun observeBusyLocks(ownedLocks: List<OwnedBackupLock> = emptyList()): List<ObservedBackupLock> =
        destinations.mapNotNull { destination ->
            val marker = lockFile(destination)
            if (marker.isFile) observedLock(destination, marker) else null
        }.filterNot { observed ->
            ownedLocks.any { owned ->
                endpointKey(owned.destination) == endpointKey(observed.destination) &&
                    owned.contentsSha256 == observed.markerSha256
            }
        }

    private fun observedLock(destination: BackupStorageEndpoint, marker: File): ObservedBackupLock {
        val bytes = readBytes(marker)
        var operationId: String? = null
        var sourceKind: String? = null
        var sourceId: String? = null
        var writer: String? = null
        try {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            operationId = json.optString("operation_id").takeIf { it.isNotEmpty() }
            sourceKind = json.optString("source_kind").takeIf { it.isNotEmpty() }
            sourceId = json.optString("source_id").takeIf { it.isNotEmpty() }
            writer = json.optString("writer").takeIf { it.isNotEmpty() }
        } catch (_: JSONException) {
            // An unreadable marker is still a lock and is breakable by exact fingerprint.
        }
        return ObservedBackupLock(
            destination = destination,
            operationId = operationId,
            sourceKind = sourceKind,
            sourceId = sourceId,
            writer = writer,
            markerSha256 = sha256(bytes),
        )
    }

    private fun assertOwnsAll(ownedLocks: List<OwnedBackupLock>) {
        ownedLocks.forEach { lock ->
            if (!lock.file.isFile || sha256(readBytes(lock.file)) != lock.contentsSha256) {
                throw BackupLockLostException(lock.destination)
            }
        }
    }

    private fun releaseLocks(ownedLocks: List<OwnedBackupLock>) {
        ownedLocks.asReversed().forEach { lock ->
            if (lock.file.isFile && sha256(readBytes(lock.file)) == lock.contentsSha256) {
                if (!lock.file.delete()) throw IOException("Could not release backup lock: ${lock.file}")
            }
        }
    }

    private fun encodeLock(operationId: String): ByteArray {
        val json =
            JSONObject()
                .put("format", CompyStorageContract.BACKUP_LOCK_FORMAT)
                .put("format_ver", CompyStorageContract.BACKUP_LOCK_FORMAT_VERSION)
                .put("operation_id", operationId)
                .put("source_kind", source.kind.wireName)
                .put("source_id", source.id)
                .put("writer", WRITER_LAUNCHER)
        return (json.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun allocateOrdinal(): Long {
        var maximum = 0L
        destinations.forEach { destination ->
            val sourceDirectory = sourceSnapshotsDirectory(destination)
            if (sourceDirectory.exists()) {
                listDirectory(sourceDirectory).forEach { candidate ->
                    val ordinal =
                        when {
                            FINAL_SNAPSHOT_DIRECTORY_PATTERN.matches(candidate.name) ->
                                candidate.name.substringBefore('-').toLongOrNull()
                            RECOVERED_SNAPSHOT_DIRECTORY_PATTERN.matches(candidate.name) ->
                                readManifestOrdinalOrNull(File(candidate, MANIFEST_FILE_NAME))
                            else -> null
                        }
                    if (ordinal != null && ordinal > maximum) maximum = ordinal
                }
            }
            if (
                source.kind == BackupSourceKind.CARD &&
                destination.kind == BackupSourceKind.CARD &&
                destination.id == source.id
            ) {
                val backups = backupsDirectory(destination)
                if (backups.exists()) {
                    listDirectory(backups).forEach { legacy ->
                        val ordinal = legacy.name.toLongOrNull()?.takeIf { it > 0L }
                        if (ordinal != null && ordinal > maximum) maximum = ordinal
                    }
                }
            }
        }
        if (maximum == Long.MAX_VALUE) throw IOException("Snapshot ordinal overflow")
        return maximum + 1L
    }

    private fun ensureApkArchive(
        destination: BackupStorageEndpoint,
        operationId: String,
        apks: List<CapturedApk>,
        ownedLocks: List<OwnedBackupLock>,
        ownedStages: MutableList<File>,
    ) {
        val archive = File(backupsDirectory(destination), APK_DIRECTORY_NAME)
        ensureDirectory(archive)
        apks.forEach { apk ->
            val final = File(archive, apk.entry.fileName)
            if (final.exists()) {
                verifyArchivedApk(final, apk.entry)
                return@forEach
            }
            val staging = File(archive, "$INCOMING_PREFIX$operationId.${apk.entry.fileName}")
            if (staging.exists()) throw IOException("Foreign APK stage already exists: $staging")
            copyFileDurably(apk.source, staging)
            ownedStages += staging
            verifyArchivedApk(staging, apk.entry)
            if (final.exists()) {
                verifyArchivedApk(final, apk.entry)
                deleteTreeChecked(staging)
            } else {
                assertOwnsAll(ownedLocks)
                if (!staging.renameTo(final)) {
                    throw IOException("Could not promote APK archive entry: $final")
                }
            }
            ownedStages.remove(staging)
        }
    }

    private fun promoteSnapshot(
        staging: File,
        final: File,
        manifest: SnapshotManifest,
        ownedLocks: List<OwnedBackupLock>,
    ): File {
        if (!final.exists()) {
            assertOwnsAll(ownedLocks)
            if (!staging.renameTo(final)) throw IOException("Could not finalize snapshot ${manifest.ordinal}")
            return final
        }

        val sameCapture =
            try {
                decodeManifest(readBytes(File(final, MANIFEST_FILE_NAME))).captureId == manifest.captureId
            } catch (_: Exception) {
                false
            }
        if (sameCapture) {
            val existing = readContractSnapshot(final, destination = null, verifyContents = true)
            if (!immutableEquivalent(existing.manifest, manifest)) {
                throw IOException("Snapshot capture ${manifest.captureId} has conflicting immutable content")
            }
            deleteTreeChecked(staging)
            return final
        }

        val recovered = File(final.parentFile, "$RECOVERED_PREFIX${manifest.captureId}")
        if (recovered.exists()) {
            val existing = readContractSnapshot(recovered, destination = null, verifyContents = true)
            if (!immutableEquivalent(existing.manifest, manifest)) {
                throw IOException("Recovered snapshot name has conflicting content: $recovered")
            }
            deleteTreeChecked(staging)
            return recovered
        }
        assertOwnsAll(ownedLocks)
        if (!staging.renameTo(recovered)) throw IOException("Could not park collided snapshot: $recovered")
        return recovered
    }

    private fun reconcileCaptureMetadata(
        captureId: String,
        operationId: String,
        ownedLocks: List<OwnedBackupLock>,
    ): List<CompyBackupSet> {
        val copies = snapshotsForCapture(captureId)
        requireImmutableAgreement(copies)
        val pinned = copies.any { it.pinned }
        val labels = copies.mapNotNull { it.label?.takeIf(String::isNotEmpty) }.toSet()
        if (labels.size > 1) throw SnapshotLabelConflictException(labels)
        val label = labels.singleOrNull()
        copies.filter { it.pinned != pinned || it.label != label }.forEach { copy ->
            replaceManifestMetadata(copy, pinned, label, operationId, ownedLocks)
        }
        return snapshotsForCapture(captureId)
    }

    private fun snapshotsForCapture(captureId: String): List<CompyBackupSet> =
        destinations.flatMap { listContractSnapshots(it, verifyContents = false) }
            .filter { it.captureId == captureId }

    private fun requireImmutableAgreement(copies: List<CompyBackupSet>) {
        if (copies.isEmpty()) throw IOException("No snapshot copies found")
        val first = copies.first().manifest ?: throw IOException("Snapshot manifest is unavailable")
        copies.drop(1).forEach { copy ->
            if (!immutableEquivalent(first, copy.manifest)) {
                throw IOException("Snapshot copies disagree on immutable capture content")
            }
        }
    }

    private fun immutableEquivalent(first: SnapshotManifest?, second: SnapshotManifest?): Boolean =
        first != null &&
            second != null &&
            first.copy(pinned = false, label = null) == second.copy(pinned = false, label = null)

    private fun replaceManifestMetadata(
        snapshot: CompyBackupSet,
        pinned: Boolean,
        label: String?,
        operationId: String,
        ownedLocks: List<OwnedBackupLock>,
    ) {
        val manifestFile = File(snapshot.directory, MANIFEST_FILE_NAME)
        val original = decodeManifest(readBytes(manifestFile))
        val json =
            try {
                JSONObject(String(readBytes(manifestFile), StandardCharsets.UTF_8))
            } catch (error: JSONException) {
                throw IOException("Invalid snapshot manifest JSON: $manifestFile", error)
            }
        json.put("pinned", pinned)
        if (label == null) json.remove("label") else json.put("label", label)
        val replacement = File(snapshot.directory, "$INCOMING_PREFIX$operationId.manifest.json")
        if (replacement.exists()) throw IOException("Foreign manifest stage already exists: $replacement")
        try {
            writeBytesDurably(
                replacement,
                (json.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8),
            )
            val updated = decodeManifest(readBytes(replacement))
            if (
                !immutableEquivalent(original, updated) ||
                updated.pinned != pinned ||
                updated.label != label
            ) throw IOException("Snapshot metadata replacement changed immutable content")
            assertOwnsAll(ownedLocks)
            if (!replacement.renameTo(manifestFile)) {
                throw IOException("Could not replace snapshot manifest: $manifestFile")
            }
        } finally {
            if (replacement.exists()) deleteTreeChecked(replacement)
        }
    }

    private fun pruneSnapshots(
        destination: BackupStorageEndpoint,
        ownedLocks: List<OwnedBackupLock>,
    ) {
        val sourceDirectory = sourceSnapshotsDirectory(destination)
        if (!sourceDirectory.exists()) return
        val readableUnpinned = mutableListOf<CompyBackupSet>()
        listDirectory(sourceDirectory).forEach { directory ->
            if (!FINAL_SNAPSHOT_DIRECTORY_PATTERN.matches(directory.name)) return@forEach
            try {
                val snapshot = readContractSnapshot(directory, destination, verifyContents = false)
                if (!snapshot.pinned) readableUnpinned += snapshot
            } catch (_: Exception) {
                // Unreadable snapshots are retained for explicit repair.
            }
        }
        readableUnpinned.sortedByDescending { it.ordinal }.drop(retentionLimit).forEach { snapshot ->
            assertOwnsAll(ownedLocks)
            deleteTreeChecked(snapshot.directory)
        }
    }

    private fun cleanupApkArchive(
        destination: BackupStorageEndpoint,
        ownedLocks: List<OwnedBackupLock>,
    ) {
        val archive = File(backupsDirectory(destination), APK_DIRECTORY_NAME)
        if (!archive.isDirectory) return
        val referenced = mutableSetOf<String>()
        val snapshotsRoot = File(backupsDirectory(destination), SNAPSHOTS_DIRECTORY_NAME)
        if (snapshotsRoot.exists()) {
            listDirectory(snapshotsRoot).forEach { kindDirectory ->
                listDirectory(kindDirectory).forEach { idDirectory ->
                    listDirectory(idDirectory).forEach snapshotLoop@{ snapshot ->
                        if (
                            !FINAL_SNAPSHOT_DIRECTORY_PATTERN.matches(snapshot.name) &&
                            !RECOVERED_SNAPSHOT_DIRECTORY_PATTERN.matches(snapshot.name)
                        ) return@snapshotLoop
                        val manifest =
                            try {
                                decodeManifest(readBytes(File(snapshot, MANIFEST_FILE_NAME)))
                            } catch (error: Exception) {
                                throw IOException("Retained snapshot manifest is unreadable: $snapshot", error)
                            }
                        manifest.apks.forEach { referenced += it.fileName }
                    }
                }
            }
        }
        listDirectory(archive)
            .filter { it.isFile && it.name.endsWith(".apk") && !it.name.startsWith(INCOMING_PREFIX) }
            .filterNot { it.name in referenced }
            .forEach { apk ->
                assertOwnsAll(ownedLocks)
                if (!apk.delete()) throw IOException("Could not remove unreferenced APK: $apk")
            }
    }

    private fun listContractSnapshots(
        destination: BackupStorageEndpoint,
        verifyContents: Boolean,
    ): List<CompyBackupSet> {
        val snapshotsRoot = File(backupsDirectory(destination), SNAPSHOTS_DIRECTORY_NAME)
        if (!snapshotsRoot.exists()) return emptyList()
        val kindDirectories = listDirectory(snapshotsRoot)
        return kindDirectories.flatMap kindLoop@{ kindDirectory ->
            val sourceKind = BackupSourceKind.entries.firstOrNull { it.wireName == kindDirectory.name }
            if (sourceKind == null) {
                return@kindLoop emptyList()
            }
            val idDirectories =
                try {
                    listDirectory(kindDirectory)
                } catch (error: Exception) {
                    return@kindLoop listOf(
                        unreadableSnapshot(kindDirectory, destination, sourceKind, "", error),
                    )
                }
            idDirectories.flatMap idLoop@{ idDirectory ->
                val snapshotDirectories =
                    try {
                        listDirectory(idDirectory)
                    } catch (error: Exception) {
                        return@idLoop listOf(
                            unreadableSnapshot(idDirectory, destination, sourceKind, idDirectory.name, error),
                        )
                    }
                snapshotDirectories.filter {
                    FINAL_SNAPSHOT_DIRECTORY_PATTERN.matches(it.name) ||
                        RECOVERED_SNAPSHOT_DIRECTORY_PATTERN.matches(it.name)
                }.map { snapshot ->
                    try {
                        readContractSnapshot(snapshot, destination, verifyContents)
                    } catch (error: Exception) {
                        unreadableSnapshot(snapshot, destination, sourceKind, idDirectory.name, error)
                    }
                }
            }
        }
    }

    private fun unreadableSnapshot(
        directory: File,
        destination: BackupStorageEndpoint,
        sourceKind: BackupSourceKind,
        bareSourceId: String,
        error: Exception,
    ): CompyBackupSet {
        val sourceId =
            if (sourceKind == BackupSourceKind.CARD) {
                CompyStorageContract.CARD_ID_PREFIX + bareSourceId
            } else {
                bareSourceId
            }
        return CompyBackupSet(
            ordinal = directory.name.substringBefore('-').toLongOrNull() ?: 0L,
            directory = directory,
            sourceKind = sourceKind,
            sourceId = sourceId,
            destination = destination,
            restorable = false,
            problem = error.message ?: "Snapshot is unreadable",
        )
    }

    private fun countRetainedSnapshots(destination: BackupStorageEndpoint): Int {
        val contractCount =
            sourceSnapshotsDirectory(destination).listFiles()?.count {
                it.isDirectory &&
                    (FINAL_SNAPSHOT_DIRECTORY_PATTERN.matches(it.name) ||
                        RECOVERED_SNAPSHOT_DIRECTORY_PATTERN.matches(it.name))
            } ?: 0
        val legacyCount =
            if (source.kind == BackupSourceKind.CARD && destination.kind == BackupSourceKind.CARD) {
                backupsDirectory(destination).listFiles()?.count {
                    it.isDirectory && it.name.toLongOrNull()?.let { ordinal -> ordinal > 0L } == true
                } ?: 0
            } else {
                0
            }
        return contractCount + legacyCount
    }

    private fun readContractSnapshot(
        directory: File,
        destination: BackupStorageEndpoint?,
        verifyContents: Boolean,
    ): CompyBackupSet {
        if (!directory.isDirectory) throw IOException("Snapshot path is not a directory: $directory")
        val manifestFile = File(directory, MANIFEST_FILE_NAME)
        val bytes = readBytes(manifestFile)
        val manifest = decodeManifest(bytes)
        val sourceIdDirectory = directory.parentFile
            ?: throw IOException("Snapshot directory has no source-id parent: $directory")
        val kindDirectory = sourceIdDirectory.parentFile
            ?: throw IOException("Snapshot directory has no source-kind parent: $directory")
        val directoryKind =
            BackupSourceKind.entries.firstOrNull { it.wireName == kindDirectory.name }
                ?: throw IOException("Snapshot directory has an invalid source kind: $directory")
        val bareId = sourceIdDirectory.name
        val directorySourceId = if (directoryKind == BackupSourceKind.CARD) "fs:$bareId" else bareId
        if (manifest.sourceKind != directoryKind || manifest.sourceId != directorySourceId) {
            throw IOException("Snapshot source identity disagrees with its directory: $directory")
        }
        if (FINAL_SNAPSHOT_DIRECTORY_PATTERN.matches(directory.name)) {
            val nameOrdinal = directory.name.substringBefore('-').toLong()
            if (nameOrdinal != manifest.ordinal) throw IOException("Snapshot ordinal disagrees with its name: $directory")
        } else if (directory.name != "$RECOVERED_PREFIX${manifest.captureId}") {
            throw IOException("Recovered snapshot name disagrees with capture ID: $directory")
        }
        if (verifyContents) verifySnapshotContents(directory, destination, manifest)
        return CompyBackupSet(
            ordinal = manifest.ordinal,
            directory = directory,
            sourceKind = manifest.sourceKind,
            sourceId = manifest.sourceId,
            captureId = manifest.captureId,
            createdAt = manifest.createdAt,
            pinned = manifest.pinned,
            label = manifest.label,
            destination = destination,
            manifest = manifest,
            manifestSha256 = sha256(bytes),
        )
    }

    private fun verifySnapshotContents(
        directory: File,
        destination: BackupStorageEndpoint?,
        manifest: SnapshotManifest,
    ) {
        val actualFiles = collectFiles(File(directory, PROJECTS_DIRECTORY_NAME), PROJECTS_DIRECTORY_NAME)
        if (actualFiles.map { it.entry } != manifest.files) {
            throw IOException("Snapshot project files do not match manifest: $directory")
        }
        destination?.let { endpoint ->
            val archive = File(backupsDirectory(endpoint), APK_DIRECTORY_NAME)
            manifest.apks.forEach { verifyArchivedApk(File(archive, it.fileName), it) }
        }
    }

    private fun listLegacySnapshots(destination: BackupStorageEndpoint): List<CompyBackupSet> {
        if (destination.kind != BackupSourceKind.CARD) {
            return emptyList()
        }
        val children = backupsDirectory(destination).listFiles() ?: return emptyList()
        return children.mapNotNull { readLegacySnapshotOrNull(it, destination) }
    }

    private fun readLegacySnapshotOrNull(
        directory: File,
        destination: BackupStorageEndpoint,
    ): CompyBackupSet? {
        if (!directory.isDirectory) return null
        val ordinal = directory.name.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val manifestFile = File(directory, LEGACY_MANIFEST_FILE_NAME)
        val projectDirectory = File(directory, PROJECTS_DIRECTORY_NAME)
        val apkDirectory = File(directory, APK_DIRECTORY_NAME)
        if (!manifestFile.isFile || !projectDirectory.isDirectory || !apkDirectory.isDirectory) return null

        val bytes = readBytes(manifestFile)
        val lines = String(bytes, StandardCharsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "format\t1" || "ordinal\t$ordinal" !in lines) {
            throw IOException("Invalid legacy snapshot manifest: $manifestFile")
        }
        val apks =
            lines.filter { it.startsWith("package\t") }.map { line ->
                val fields = line.split('\t')
                if (fields.size != 5) throw IOException("Invalid legacy APK entry: $manifestFile")
                val apk = File(apkDirectory, fields[4])
                if (!apk.isFile) return null
                val metadata = apkInspector.inspect(apk)
                val versionCode = fields[2].toLongOrNull() ?: throw IOException("Invalid legacy version code")
                if (
                    metadata.packageName != fields[1] ||
                    metadata.versionName != fields[3] ||
                    metadata.versionCode != versionCode
                ) throw IOException("Legacy APK metadata disagrees with manifest: $apk")
                SnapshotApkEntry(
                    packageName = metadata.packageName,
                    versionName = metadata.versionName,
                    versionCode = metadata.versionCode,
                    debuggable = metadata.debuggable,
                    signingCertificateSha256 = normalizeSha256(metadata.signingCertificateSha256),
                    fileName = fields[4],
                    size = apk.length(),
                    sha256 = sha256(apk),
                )
            }.sortedBy { it.packageName }
        if (apks.map { it.packageName }.toSet() != REQUIRED_APK_PACKAGES) return null
        val files = collectFiles(projectDirectory, PROJECTS_DIRECTORY_NAME).map { it.entry }
        val manifest =
            SnapshotManifest(
                sourceKind = BackupSourceKind.CARD,
                sourceId = destination.id,
                captureId = LEGACY_CAPTURE_ID,
                ordinal = ordinal,
                pinned = false,
                label = null,
                createdAt = "",
                files = files,
                apks = apks,
            )
        return CompyBackupSet(
            ordinal = ordinal,
            directory = directory,
            sourceKind = BackupSourceKind.CARD,
            sourceId = destination.id,
            destination = destination,
            manifest = manifest,
            manifestSha256 = sha256(bytes),
            legacy = true,
        )
    }

    private fun inspectInstalledApks(installedApks: List<InstalledApkSnapshot>): List<CapturedApk> {
        if (installedApks.map { it.packageName }.toSet() != REQUIRED_APK_PACKAGES) {
            throw IllegalArgumentException("Exactly the installed IDE and launcher APKs are required")
        }
        if (installedApks.map { it.packageName }.distinct().size != installedApks.size) {
            throw IllegalArgumentException("Installed APK package names must be unique")
        }
        return installedApks.map { installed ->
            if (!installed.sourceApk.isFile || !installed.sourceApk.canRead()) {
                throw IOException("Installed APK is not readable: ${installed.packageName}")
            }
            val expected = installed.metadata().copy(
                signingCertificateSha256 = normalizeSha256(installed.signingCertificateSha256),
            )
            val inspected = apkInspector.inspect(installed.sourceApk)
            val observed = inspected.copy(
                signingCertificateSha256 = normalizeSha256(inspected.signingCertificateSha256),
            )
            if (observed != expected) {
                throw IOException("Installed APK archive metadata disagrees with package state: ${installed.packageName}")
            }
            val archiveName = apkArchiveName(observed)
            CapturedApk(
                source = installed.sourceApk,
                entry =
                    SnapshotApkEntry(
                        packageName = observed.packageName,
                        versionName = observed.versionName,
                        versionCode = observed.versionCode,
                        debuggable = observed.debuggable,
                        signingCertificateSha256 = observed.signingCertificateSha256,
                        fileName = archiveName,
                        size = installed.sourceApk.length(),
                        sha256 = sha256(installed.sourceApk),
                    ),
            )
        }.sortedBy { it.entry.packageName }
    }

    private fun preflightAvailableSpace(
        captureDestinations: List<BackupStorageEndpoint>,
        sourceFiles: List<CapturedFile>,
        apks: List<CapturedApk>,
    ) {
        val projectBytes = sourceFiles.fold(0L) { total, file -> Math.addExact(total, file.entry.size) }
        val shortfalls = captureDestinations.mapNotNull { destination ->
            val archive = File(backupsDirectory(destination), APK_DIRECTORY_NAME)
            val missingApkBytes =
                apks.filter { !File(archive, it.entry.fileName).exists() }
                    .fold(0L) { total, apk -> Math.addExact(total, apk.entry.size) }
            val bytesToWrite = Math.addExact(projectBytes, missingApkBytes)
            val required = Math.addExact(bytesToWrite, MINIMUM_POST_WRITE_FREE_BYTES)
            val available = usableSpace(destination.compyDirectory).coerceAtLeast(0L)
            if (available >= required) {
                null
            } else {
                BackupSpaceShortfall(
                    destination = destination,
                    requiredBytes = required,
                    availableBytes = available,
                    projectedRemainingBytes = available - bytesToWrite,
                )
            }
        }
        if (shortfalls.isNotEmpty()) throw InsufficientBackupSpaceException(shortfalls)
    }

    private fun verifyArchivedApk(file: File, expected: SnapshotApkEntry) {
        verifyFile(file, expected.size, expected.sha256)
        val inspected = apkInspector.inspect(file)
        val metadata = inspected.copy(
            signingCertificateSha256 = normalizeSha256(inspected.signingCertificateSha256),
        )
        if (metadata != expected.metadata()) throw IOException("APK archive metadata mismatch: $file")
        if (apkArchiveName(metadata) != expected.fileName) throw IOException("APK archive name mismatch: $file")
    }

    private fun apkArchiveName(metadata: ApkArchiveMetadata): String {
        val token =
            buildString {
                var replacingInvalid = false
                metadata.versionName.forEach { character ->
                    if (character.isAsciiArtifactCharacter()) {
                        append(character)
                        replacingInvalid = false
                    } else if (!replacingInvalid) {
                        append(CompyStorageContract.APK_INVALID_CHARACTER_REPLACEMENT)
                        replacingInvalid = CompyStorageContract.APK_COLLAPSE_INVALID_CHARACTER_RUNS
                    }
                }
            }
        if (token.isEmpty() || !APK_VERSION_TOKEN_PATTERN.matches(token)) {
            throw IOException("APK versionName cannot produce an archive token: ${metadata.versionName}")
        }
        return when (metadata.packageName) {
            CompyStorageContract.IDE_PACKAGE ->
                if (metadata.debuggable) "Compy-IDE-debug-$token.apk" else "Compy-IDE-$token.apk"
            CompyStorageContract.LAUNCHER_PACKAGE ->
                if (metadata.debuggable) {
                    "toys.compy.launcher-debug-$token.apk"
                } else {
                    "toys.compy.launcher-$token.apk"
                }
            else -> throw IOException("Unsupported Compy APK package: ${metadata.packageName}")
        }
    }

    private fun encodeManifest(manifest: SnapshotManifest): ByteArray {
        val json =
            JSONObject()
                .put("format", CompyStorageContract.SNAPSHOT_FORMAT)
                .put("format_ver", CompyStorageContract.SNAPSHOT_FORMAT_VERSION)
                .put("storage_schema_ver", CompyStorageContract.STORAGE_SCHEMA_VERSION)
                .put("source_kind", manifest.sourceKind.wireName)
                .put("source_id", manifest.sourceId)
                .put("capture_id", manifest.captureId)
                .put("ordinal", manifest.ordinal)
                .put("pinned", manifest.pinned)
                .put("created_at", manifest.createdAt)
                .put("created_by", WRITER_LAUNCHER)
        manifest.label?.let { json.put("label", it) }
        json.put(
            "files",
            JSONArray().also { array ->
                manifest.files.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("path", entry.path)
                            .put("size", entry.size)
                            .put("sha256", entry.sha256),
                    )
                }
            },
        )
        json.put(
            "apks",
            JSONArray().also { array ->
                manifest.apks.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("package", entry.packageName)
                            .put("version_name", entry.versionName)
                            .put("version_code", entry.versionCode)
                            .put("debuggable", entry.debuggable)
                            .put("signing_cert_sha256", entry.signingCertificateSha256)
                            .put("file", entry.fileName)
                            .put("size", entry.size)
                            .put("sha256", entry.sha256),
                    )
                }
            },
        )
        return (json.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeManifest(bytes: ByteArray): SnapshotManifest {
        try {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (
                json.getString("format") != CompyStorageContract.SNAPSHOT_FORMAT ||
                json.getInt("format_ver") != CompyStorageContract.SNAPSHOT_FORMAT_VERSION ||
                json.getInt("storage_schema_ver") != CompyStorageContract.STORAGE_SCHEMA_VERSION ||
                json.getString("created_by") !in WRITERS
            ) throw IOException("Unsupported snapshot manifest format")
            val sourceKind =
                BackupSourceKind.entries.firstOrNull { it.wireName == json.getString("source_kind") }
                    ?: throw IOException("Invalid snapshot source kind")
            val sourceId = json.getString("source_id")
            val createdAt = json.getString("created_at")
            validateSourceId(sourceKind, sourceId)
            if (!RFC3339_UTC_PATTERN.matches(createdAt)) throw IOException("Invalid snapshot creation time")
            val captureId = requireCanonicalUuid(json.getString("capture_id"), "capture_id")
            val ordinal = json.getLong("ordinal")
            if (ordinal <= 0L) throw IOException("Invalid snapshot ordinal")
            val label =
                if (json.has("label") && !json.isNull("label")) {
                    json.getString("label").takeIf { it.isNotEmpty() }
                        ?: throw IOException("Snapshot label is empty")
                } else {
                    null
                }
            val files =
                json.getJSONArray("files").objects().map { item ->
                    SnapshotFileEntry(
                        path = item.getString("path"),
                        size = item.getLong("size"),
                        sha256 = normalizeSha256(item.getString("sha256")),
                    ).also {
                        if (it.size < 0L) throw IOException("Negative snapshot file size")
                    }
                }.sortedBy { it.path }
            validateManifestPaths(files.map { it.path })
            val apks =
                json.getJSONArray("apks").objects().map { item ->
                    SnapshotApkEntry(
                        packageName = item.getString("package"),
                        versionName = item.getString("version_name"),
                        versionCode = item.getLong("version_code"),
                        debuggable = item.getBoolean("debuggable"),
                        signingCertificateSha256 = normalizeSha256(item.getString("signing_cert_sha256")),
                        fileName = item.getString("file"),
                        size = item.getLong("size"),
                        sha256 = normalizeSha256(item.getString("sha256")),
                    ).also { entry ->
                        if (entry.versionName.isEmpty() || entry.versionCode < 1L || entry.size < 0L) {
                            throw IOException("Invalid snapshot APK entry")
                        }
                        validateApkFileName(entry.fileName)
                        if (apkArchiveName(entry.metadata()) != entry.fileName) {
                            throw IOException("Snapshot APK name disagrees with metadata")
                        }
                    }
                }.sortedBy { it.packageName }
            if (apks.map { it.packageName }.toSet() != REQUIRED_APK_PACKAGES || apks.size != 2) {
                throw IOException("Snapshot must reference the IDE and launcher APKs")
            }
            return SnapshotManifest(
                sourceKind = sourceKind,
                sourceId = sourceId,
                captureId = captureId,
                ordinal = ordinal,
                pinned = json.getBoolean("pinned"),
                label = label,
                createdAt = createdAt,
                files = files,
                apks = apks,
            )
        } catch (error: JSONException) {
            throw IOException("Invalid snapshot manifest JSON", error)
        }
    }

    private fun writeRestoreJournal(file: File, journal: RestoreJournal) {
        val json =
            JSONObject()
                .put("format", CompyStorageContract.RESTORE_JOURNAL_FORMAT)
                .put("format_ver", CompyStorageContract.RESTORE_JOURNAL_FORMAT_VERSION)
                .put("operation_id", journal.operationId)
                .put("source_manifest_sha256", journal.sourceManifestSha256)
                .put("target", journal.targetName)
                .put("target_existed", journal.targetExisted)
                .put("backup_path", journal.backupName ?: JSONObject.NULL)
                .put("staged_path", journal.stagedName)
                .put("phase", journal.phase.wireName)
                .put(
                    "files",
                    JSONArray().also { array ->
                        journal.expectedFiles.forEach { entry ->
                            array.put(
                                JSONObject()
                                    .put("path", entry.path)
                                    .put("size", entry.size)
                                    .put("sha256", entry.sha256),
                            )
                        }
                    },
                )
        replaceJsonDurably(file, (json.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeRestoreJournal(bytes: ByteArray): RestoreJournal {
        try {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (
                json.getString("format") != CompyStorageContract.RESTORE_JOURNAL_FORMAT ||
                json.getInt("format_ver") != CompyStorageContract.RESTORE_JOURNAL_FORMAT_VERSION
            ) throw IOException("Unsupported restore journal format")
            val operationId = requireCanonicalUuid(json.getString("operation_id"), "operation_id")
            val target = json.getString("target")
            validateProjectName(target)
            val backup = if (json.isNull("backup_path")) null else json.getString("backup_path")
            val expected =
                json.getJSONArray("files").objects().map { item ->
                    SnapshotFileEntry(
                        path = item.getString("path"),
                        size = item.getLong("size"),
                        sha256 = normalizeSha256(item.getString("sha256")),
                    )
                }.sortedBy { it.path }
            if (expected.isEmpty()) throw IOException("Restore journal has no file entries")
            validateManifestPaths(expected.map { it.path })
            entriesForProject(expected, target)
            return RestoreJournal(
                operationId = operationId,
                sourceManifestSha256 = normalizeSha256(json.getString("source_manifest_sha256")),
                targetName = target,
                targetExisted = json.getBoolean("target_existed"),
                backupName = backup,
                stagedName = json.getString("staged_path"),
                phase = RestorePhase.fromWireName(json.getString("phase")),
                expectedFiles = expected,
            )
        } catch (error: JSONException) {
            throw IOException("Invalid restore journal JSON", error)
        }
    }

    private fun replaceJsonDurably(target: File, bytes: ByteArray) {
        val operation = target.name.substringAfter(RESTORE_JOURNAL_PREFIX).substringBefore(".json")
        val staging = File(target.parentFile, "$INCOMING_PREFIX$operation.journal.json")
        if (staging.exists()) deleteTreeChecked(staging)
        writeBytesDurably(staging, bytes)
        if (!staging.renameTo(target)) {
            deleteTreeChecked(staging)
            throw IOException("Could not replace operation journal: $target")
        }
    }

    private fun captureSourceFiles(): List<CapturedFile> {
        val projects = projectsDirectory.listFiles()?.sortedBy { it.name }
            ?: throw IOException("Could not read projects directory: $projectsDirectory")
        return projects.flatMap { project ->
            if (
                !project.isDirectory ||
                isReservedName(project.name) ||
                PRESERVED_PROJECT_PATTERN.matches(project.name) ||
                !File(project, "main.lua").isFile
            ) {
                emptyList()
            } else {
                validateProjectName(project.name)
                collectFiles(project, "$PROJECTS_DIRECTORY_NAME/${project.name}")
            }
        }.sortedBy { it.entry.path }.also { files ->
            validateManifestPaths(files.map { it.entry.path })
        }
    }

    private fun collectFiles(root: File, prefix: String): List<CapturedFile> {
        if (!root.exists()) return emptyList()
        if (!root.isDirectory) throw IOException("Project root is not a directory: $root")
        val files = mutableListOf<CapturedFile>()
        fun visit(directory: File, relative: String) {
            val children = directory.listFiles()?.sortedBy { it.name }
                ?: throw IOException("Could not read directory: $directory")
            children.forEach { child ->
                if (isReservedName(child.name)) return@forEach
                ensureNoSymlink(child)
                val childRelative = "$relative/${child.name}"
                when {
                    child.isDirectory -> visit(child, childRelative)
                    child.isFile ->
                        files +=
                            CapturedFile(
                                file = child,
                                entry = SnapshotFileEntry(childRelative, child.length(), sha256(child)),
                            )
                    else -> throw IOException("Unsupported project entry: $child")
                }
            }
        }
        visit(root, prefix)
        val sorted = files.sortedBy { it.entry.path }
        validateManifestPaths(sorted.map { it.entry.path })
        return sorted
    }

    private fun selectedProjectNames(backupSet: CompyBackupSet): List<String> {
        val manifest = backupSet.manifest ?: throw IOException("Snapshot manifest is unavailable")
        return manifest.files.map { entry ->
            val parts = entry.path.split('/')
            if (parts.size < 2 || parts[0] != PROJECTS_DIRECTORY_NAME) {
                throw IOException("Snapshot path is outside projects: ${entry.path}")
            }
            parts[1]
        }.distinct().sorted()
    }

    private fun entriesForProject(
        entries: List<SnapshotFileEntry>,
        projectName: String,
    ): List<SnapshotFileEntry> {
        val prefix = "$PROJECTS_DIRECTORY_NAME/$projectName"
        val selected = entries.filter { it.path == prefix || it.path.startsWith("$prefix/") }
        if (selected.isEmpty()) throw IOException("No manifest entries for project: $projectName")
        return selected
    }

    private fun verifyProjectItem(
        target: File,
        projectName: String,
        expected: List<SnapshotFileEntry>,
    ): Boolean {
        if (!target.exists()) return false
        val prefix = "$PROJECTS_DIRECTORY_NAME/$projectName"
        return try {
            val actual =
                if (target.isFile) {
                    listOf(CapturedFile(target, SnapshotFileEntry(prefix, target.length(), sha256(target))))
                } else {
                    collectFiles(target, prefix)
                }
            actual.map { it.entry } == expected
        } catch (_: IOException) {
            false
        }
    }

    private fun resolveBackupSet(requested: CompyBackupSet): CompyBackupSet {
        if (!requested.restorable) {
            throw IOException(requested.problem ?: "Snapshot is not restorable")
        }
        val canonical = requested.directory.canonicalFile
        return listAllBackupCopies(verifyContents = true).firstOrNull { it.directory.canonicalFile == canonical }
            ?: throw IOException("Snapshot is outside the configured backup stores")
    }

    private fun copyEntry(source: File, target: File) {
        ensureNoSymlink(source)
        when {
            source.isDirectory -> {
                ensureDirectory(target)
                val children = source.listFiles()?.sortedBy { it.name }
                    ?: throw IOException("Could not read directory: $source")
                children.forEach { child ->
                    if (!isReservedName(child.name)) copyEntry(child, File(target, child.name))
                }
            }
            source.isFile -> copyFileDurably(source, target)
            else -> throw IOException("Unsupported project entry: $source")
        }
    }

    private fun copyFileDurably(source: File, target: File) {
        target.parentFile?.let(::ensureDirectory)
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun writeBytesDurably(target: File, contents: ByteArray) {
        target.parentFile?.let(::ensureDirectory)
        if (target.exists()) throw IOException("Refusing to overwrite staged file: $target")
        FileOutputStream(target).use { output ->
            output.write(contents)
            output.fd.sync()
        }
    }

    private fun writeBytesToExistingFileDurably(target: File, contents: ByteArray) {
        FileOutputStream(target, false).use { output ->
            output.write(contents)
            output.fd.sync()
        }
    }

    private fun readBytes(file: File): ByteArray {
        if (!file.isFile || !file.canRead()) throw IOException("File is not readable: $file")
        return FileInputStream(file).use { it.readBytes() }
    }

    private fun verifyFile(file: File, size: Long, hash: String) {
        if (!file.isFile || file.length() != size || sha256(file) != hash) {
            throw IOException("File content verification failed: $file")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it) }

    private fun normalizeSha256(value: String): String {
        val normalized = value.lowercase(Locale.US)
        if (!SHA256_PATTERN.matches(normalized)) throw IOException("Invalid SHA-256 value")
        return normalized
    }

    private fun validateManifestPaths(paths: List<String>) {
        if (paths.distinct().size != paths.size) throw IOException("Duplicate snapshot path")
        val sorted = paths.sorted()
        sorted.forEach { path ->
            if (path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) {
                throw IOException("Unsafe snapshot path: $path")
            }
            val components = path.split('/')
            if (
                components.size < 2 ||
                components.first() != PROJECTS_DIRECTORY_NAME ||
                components.any { it.isEmpty() || it == "." || it == ".." || isReservedName(it) }
            ) throw IOException("Unsafe snapshot path: $path")
        }
        sorted.forEachIndexed { index, path ->
            sorted.drop(index + 1).firstOrNull()?.let { next ->
                if (next.startsWith("$path/")) throw IOException("Snapshot path prefix conflict: $path")
            }
        }
    }

    private fun validateApkFileName(name: String) {
        if (
            name.isEmpty() ||
            !name.endsWith(".apk") ||
            name.contains('/') ||
            name.contains('\\') ||
            name.contains('\u0000') ||
            isReservedName(name)
        ) throw IOException("Unsafe APK archive name: $name")
    }

    private fun validateEndpoint(endpoint: BackupStorageEndpoint) {
        validateSourceId(endpoint.kind, endpoint.id)
    }

    private fun validateSourceId(kind: BackupSourceKind, id: String) {
        val valid =
            when (kind) {
                BackupSourceKind.CARD -> CARD_ID_PATTERN.matches(id)
                BackupSourceKind.INTERNAL -> UUID_PATTERN.matches(id)
            }
        require(valid) { "Invalid ${kind.wireName} storage identity: $id" }
    }

    private fun validateProjectName(name: String) {
        if (
            name.isEmpty() ||
            name.length > 60 ||
            name == "." ||
            name == ".." ||
            name.contains('/') ||
            name.contains('\\') ||
            name.contains('\u0000') ||
            isReservedName(name)
        ) throw IOException("Unsafe project name: $name")
    }

    private fun checkedOperationPath(name: String, requiredPrefix: String): File {
        if (name.contains('/') || name.contains('\\') || !name.startsWith(requiredPrefix)) {
            throw IOException("Unsafe operation path: $name")
        }
        return File(projectsDirectory, name)
    }

    private fun checkedBackupPath(targetName: String, backupName: String): File {
        if (
            backupName.contains('/') ||
            backupName.contains('\\') ||
            (backupName != "$targetName.old" && !backupName.matches(Regex("${Regex.escape(targetName)}[.]old[.][1-9][0-9]*")))
        ) throw IOException("Unsafe preserved path: $backupName")
        return File(projectsDirectory, backupName)
    }

    private fun nextOldPath(target: File): File {
        val parent = target.parentFile ?: throw IOException("Project target has no parent: $target")
        val direct = File(parent, "${target.name}.old")
        if (!direct.exists()) return direct
        val suffixPattern = Regex("^${Regex.escape(target.name)}[.]old[.]([1-9][0-9]*)$")
        val siblings = parent.listFiles()
            ?: throw IOException("Could not read projects directory: $parent")
        val largestSuffix =
            siblings.mapNotNull { sibling ->
                suffixPattern.matchEntire(sibling.name)?.groupValues?.get(1)?.let(::BigInteger)
            }.maxOrNull() ?: BigInteger.ZERO
        return File(parent, "${target.name}.old.${largestSuffix + BigInteger.ONE}")
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create directory: $directory")
        }
    }

    private fun listDirectory(directory: File): List<File> {
        if (!directory.isDirectory) throw IOException("Expected a readable directory: $directory")
        return directory.listFiles()?.toList() ?: throw IOException("Could not read directory: $directory")
    }

    private fun deleteTreeChecked(path: File) {
        if (!path.exists()) return
        if (path.isDirectory) {
            val children = path.listFiles() ?: throw IOException("Could not read directory: $path")
            children.forEach(::deleteTreeChecked)
        }
        if (!path.delete()) throw IOException("Could not delete: $path")
    }

    private fun ensureNoSymlink(path: File) {
        if (path.canonicalFile != path.absoluteFile) throw IOException("Symbolic links are unsupported: $path")
    }

    private fun resolveRelativeFile(root: File, relative: String): File {
        val resolved = File(root, relative)
        val rootPath = root.canonicalFile.path + File.separator
        if (!resolved.canonicalFile.path.startsWith(rootPath)) {
            throw IOException("Path escapes snapshot root: $relative")
        }
        return resolved
    }

    private fun sourceSnapshotsDirectory(destination: BackupStorageEndpoint): File {
        val bareId = if (source.kind == BackupSourceKind.CARD) source.id.removePrefix("fs:") else source.id
        return File(
            File(File(backupsDirectory(destination), SNAPSHOTS_DIRECTORY_NAME), source.kind.wireName),
            bareId,
        )
    }

    private fun backupsDirectory(destination: BackupStorageEndpoint): File =
        File(destination.compyDirectory, BACKUPS_DIRECTORY_NAME)

    private fun lockFile(destination: BackupStorageEndpoint): File =
        File(File(backupsDirectory(destination), LOCKS_DIRECTORY_NAME), STORE_LOCK_FILE_NAME)

    private fun endpointKey(endpoint: BackupStorageEndpoint): String = endpoint.compyDirectory.canonicalPath

    private fun destinationOrder(endpoint: BackupStorageEndpoint): Int =
        CompyStorageContract.DESTINATION_ORDER.indexOf(endpoint.kind.wireName)

    private fun topLevelProjectName(file: CapturedFile): String = file.entry.path.split('/')[1]

    private fun formatDirectoryTimestamp(date: Date): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = UTC }.format(date)

    private fun formatCreatedAt(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = UTC }.format(date)

    private fun canonicalUuid(uuid: UUID): String = uuid.toString().lowercase(Locale.US)

    private fun requireCanonicalUuid(value: String, field: String): String {
        if (!UUID_PATTERN.matches(value)) throw IOException("Invalid $field")
        return value
    }

    private fun readManifestOrdinalOrNull(manifest: File): Long? =
        try {
            decodeManifest(readBytes(manifest)).ordinal
        } catch (_: Exception) {
            null
        }

    private fun isReservedName(name: String): Boolean =
        name.startsWith(INCOMING_PREFIX) ||
            name.startsWith(RESTORE_JOURNAL_PREFIX) ||
            name.startsWith(RECOVERED_PREFIX) ||
            name == LOCKS_DIRECTORY_NAME

    private fun Char.isAsciiArtifactCharacter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { index -> getJSONObject(index) }

    private data class CapturedFile(
        val file: File,
        val entry: SnapshotFileEntry,
    )

    private data class CapturedApk(
        val source: File,
        val entry: SnapshotApkEntry,
    )

    private data class OwnedBackupLock(
        val destination: BackupStorageEndpoint,
        val file: File,
        val contentsSha256: String,
    )

    private enum class RestorePhase(val wireName: String) {
        STAGED("staged"),
        OLD_PRESERVED("old-preserved"),
        PROMOTED("promoted");

        companion object {
            fun fromWireName(value: String): RestorePhase =
                entries.firstOrNull { it.wireName == value }
                    ?: throw IOException("Invalid restore phase: $value")
        }
    }

    private data class RestoreJournal(
        val operationId: String,
        val sourceManifestSha256: String,
        val targetName: String,
        val targetExisted: Boolean,
        val backupName: String?,
        val stagedName: String,
        val phase: RestorePhase,
        val expectedFiles: List<SnapshotFileEntry>,
    )

    companion object {
        const val DEFAULT_RETENTION_LIMIT = CompyStorageContract.SNAPSHOT_RETENTION_PER_SOURCE

        fun recoverPendingRestoresOnStartup(source: BackupStorageEndpoint) {
            CompyBackupStore(
                source = source,
                destinationEndpoints = listOf(source),
                apkInspector = ApkArchiveInspector { throw IOException("APK inspection is unavailable during startup recovery") },
            ).recoverPendingRestores()
        }

        private const val BACKUPS_DIRECTORY_NAME = "backups"
        private const val PROJECTS_DIRECTORY_NAME = "projects"
        private const val APK_DIRECTORY_NAME = "apk"
        private const val SNAPSHOTS_DIRECTORY_NAME = "snapshots"
        private const val LOCKS_DIRECTORY_NAME = ".locks"
        private const val STORE_LOCK_FILE_NAME = "store"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val LEGACY_MANIFEST_FILE_NAME = "manifest.txt"
        private const val INCOMING_PREFIX = ".incoming."
        private const val RECOVERED_PREFIX = ".recovered."
        private const val RESTORE_INCOMING_PREFIX = ".incoming."
        private const val RESTORE_JOURNAL_PREFIX = ".restore."
        private const val WRITER_LAUNCHER = "launcher"
        private const val LEGACY_CAPTURE_ID = "00000000-0000-0000-0000-000000000000"
        private const val MINIMUM_POST_WRITE_FREE_BYTES = 2L * 1024L * 1024L * 1024L
        private val UTC = TimeZone.getTimeZone("UTC")
        private val WRITERS = setOf("launcher", "host")
        private val REQUIRED_APK_PACKAGES =
            setOf(CompyStorageContract.IDE_PACKAGE, CompyStorageContract.LAUNCHER_PACKAGE)
        private val CARD_ID_PATTERN = Regex("^fs:[0-9a-f]+(-[0-9a-f]+)*$")
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val RFC3339_UTC_PATTERN =
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:[.][0-9]+)?Z$")
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val APK_VERSION_TOKEN_PATTERN = Regex(CompyStorageContract.APK_VERSION_TOKEN_PATTERN)
        private val FINAL_SNAPSHOT_DIRECTORY_PATTERN = Regex(CompyStorageContract.SNAPSHOT_DIRECTORY_PATTERN)
        private val RECOVERED_SNAPSHOT_DIRECTORY_PATTERN =
            Regex(CompyStorageContract.RECOVERED_SNAPSHOT_DIRECTORY_PATTERN)
        private val RESTORE_JOURNAL_PATTERN =
            Regex("^[.]restore[.][0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]json$")
        private val PRESERVED_PROJECT_PATTERN = Regex("^.+[.]old(?:[.][1-9][0-9]*)?$")
    }
}
