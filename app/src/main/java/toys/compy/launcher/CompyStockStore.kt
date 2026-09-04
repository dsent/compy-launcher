/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class StockProgramVersion(
    val token: String,
    val directory: File,
    val files: List<SnapshotFileEntry>,
    val sourceManifestSha256: String,
)

data class StockProgram(
    val name: String,
    val defaultToken: String,
    val versions: List<StockProgramVersion>,
)

data class CompyStockCatalog(
    val source: BackupStorageEndpoint,
    val programs: List<StockProgram>,
)

data class StockRestoreResult(
    val restoredPrograms: Int,
    val preservedPrograms: Int,
    val unchangedPrograms: Int,
)

class StockRestoreException(
    val completedPrograms: List<String>,
    val failedProgram: String,
    val unstartedPrograms: List<String>,
    cause: Exception,
) : IOException(
    buildString {
        append("Restore stopped while processing ")
        append(failedProgram)
        append(". Completed before the stop: ")
        append(completedPrograms.size)
        append(". Not started: ")
        append(unstartedPrograms.size)
        append(". Completed programs remain restored; programs not started remain unchanged. ")
        append("Correct the storage problem and choose Restore stock programs again. ")
        append("Compy will recover or retry the stopped program first. Detail: ")
        append(cause.message ?: "unknown error")
    },
    cause,
)

/**
 * Reads the public stock layout and restores it through the journaled project
 * replacement engine. Catalog parsing is deliberately strict because this is
 * the recovery source for every selected program.
 */
class CompyStockStore(
    private val source: BackupStorageEndpoint,
    private val target: BackupStorageEndpoint = source,
    uuidGenerator: () -> UUID = { UUID.randomUUID() },
) {
    private val programsDirectory = File(source.compyDirectory, "stock/programs")
    private val projectRestorer =
        CompyBackupStore(
            source = target,
            destinationEndpoints = listOf(target),
            apkInspector = {
                throw IOException("APK inspection is unavailable during stock restore")
            },
            uuidGenerator = uuidGenerator,
        )

    fun readCatalog(): CompyStockCatalog {
        val entries = listDirectory(programsDirectory)
        val programs =
            entries
                .filterNot { isReservedName(it.name) }
                .map { program -> readProgram(program) }
                .sortedBy { it.name }
        if (programs.isEmpty()) throw IOException("This storage location has no stock programs")
        return CompyStockCatalog(source, programs)
    }

    fun restoreAll(
        catalog: CompyStockCatalog,
        selectedTokens: Map<String, String>,
    ): StockRestoreResult {
        require(catalog.source.compyDirectory.canonicalFile == source.compyDirectory.canonicalFile) {
            "Stock catalog belongs to another storage location"
        }
        projectRestorer.recoverPendingRestores()

        // Re-read and re-hash every source before starting a new replacement.
        val currentCatalog = readCatalog()
        val programNames = currentCatalog.programs.map { it.name }
        if (selectedTokens.keys != programNames.toSet()) {
            throw IOException("Every stock program must have one selected version")
        }
        val displayedPrograms = catalog.programs.associateBy { it.name }
        val selectedVersions =
            currentCatalog.programs.map { program ->
                val token = selectedTokens.getValue(program.name)
                val version = program.versions.firstOrNull { it.token == token }
                    ?: throw IOException("Selected stock version is unavailable: ${program.name} $token")
                val displayedVersion =
                    displayedPrograms[program.name]
                        ?.versions
                        ?.firstOrNull { it.token == token }
                if (displayedVersion?.sourceManifestSha256 != version.sourceManifestSha256) {
                    throw IOException(
                        "Stock changed while versions were being chosen. " +
                            "No new restore was started; open Restore stock programs again.",
                    )
                }
                program to version
            }

        var restored = 0
        var preserved = 0
        var unchanged = 0
        val completed = mutableListOf<String>()
        selectedVersions.forEachIndexed { index, (program, version) ->
            try {
                val result =
                    projectRestorer.restoreProjectTree(
                        sourceProject = version.directory,
                        projectName = program.name,
                        sourceManifestSha256 = version.sourceManifestSha256,
                        expectedFiles = version.files,
                    )
                restored += result.restoredEntries
                preserved += result.preservedEntries
                if (result.restoredEntries == 0) unchanged += 1
                completed += program.name
            } catch (error: Exception) {
                throw StockRestoreException(
                    completedPrograms = completed.toList(),
                    failedProgram = program.name,
                    unstartedPrograms = programNames.drop(index + 1),
                    cause = error,
                )
            }
        }
        return StockRestoreResult(restored, preserved, unchanged)
    }

    private fun readProgram(program: File): StockProgram {
        if (
            !program.isDirectory ||
            program.name.length > MAX_PROJECT_NAME_LENGTH ||
            !SAFE_COMPONENT.matches(program.name)
        ) {
            throw IOException("Stock has an invalid program entry: ${program.name}")
        }
        ensureNoSymlink(program)
        val tokens = readIndex(File(program, "versions.txt"), "versions index for ${program.name}")
        if (tokens.toSet().size != tokens.size) {
            throw IOException("Stock repeats a version for ${program.name}")
        }
        val versions = tokens.map { token -> readVersion(program, token) }
        val default = readIndex(File(program, "stock.txt"), "default version for ${program.name}")
        if (default.size != 1 || versions.none { it.token == default.single() }) {
            throw IOException("Stock has an invalid default for ${program.name}")
        }
        return StockProgram(program.name, default.single(), versions)
    }

    private fun readVersion(program: File, token: String): StockProgramVersion {
        if (!SAFE_COMPONENT.matches(token)) {
            throw IOException("Stock has an invalid version for ${program.name}: $token")
        }
        val version = File(program, token)
        if (!version.isDirectory) {
            throw IOException("Stock version is incomplete for ${program.name}: $token")
        }
        ensureNoSymlink(version)
        if (!File(version, "main.lua").isFile) {
            throw IOException("Stock version has no main.lua for ${program.name}: $token")
        }
        val files = collectFiles(version, "projects/${program.name}")
        if (files.isEmpty()) {
            throw IOException("Stock version is empty for ${program.name}: $token")
        }
        return StockProgramVersion(
            token = token,
            directory = version,
            files = files,
            sourceManifestSha256 = sourceManifestSha256(program.name, token, files),
        )
    }

    private fun collectFiles(root: File, prefix: String): List<SnapshotFileEntry> {
        val files = mutableListOf<SnapshotFileEntry>()
        fun visit(directory: File, relative: String) {
            listDirectory(directory).sortedBy { it.name }.forEach { entry ->
                if (isReservedName(entry.name)) return@forEach
                if (entry.name.contains('\\') || entry.name.contains('\u0000')) {
                    throw IOException("Stock version contains an unsafe project path: $entry")
                }
                if (entry.name == ".git" || entry.name == ".compy") {
                    throw IOException("Stock version contains excluded project data: $entry")
                }
                ensureNoSymlink(entry)
                val path = "$relative/${entry.name}"
                when {
                    entry.isDirectory -> visit(entry, path)
                    entry.isFile -> files += SnapshotFileEntry(path, entry.length(), sha256(entry))
                    else -> throw IOException("Stock version contains an unsupported entry: $entry")
                }
            }
        }
        visit(root, prefix)
        return files.sortedBy { it.path }
    }

    private fun readIndex(file: File, description: String): List<String> {
        if (!file.isFile || !file.canRead()) throw IOException("Missing $description")
        ensureNoSymlink(file)
        val lines = file.readLines(StandardCharsets.UTF_8)
        if (lines.isEmpty() || lines.any(String::isBlank)) throw IOException("Invalid $description")
        return lines
    }

    private fun sourceManifestSha256(
        program: String,
        token: String,
        files: List<SnapshotFileEntry>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField("compy-stock-restore-v1")
        digest.updateField(program)
        digest.updateField(token)
        files.forEach { entry ->
            digest.updateField(entry.path)
            digest.updateLong(entry.size)
            digest.updateField(entry.sha256)
        }
        return digest.digest().toHex()
    }

    private fun MessageDigest.updateField(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        updateInt(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.updateInt(value: Int) {
        update(
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
    }

    private fun MessageDigest.updateLong(value: Long) {
        update(
            byteArrayOf(
                (value ushr 56).toByte(),
                (value ushr 48).toByte(),
                (value ushr 40).toByte(),
                (value ushr 32).toByte(),
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it) }

    private fun listDirectory(directory: File): List<File> {
        if (!directory.isDirectory) throw IOException("Expected a readable directory: $directory")
        return directory.listFiles()?.toList() ?: throw IOException("Could not read directory: $directory")
    }

    private fun ensureNoSymlink(path: File) {
        if (path.canonicalFile != path.absoluteFile) {
            throw IOException("Symbolic links are unsupported in stock: $path")
        }
    }

    private fun isReservedName(name: String): Boolean =
        name.startsWith(".incoming.") ||
            name.startsWith(".restore.") ||
            name.startsWith(".recovered.") ||
            name == ".locks"

    private companion object {
        const val MAX_PROJECT_NAME_LENGTH = 60
        val SAFE_COMPONENT = Regex("^[A-Za-z0-9._-]+$")
    }
}
