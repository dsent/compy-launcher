/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

/**
 * Native copy of the Compy product storage contract values.
 *
 * The launcher deliberately builds without fetching product metadata. The
 * product repository's conformance gate checks this object for drift.
 */
object CompyStorageContract {
    const val STORAGE_SCHEMA_VERSION = 1
    const val ROOT = "Documents/compy"

    const val PROJECTS_DESTINATION = "Documents/compy/projects"
    const val STOCK_DESTINATION = "Documents/compy/stock"
    const val APK_DESTINATION = "Documents/compy/launcher/apk"
    const val BACKUPS_DESTINATION = "Documents/compy/backups"
    const val UPDATES_DESTINATION = "Documents/compy/updates"
    const val REPAIR_DESTINATION = "Documents/compy/repair"

    const val CARD_IDENTITY_FORMAT = "compy-card"
    const val CARD_IDENTITY_FORMAT_VERSION = 1
    const val CARD_IDENTITY_FILE_SUFFIX = ".sdcard.json"
    const val INTERNAL_IDENTITY_FORMAT = "compy-internal"
    const val INTERNAL_IDENTITY_FORMAT_VERSION = 1
    const val INTERNAL_IDENTITY_FILE = "device.json"
    const val SNAPSHOT_FORMAT = "compy-snapshot"
    const val SNAPSHOT_FORMAT_VERSION = 1
    const val BACKUP_LOCK_FORMAT = "compy-backup-lock"
    const val BACKUP_LOCK_FORMAT_VERSION = 1
    const val RESTORE_JOURNAL_FORMAT = "compy-restore"
    const val RESTORE_JOURNAL_FORMAT_VERSION = 1
    const val APK_RESTORE_JOURNAL_FORMAT = "compy-apk-restore"
    const val APK_RESTORE_JOURNAL_FORMAT_VERSION = 1

    const val CARD_ID_PREFIX = "fs:"
    const val CARD_UUID_PATTERN = "^[0-9a-f]+(-[0-9a-f]+)*$"
    const val CARD_LABEL_MAX_CHARACTERS = 11
    const val CARD_LABEL_PATTERN = "^CompySD[0-9]{4}$"
    const val LEGACY_CARD_LABEL_PATTERN = "^Compy_[A-Za-z0-9][0-9]{4}$"
    const val LABEL_CHECK_LENGTH = 8
    const val SHA256_LENGTH = 64

    const val SNAPSHOT_FIRST_ORDINAL = 1L
    const val SNAPSHOT_ORDINAL_PATTERN = "^[1-9][0-9]*$"
    const val SNAPSHOT_DIRECTORY_PATTERN = "^[1-9][0-9]*-[0-9]{8}-[0-9]{6}$"
    const val RECOVERED_SNAPSHOT_DIRECTORY_PATTERN = "^[.]recovered[.][0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    const val SNAPSHOT_RETENTION_PER_SOURCE = 3
    const val BACKUP_FREE_SPACE_THRESHOLD_MB = 2048L
    const val NAME_TIMESTAMP_FORMAT = "YYYYMMDD-HHMMSS"

    const val APK_VERSION_TOKEN_PATTERN = "^[A-Za-z0-9._-]+$"
    const val APK_INVALID_CHARACTER_REPLACEMENT = "-"
    const val APK_COLLAPSE_INVALID_CHARACTER_RUNS = true
    const val IDE_PACKAGE = "toys.compy.ide"
    const val LAUNCHER_PACKAGE = "toys.compy.launcher"
    const val IDE_RELEASE_APK_PATTERN = "Compy-IDE-<version-token>.apk"
    const val IDE_DEBUG_APK_PATTERN = "Compy-IDE-debug-<version-token>.apk"
    const val LAUNCHER_RELEASE_APK_PATTERN = "toys.compy.launcher-<version-token>.apk"
    const val LAUNCHER_DEBUG_APK_PATTERN = "toys.compy.launcher-debug-<version-token>.apk"

    val SOURCE_KINDS = listOf("card", "internal")
    val DESTINATION_ORDER = listOf("card", "internal")
    val WRITERS = listOf("launcher", "host")
    val RESTORE_PHASES = listOf("staged", "old-preserved", "promoted")
    val APK_RESTORE_PHASES = listOf("prepared", "current-removed", "candidate-installed", "verified")
    val INITIALIZED_DESTINATIONS = listOf("projects", "stock", "apk", "backups", "updates", "repair")
    val INITIALIZED_DESTINATION_PATHS =
        listOf(
            PROJECTS_DESTINATION,
            STOCK_DESTINATION,
            APK_DESTINATION,
            BACKUPS_DESTINATION,
            UPDATES_DESTINATION,
            REPAIR_DESTINATION,
        ).map { destination -> destination.removePrefix("$ROOT/") }
    val CARD_MEDIA_DIRECTORIES = listOf("Download", "Movies", "Music", "Pictures")
    val REPAIR_SUPPORTED_PLATFORMS = listOf("linux-amd64")
    val APK_INSTALL_ORDER = listOf("IDE", "Launcher")
}
