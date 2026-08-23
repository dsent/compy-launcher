/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

data class InstalledApkSnapshot(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sourceApk: File,
)

data class CompyBackupSet(
    val ordinal: Long,
    val directory: File,
)

data class BackupCreateResult(
    val backupSet: CompyBackupSet,
    val projectEntries: Int,
    val retainedSets: Int,
)

data class ProjectRestoreResult(
    val restoredEntries: Int,
    val preservedEntries: Int,
)

/** Card-local backup storage with clock-independent identities and collision-safe restore. */
class CompyBackupStore(
    private val compyDirectory: File,
    private val retentionLimit: Int = DEFAULT_RETENTION_LIMIT,
) {
    init {
        require(retentionLimit > 0) { "retentionLimit must be positive" }
    }

    private val backupsDirectory = File(compyDirectory, BACKUPS_DIRECTORY_NAME)
    private val projectsDirectory = File(compyDirectory, PROJECTS_DIRECTORY_NAME)

    @Synchronized
    fun listBackupSets(): List<CompyBackupSet> {
        val children = backupsDirectory.listFiles() ?: return emptyList()
        return children
            .mapNotNull(::completeBackupSet)
            .sortedByDescending { it.ordinal }
    }

    @Synchronized
    fun createBackup(installedApks: List<InstalledApkSnapshot>): BackupCreateResult {
        require(installedApks.isNotEmpty()) { "At least one installed APK is required" }
        require(installedApks.map { it.packageName }.distinct().size == installedApks.size) {
            "Installed APK package names must be unique"
        }
        require(installedApks.map { it.packageName }.toSet().containsAll(REQUIRED_APK_PACKAGES)) {
            "Installed IDE and launcher APKs are required"
        }
        installedApks.forEach(::validateInstalledApk)

        if (!compyDirectory.isDirectory || !projectsDirectory.isDirectory) {
            throw IOException("The removable card does not contain Documents/compy/projects")
        }

        ensureDirectory(backupsDirectory)
        removeIncompleteSets()
        removeRestoreStaging()

        val ordinal = (listBackupSets().maxOfOrNull { it.ordinal } ?: 0L) + 1L
        val staging = File(backupsDirectory, "$INCOMING_PREFIX$ordinal")
        val completed = File(backupsDirectory, ordinal.toString())
        if (staging.exists() || completed.exists()) {
            throw IOException("Backup set $ordinal already exists")
        }
        ensureDirectory(staging)

        try {
            val projectTarget = File(staging, PROJECTS_DIRECTORY_NAME)
            ensureDirectory(projectTarget)
            val projectEntries = copyDirectoryContents(projectsDirectory, projectTarget)

            val apkTarget = File(staging, APK_DIRECTORY_NAME)
            ensureDirectory(apkTarget)
            installedApks.sortedBy { it.packageName }.forEach { apk ->
                copyFileDurably(apk.sourceApk, File(apkTarget, "${apk.packageName}.apk"))
            }

            writeManifest(File(staging, MANIFEST_FILE_NAME), ordinal, installedApks)
            if (!staging.renameTo(completed)) {
                throw IOException("Could not finalize backup set $ordinal")
            }

            pruneCompletedSets()
            return BackupCreateResult(
                backupSet = CompyBackupSet(ordinal, completed),
                projectEntries = projectEntries,
                retainedSets = listBackupSets().size,
            )
        } catch (error: Exception) {
            if (staging.exists()) {
                deleteTreeChecked(staging)
            }
            throw error
        }
    }

    @Synchronized
    fun restoreProjects(backupSet: CompyBackupSet): ProjectRestoreResult {
        val selected = listBackupSets().firstOrNull { it.ordinal == backupSet.ordinal }
            ?: throw IOException("Backup set ${backupSet.ordinal} is not complete")
        if (selected.directory.canonicalFile != backupSet.directory.canonicalFile) {
            throw IOException("Backup set ${backupSet.ordinal} is outside the backup store")
        }

        val sourceProjects = File(selected.directory, PROJECTS_DIRECTORY_NAME)
        ensureDirectory(projectsDirectory)
        removeRestoreStaging()

        val entries = sourceProjects.listFiles()?.sortedBy { it.name } ?: emptyList()
        var preservedEntries = 0
        entries.forEach { source ->
            val staging = File(
                projectsDirectory,
                "$RESTORE_INCOMING_PREFIX${selected.ordinal}.${source.name}",
            )
            if (staging.exists()) {
                deleteTreeChecked(staging)
            }
            copyEntry(source, staging)

            val target = File(projectsDirectory, source.name)
            val preserved = if (target.exists()) nextOldPath(target) else null
            if (preserved != null && !target.renameTo(preserved)) {
                deleteTreeChecked(staging)
                throw IOException("Could not preserve ${target.name} as ${preserved.name}")
            }
            if (!staging.renameTo(target)) {
                if (preserved != null && !preserved.renameTo(target)) {
                    throw IOException(
                        "Could not restore ${target.name}; preserved copy remains at ${preserved.name}",
                    )
                }
                throw IOException("Could not restore ${target.name}")
            }
            if (preserved != null) preservedEntries += 1
        }

        return ProjectRestoreResult(entries.size, preservedEntries)
    }

    private fun completeBackupSet(directory: File): CompyBackupSet? {
        if (!directory.isDirectory) return null
        val ordinal = directory.name.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val apkDirectory = File(directory, APK_DIRECTORY_NAME)
        val hasRequiredShape =
            File(directory, MANIFEST_FILE_NAME).isFile &&
                File(directory, PROJECTS_DIRECTORY_NAME).isDirectory &&
                apkDirectory.isDirectory &&
                REQUIRED_APK_PACKAGES.all { packageName ->
                    File(apkDirectory, "$packageName.apk").isFile
                }
        return if (hasRequiredShape) CompyBackupSet(ordinal, directory) else null
    }

    private fun validateInstalledApk(apk: InstalledApkSnapshot) {
        if (!PACKAGE_NAME_PATTERN.matches(apk.packageName)) {
            throw IllegalArgumentException("Unsafe package name: ${apk.packageName}")
        }
        if (!apk.sourceApk.isFile || !apk.sourceApk.canRead()) {
            throw IOException("Installed APK is not readable: ${apk.packageName}")
        }
    }

    private fun removeIncompleteSets() {
        backupsDirectory.listFiles()
            ?.filter { it.name.startsWith(INCOMING_PREFIX) }
            ?.forEach(::deleteTreeChecked)
    }

    private fun removeRestoreStaging() {
        projectsDirectory.listFiles()
            ?.filter { it.name.startsWith(RESTORE_INCOMING_PREFIX) }
            ?.forEach(::deleteTreeChecked)
    }

    private fun pruneCompletedSets() {
        listBackupSets().drop(retentionLimit).forEach { set ->
            deleteTreeChecked(set.directory)
        }
    }

    private fun copyDirectoryContents(source: File, target: File): Int {
        if (!source.exists()) return 0
        if (!source.isDirectory) throw IOException("Projects path is not a directory: $source")
        val entries = source.listFiles()?.sortedBy { it.name }
            ?: throw IOException("Could not read projects directory: $source")
        entries.forEach { copyEntry(it, File(target, it.name)) }
        return entries.size
    }

    private fun copyEntry(source: File, target: File) {
        when {
            source.isDirectory -> {
                ensureDirectory(target)
                val children = source.listFiles()?.sortedBy { it.name }
                    ?: throw IOException("Could not read directory: $source")
                children.forEach { copyEntry(it, File(target, it.name)) }
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

    private fun writeManifest(
        target: File,
        ordinal: Long,
        installedApks: List<InstalledApkSnapshot>,
    ) {
        val contents = buildString {
            append("format\t1\n")
            append("ordinal\t$ordinal\n")
            installedApks.sortedBy { it.packageName }.forEach { apk ->
                append("package\t")
                append(apk.packageName)
                append('\t')
                append(apk.versionCode)
                append('\t')
                append(sanitizeManifestField(apk.versionName))
                append('\t')
                append(apk.packageName)
                append(".apk\n")
            }
        }
        FileOutputStream(target).use { output ->
            output.write(contents.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun nextOldPath(target: File): File {
        val direct = File(target.parentFile, "${target.name}.old")
        if (!direct.exists()) return direct
        var suffix = 1
        while (true) {
            val candidate = File(target.parentFile, "${target.name}.old.$suffix")
            if (!candidate.exists()) return candidate
            suffix += 1
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create directory: $directory")
        }
    }

    private fun deleteTreeChecked(path: File) {
        if (!path.exists()) return
        if (path.isDirectory) {
            val children = path.listFiles() ?: throw IOException("Could not read directory: $path")
            children.forEach(::deleteTreeChecked)
        }
        if (!path.delete()) throw IOException("Could not delete: $path")
    }

    private fun sanitizeManifestField(value: String): String {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    }

    companion object {
        const val DEFAULT_RETENTION_LIMIT = 3
        private const val BACKUPS_DIRECTORY_NAME = "backups"
        private const val PROJECTS_DIRECTORY_NAME = "projects"
        private const val APK_DIRECTORY_NAME = "apk"
        private const val MANIFEST_FILE_NAME = "manifest.txt"
        private const val INCOMING_PREFIX = ".incoming."
        private const val RESTORE_INCOMING_PREFIX = ".incoming.restore."
        private val REQUIRED_APK_PACKAGES = setOf("toys.compy.ide", "toys.compy.launcher")
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}
