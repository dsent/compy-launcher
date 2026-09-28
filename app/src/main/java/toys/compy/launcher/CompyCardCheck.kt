/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

enum class CompyCardCondition {
    HEALTHY,
    MISSING,
    UNREADABLE,
    UNINITIALIZED,
    IDENTITY_INVALID,
    UNWRITABLE,
}

data class CompyCardCheckResult(
    val condition: CompyCardCondition,
    val cardId: String? = null,
    val missingDestinations: List<String> = emptyList(),
    val detail: String? = null,
) {
    val healthy: Boolean = condition == CompyCardCondition.HEALTHY
    val repairable: Boolean = condition == CompyCardCondition.UNINITIALIZED
}

internal data class RemovableVolumeSnapshot(
    val state: String,
    val root: File?,
    val uuid: String?,
)

object CompyCardCheck {
    fun inspect(context: Context): CompyCardCheckResult =
        withInitializationRecord(context, inspectCard(context))

    /** The card's own condition, without an initialization failure this device recorded for it. */
    fun inspectCard(context: Context): CompyCardCheckResult {
        val volume = removableVolume(context) ?: return withoutIdentifiedCard(unidentifiedVolumes(context))
        return inspect(volume)
    }

    /**
     * Reports a card whose initialization failed verification on this device as unreadable. The
     * record is kept on this device, so checking the card again never clears it.
     */
    fun withInitializationRecord(context: Context, result: CompyCardCheckResult): CompyCardCheckResult =
        withInitializationRecord(result, result.cardId?.let { KioskState.cardInitializationFailure(context, it) })

    internal fun withInitializationRecord(
        result: CompyCardCheckResult,
        recordedFailure: String?,
    ): CompyCardCheckResult =
        if (recordedFailure == null) {
            result
        } else {
            result.copy(condition = CompyCardCondition.UNREADABLE, detail = recordedFailure)
        }

    /**
     * The result when no removable volume is identified as the card slot; none of them is used.
     * Removable storage that is present but not mounted may be a card that is still mounting or
     * cannot be mounted, so it reads as unreadable and is checked again. Otherwise there is no card.
     */
    internal fun withoutIdentifiedCard(unidentified: List<RemovableVolumeSnapshot>): CompyCardCheckResult {
        fun describe(volumes: List<RemovableVolumeSnapshot>) =
            volumes.joinToString { "${it.uuid ?: "no UUID"} (${it.state})" }
        val present =
            unidentified.filter {
                it.state != Environment.MEDIA_REMOVED && it.state != Environment.MEDIA_BAD_REMOVAL
            }
        val unmounted =
            present.filter {
                it.state != Environment.MEDIA_MOUNTED && it.state != Environment.MEDIA_MOUNTED_READ_ONLY
            }
        return when {
            unmounted.isNotEmpty() ->
                CompyCardCheckResult(
                    condition = CompyCardCondition.UNREADABLE,
                    detail = "Removable storage is present but not mounted, so no volume is identified " +
                        "as the SD card slot: ${describe(unmounted)}",
                )
            present.isNotEmpty() ->
                CompyCardCheckResult(
                    condition = CompyCardCondition.MISSING,
                    detail = "Removable storage not identified as the SD card slot, left unused: " +
                        describe(present),
                )
            else -> CompyCardCheckResult(CompyCardCondition.MISSING)
        }
    }

    internal fun inspect(
        volume: RemovableVolumeSnapshot?,
        writeProbe: (File) -> Unit = ::writeProbe,
    ): CompyCardCheckResult {
        if (volume == null) return CompyCardCheckResult(CompyCardCondition.MISSING)
        if (
            volume.state != Environment.MEDIA_MOUNTED &&
            volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
        ) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.UNREADABLE,
                detail = "The removable volume is ${volume.state}",
            )
        }

        val root = volume.root
            ?: return CompyCardCheckResult(
                condition = CompyCardCondition.UNREADABLE,
                detail = "The removable volume root is unavailable",
            )
        if (!root.isDirectory || root.listFiles() == null) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.UNREADABLE,
                detail = "The removable volume root is unreadable",
            )
        }

        val uuid = volume.uuid?.trim()?.lowercase(Locale.US)
        if (uuid == null || !Regex(CompyStorageContract.CARD_UUID_PATTERN).matches(uuid)) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.UNREADABLE,
                detail = "The removable volume has no supported filesystem UUID",
            )
        }
        val cardId = CompyStorageContract.CARD_ID_PREFIX + uuid

        try {
            writeProbe(root)
        } catch (error: IOException) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.UNWRITABLE,
                cardId = cardId,
                detail = error.message,
            )
        }

        try {
            CompyStorage.validateRemovableIdentity(root, cardId)
        } catch (error: IOException) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.IDENTITY_INVALID,
                cardId = cardId,
                detail = error.message,
            )
        }

        val compyDirectory = File(root, CompyStorageContract.ROOT)
        val missing =
            CompyStorageContract.INITIALIZED_DESTINATION_PATHS.filter { relativePath ->
                !File(compyDirectory, relativePath).isDirectory
            }
        if (missing.isNotEmpty()) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.UNINITIALIZED,
                cardId = cardId,
                missingDestinations = missing,
            )
        }

        val projects = File(compyDirectory, "projects")
        if (projects.listFiles() == null) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.UNREADABLE,
                cardId = cardId,
                detail = "The projects directory is unreadable",
            )
        }

        try {
            writeProbe(projects)
        } catch (error: IOException) {
            return CompyCardCheckResult(
                condition = CompyCardCondition.UNWRITABLE,
                cardId = cardId,
                detail = error.message,
            )
        }
        return CompyCardCheckResult(CompyCardCondition.HEALTHY, cardId = cardId)
    }

    internal fun removableVolume(context: Context): RemovableVolumeSnapshot? {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val volume = CardVolumeSelection.cardVolume(storageManager.storageVolumes) ?: return null
        return RemovableVolumeSnapshot(
            state = volume.state,
            root = storageVolumeRoot(context, volume),
            uuid = volume.uuid,
        )
    }

    private fun unidentifiedVolumes(context: Context): List<RemovableVolumeSnapshot> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        return CardVolumeSelection.unidentifiedVolumes(storageManager.storageVolumes).map { volume ->
            RemovableVolumeSnapshot(state = volume.state, root = null, uuid = volume.uuid)
        }
    }

    private fun storageVolumeRoot(context: Context, volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        return context.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { Environment.isExternalStorageRemovable(it) }
            ?.let(::externalFilesStorageRoot)
    }

    private fun externalFilesStorageRoot(externalFilesDirectory: File): File? {
        var current: File? = externalFilesDirectory
        while (current != null && current.name != "Android") {
            current = current.parentFile
        }
        return current?.parentFile
    }

    private fun writeProbe(directory: File) {
        val operationId = UUID.randomUUID().toString().lowercase(Locale.US)
        val probe = File(directory, ".incoming.$operationId.card-check")
        var failure: IOException? = null
        try {
            if (!probe.createNewFile()) throw IOException("Could not create the SD-card write probe")
            FileOutputStream(probe).use { output ->
                output.write(PROBE_BYTES)
                output.fd.sync()
            }
        } catch (error: IOException) {
            failure = error
        } finally {
            if (probe.exists() && !probe.delete()) {
                failure = IOException("Could not delete the SD-card write probe", failure)
            }
        }
        failure?.let { throw it }
    }

    private val PROBE_BYTES = byteArrayOf(0x43, 0x50, 0x59)
}
