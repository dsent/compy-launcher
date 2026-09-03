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
    fun inspect(context: Context): CompyCardCheckResult {
        val result = inspect(removableVolume(context))
        val cardId = result.cardId ?: return result
        val persistenceFailure = KioskState.cardInitializationFailure(context, cardId)
        return if (persistenceFailure == null) {
            result
        } else {
            result.copy(
                condition = CompyCardCondition.UNREADABLE,
                detail = persistenceFailure,
            )
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

    private fun removableVolume(context: Context): RemovableVolumeSnapshot? {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val volume = storageManager.storageVolumes.firstOrNull(StorageVolume::isRemovable) ?: return null
        return RemovableVolumeSnapshot(
            state = volume.state,
            root = storageVolumeRoot(context, volume),
            uuid = volume.uuid,
        )
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
