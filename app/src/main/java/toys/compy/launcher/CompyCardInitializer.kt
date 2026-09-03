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
import java.security.MessageDigest

data class CompyCardInitializationResult(
    val seededProjects: Int,
    val copiedFiles: Int,
    val reusedFiles: Int,
)

object CompyCardInitializer {
    private data class InitializationPlan(
        val seeds: List<SeedTree>,
        val defaultProjectCount: Int,
    )

    private data class SeedTree(
        val source: File,
        val target: File,
    )

    private data class CopyCounts(
        var copied: Int = 0,
        var reused: Int = 0,
    )

    fun initialize(
        internalCompyDirectory: File,
        cardRoot: File,
        operationId: String,
    ): CompyCardInitializationResult {
        if (!OPERATION_ID_PATTERN.matches(operationId)) throw IOException("Invalid SD initialization operation ID")
        if (!internalCompyDirectory.isDirectory || internalCompyDirectory.listFiles() == null) {
            throw IOException("Internal Compy recovery seed is unreadable")
        }
        if (!cardRoot.isDirectory || cardRoot.listFiles() == null) {
            throw IOException("The mounted SD card root is unreadable")
        }

        val cardCompyDirectory = File(cardRoot, CompyStorageContract.ROOT)
        val plan = initializationPlan(internalCompyDirectory, cardCompyDirectory)

        // Preflight every collision before creating even the harmless layout directories.
        CompyStorageContract.CARD_MEDIA_DIRECTORIES.forEach { name ->
            preflightDirectory(File(cardRoot, name))
        }
        CompyStorageContract.INITIALIZED_DESTINATION_PATHS.forEach { path ->
            preflightDirectory(File(cardCompyDirectory, path))
        }
        plan.seeds.forEach { seed -> preflight(seed.source, seed.target) }

        CompyStorageContract.CARD_MEDIA_DIRECTORIES.forEach { name -> ensureDirectory(File(cardRoot, name)) }
        CompyStorageContract.INITIALIZED_DESTINATION_PATHS.forEach { path ->
            ensureDirectory(File(cardCompyDirectory, path))
        }

        val counts = CopyCounts()
        plan.seeds.forEach { seed -> copyTree(seed.source, seed.target, operationId, counts) }
        return CompyCardInitializationResult(
            seededProjects = plan.defaultProjectCount,
            copiedFiles = counts.copied,
            reusedFiles = counts.reused,
        )
    }

    fun verify(
        internalCompyDirectory: File,
        cardRoot: File,
    ) {
        if (!internalCompyDirectory.isDirectory || internalCompyDirectory.listFiles() == null) {
            throw IOException("Internal Compy recovery seed is unreadable")
        }
        if (!cardRoot.isDirectory || cardRoot.listFiles() == null) {
            throw IOException("The remounted SD card root is unreadable")
        }

        val cardCompyDirectory = File(cardRoot, CompyStorageContract.ROOT)
        val plan = initializationPlan(internalCompyDirectory, cardCompyDirectory)
        CompyStorageContract.CARD_MEDIA_DIRECTORIES.forEach { name ->
            requireDirectory(File(cardRoot, name))
        }
        CompyStorageContract.INITIALIZED_DESTINATION_PATHS.forEach { path ->
            requireDirectory(File(cardCompyDirectory, path))
        }
        plan.seeds.forEach { seed -> verifyTree(seed.source, seed.target) }
    }

    private fun initializationPlan(
        internalCompyDirectory: File,
        cardCompyDirectory: File,
    ): InitializationPlan {
        val stockSource = File(internalCompyDirectory, "stock")
        val apkSource = File(internalCompyDirectory, "launcher/apk")
        val repairSource = File(internalCompyDirectory, "repair")
        val defaultProjects = readDefaultStockProjects(stockSource)
        requireApkSeed(apkSource)
        requireRepairSeed(repairSource)
        val seeds =
            defaultProjects.map { (name, source) ->
                SeedTree(source, File(cardCompyDirectory, "projects/$name"))
            } +
                listOf(
                    SeedTree(stockSource, File(cardCompyDirectory, "stock")),
                    SeedTree(apkSource, File(cardCompyDirectory, "launcher/apk")),
                    SeedTree(repairSource, File(cardCompyDirectory, "repair")),
                )
        return InitializationPlan(seeds, defaultProjects.size)
    }

    private fun readDefaultStockProjects(stockSource: File): List<Pair<String, File>> {
        val programs = File(stockSource, "programs")
        val programDirectories = finalChildren(programs).filter(File::isDirectory)
        if (programDirectories.isEmpty()) throw IOException("Internal stock has no complete programs")
        return programDirectories.sortedBy(File::getName).map { program ->
            if (!SAFE_NAME_PATTERN.matches(program.name)) {
                throw IOException("Internal stock has an invalid program name: ${program.name}")
            }
            val versions = readIndex(File(program, "versions.txt"), "versions index for ${program.name}")
            if (versions.toSet().size != versions.size) {
                throw IOException("Internal stock repeats a version for ${program.name}")
            }
            versions.forEach { token ->
                if (!SAFE_NAME_PATTERN.matches(token) || !File(program, token).isDirectory) {
                    throw IOException("Internal stock has an incomplete version for ${program.name}: $token")
                }
            }
            val default = readIndex(File(program, "stock.txt"), "default index for ${program.name}")
            if (default.size != 1 || default.single() !in versions) {
                throw IOException("Internal stock has an invalid default for ${program.name}")
            }
            val source = File(program, default.single())
            if (!File(source, "main.lua").isFile) {
                throw IOException("Default stock project has no main.lua: ${program.name}")
            }
            program.name to source
        }
    }

    private fun readIndex(file: File, description: String): List<String> {
        if (!file.isFile || !file.canRead()) throw IOException("Missing $description")
        val lines = file.readLines(StandardCharsets.UTF_8)
        if (lines.isEmpty() || lines.any(String::isBlank)) throw IOException("Invalid $description")
        return lines
    }

    private fun requireApkSeed(directory: File) {
        val files = finalFiles(directory, "recovery APK")
        val patterns =
            listOf(
                CompyStorageContract.IDE_RELEASE_APK_PATTERN,
                CompyStorageContract.IDE_DEBUG_APK_PATTERN,
                CompyStorageContract.LAUNCHER_RELEASE_APK_PATTERN,
                CompyStorageContract.LAUNCHER_DEBUG_APK_PATTERN,
            ).associateWith(::artifactNameRegex)
        val hasIde = files.any { file ->
            patterns.getValue(CompyStorageContract.IDE_RELEASE_APK_PATTERN).matches(file.name) ||
                patterns.getValue(CompyStorageContract.IDE_DEBUG_APK_PATTERN).matches(file.name)
        }
        val hasLauncher = files.any { file ->
            patterns.getValue(CompyStorageContract.LAUNCHER_RELEASE_APK_PATTERN).matches(file.name) ||
                patterns.getValue(CompyStorageContract.LAUNCHER_DEBUG_APK_PATTERN).matches(file.name)
        }
        if (!hasIde || !hasLauncher) throw IOException("Internal recovery APK seed is incomplete")
    }

    private fun requireRepairSeed(directory: File) {
        val files = finalFiles(directory, "Compy Repair")
        val missingPlatforms =
            CompyStorageContract.REPAIR_SUPPORTED_PLATFORMS.filter { platform ->
                files.none { file -> repairBundlePattern(platform).matches(file.name) }
            }
        if (files.none { it.name == "README.txt" } || missingPlatforms.isNotEmpty()) {
            throw IOException("Internal Compy Repair seed is incomplete")
        }
    }

    private fun repairBundlePattern(platform: String): Regex =
        Regex(
            "^Compy-Repair-[A-Za-z0-9._-]+-${Regex.escape(platform)}[.]zip$",
        )

    private fun finalFiles(directory: File, description: String): List<File> {
        if (!directory.isDirectory) throw IOException("Internal $description seed is missing")
        return finalChildren(directory).filter(File::isFile)
    }

    private fun artifactNameRegex(pattern: String): Regex {
        val parts = pattern.split("<version-token>")
        require(parts.size == 2) { "Invalid APK artifact pattern: $pattern" }
        return Regex("^${Regex.escape(parts[0])}[A-Za-z0-9._-]+${Regex.escape(parts[1])}$")
    }

    private fun preflight(source: File, target: File) {
        if (!source.isDirectory && !source.isFile) throw IOException("Unsupported seed entry: $source")
        if (target.exists()) {
            if (source.isDirectory != target.isDirectory || source.isFile != target.isFile) {
                throw IOException("SD initialization collision: $target")
            }
            if (source.isFile && sha256(source) != sha256(target)) {
                throw IOException("SD initialization would overwrite different content: $target")
            }
        }
        if (source.isDirectory) {
            finalChildren(source).forEach { child -> preflight(child, File(target, child.name)) }
        }
    }

    private fun preflightDirectory(directory: File) {
        if (directory.exists() && !directory.isDirectory) {
            throw IOException("SD initialization directory collision: $directory")
        }
    }

    private fun requireDirectory(directory: File) {
        if (!directory.isDirectory || directory.listFiles() == null) {
            throw IOException("SD initialization directory did not persist: $directory")
        }
    }

    private fun verifyTree(source: File, target: File) {
        if (source.isDirectory) {
            requireDirectory(target)
            finalChildren(source).forEach { child ->
                verifyTree(child, File(target, child.name))
            }
            return
        }
        if (!target.isFile || sha256(source) != sha256(target)) {
            throw IOException("SD initialization file did not persist: $target")
        }
    }

    private fun copyTree(
        source: File,
        target: File,
        operationId: String,
        counts: CopyCounts,
    ) {
        if (source.isDirectory) {
            ensureDirectory(target)
            finalChildren(source)
                .sortedWith(compareBy<File>({ copyOrder(it.name) }, File::getName))
                .forEach { child -> copyTree(child, File(target, child.name), operationId, counts) }
            return
        }
        if (target.isFile) {
            if (sha256(source) != sha256(target)) {
                throw IOException("SD initialization target changed during copy: $target")
            }
            counts.reused += 1
            return
        }
        atomicCopyFile(source, target, operationId)
        counts.copied += 1
    }

    private fun atomicCopyFile(source: File, target: File, operationId: String) {
        ensureDirectory(target.parentFile ?: throw IOException("Seed target has no parent: $target"))
        val staging = File(target.parentFile, ".incoming.$operationId.${target.name}")
        if (staging.exists()) throw IOException("SD initialization stage already exists: $staging")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (sha256(source) != sha256(staging)) throw IOException("SD initialization copy did not verify: $target")
            if (target.exists()) throw IOException("SD initialization target appeared during copy: $target")
            if (!staging.renameTo(target)) throw IOException("Could not promote SD initialization file: $target")
        } finally {
            if (staging.exists() && !staging.delete()) {
                throw IOException("Could not remove SD initialization stage: $staging")
            }
        }
    }

    private fun finalChildren(directory: File): List<File> {
        val children = directory.listFiles() ?: throw IOException("Unreadable seed directory: $directory")
        return children.filterNot { child -> isReserved(child.name) }
    }

    private fun isReserved(name: String): Boolean =
        name.startsWith(".incoming.") ||
            name.startsWith(".restore.") ||
            name.startsWith(".recovered.") ||
            name == ".locks"

    private fun copyOrder(name: String): Int =
        when (name) {
            "versions.txt" -> 1
            "stock.txt" -> 2
            else -> 0
        }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) throw IOException("Could not create directory: $directory")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private val OPERATION_ID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SAFE_NAME_PATTERN = Regex("^[A-Za-z0-9._-]+$")
}
