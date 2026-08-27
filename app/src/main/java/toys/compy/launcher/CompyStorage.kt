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
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Locale

data class MountedCompyStorage(
    val id: String,
    val compyDirectory: File,
)

object CompyStorage {
    fun removableCompyDirectory(context: Context): File {
        return removableStorage(context).compyDirectory
    }

    fun removableStorage(context: Context): MountedCompyStorage {
        val volume = removableStorageVolume(context)
            ?: throw IOException("No mounted removable storage is available")
        val root = storageVolumeRoot(context, volume)
            ?: throw IOException("The mounted removable storage root is unavailable")
        val uuid = volume.uuid?.lowercase(Locale.US)
            ?: throw IOException("The mounted removable storage has no filesystem UUID")
        if (!Regex(CompyStorageContract.CARD_UUID_PATTERN).matches(uuid)) {
            throw IOException("The mounted removable storage has an unsupported filesystem UUID")
        }
        val cardId = CompyStorageContract.CARD_ID_PREFIX + uuid
        validateRemovableIdentity(root, cardId)
        return MountedCompyStorage(
            id = cardId,
            compyDirectory = File(root, CompyStorageContract.ROOT),
        )
    }

    fun internalStorage(context: Context): MountedCompyStorage {
        val compyDirectory = File(internalStorageRoot(context), CompyStorageContract.ROOT)
        val identityFile = File(compyDirectory, CompyStorageContract.INTERNAL_IDENTITY_FILE)
        val deviceId = readInternalDeviceId(identityFile)
        return MountedCompyStorage(deviceId, compyDirectory)
    }

    internal fun readInternalDeviceId(identityFile: File): String {
        if (!identityFile.isFile || !identityFile.canRead()) {
            throw IOException("Internal Compy identity is missing: $identityFile")
        }
        val identity =
            try {
                JSONObject(identityFile.readText())
            } catch (error: JSONException) {
                throw IOException("Internal Compy identity is invalid: $identityFile", error)
            }
        if (
            identity.optString("format") != CompyStorageContract.INTERNAL_IDENTITY_FORMAT ||
            identity.opt("format_ver") != CompyStorageContract.INTERNAL_IDENTITY_FORMAT_VERSION ||
            identity.opt("storage_schema_ver") != CompyStorageContract.STORAGE_SCHEMA_VERSION
        ) {
            throw IOException("Internal Compy identity uses an unsupported format: $identityFile")
        }
        val deviceId = identity.optString("device_id")
        if (!DEVICE_ID_PATTERN.matches(deviceId)) {
            throw IOException("Internal Compy identity has an invalid device ID: $identityFile")
        }
        if ((identity.opt("serial") as? String).isNullOrEmpty()) {
            throw IOException("Internal Compy identity has no hardware serial: $identityFile")
        }
        return deviceId
    }

    internal fun validateRemovableIdentity(root: File, cardId: String) {
        val identityFiles = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(CompyStorageContract.CARD_IDENTITY_FILE_SUFFIX) }
            ?: throw IOException("The mounted removable storage root is unreadable: $root")
        if (identityFiles.isEmpty()) return
        if (identityFiles.size != 1) {
            throw IOException("The SD card has multiple identity files; repair its identity before continuing")
        }
        val identityFile = identityFiles.single()
        val identity =
            try {
                JSONObject(identityFile.readText())
            } catch (error: JSONException) {
                throw IOException("The SD card identity is invalid: $identityFile", error)
            }
        if (
            identity.optString("format") != CompyStorageContract.CARD_IDENTITY_FORMAT ||
            identity.opt("format_ver") != CompyStorageContract.CARD_IDENTITY_FORMAT_VERSION ||
            identity.opt("storage_schema_ver") != CompyStorageContract.STORAGE_SCHEMA_VERSION
        ) {
            throw IOException("The SD card identity uses an unsupported format: $identityFile")
        }
        val label = identity.opt("label") as? String
            ?: throw IOException("The SD card identity has no label: $identityFile")
        if (
            label.length > CompyStorageContract.CARD_LABEL_MAX_CHARACTERS ||
            (!Regex(CompyStorageContract.CARD_LABEL_PATTERN).matches(label) &&
                !Regex(CompyStorageContract.LEGACY_CARD_LABEL_PATTERN).matches(label))
        ) {
            throw IOException("The SD card identity has an invalid label: $identityFile")
        }
        val expectedFileName = label + CompyStorageContract.CARD_IDENTITY_FILE_SUFFIX
        val expectedCheck =
            MessageDigest.getInstance("SHA-256")
                .digest(label.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
                .take(CompyStorageContract.LABEL_CHECK_LENGTH)
        if (
            identityFile.name != expectedFileName ||
            identity.opt("label_check") != expectedCheck ||
            identity.opt("card_id") != cardId
        ) {
            throw IOException("The SD card identity signals disagree; repair its identity before continuing")
        }
    }

    private fun removableStorageVolume(context: Context): StorageVolume? {
        val storageManager = context.getSystemService(StorageManager::class.java)
        return storageManager.storageVolumes.firstOrNull { volume ->
            volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED
        }
    }

    private fun storageVolumeRoot(context: Context, volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        return context.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { Environment.isExternalStorageRemovable(it) }
            ?.let(::externalFilesStorageRoot)
    }

    @Suppress("DEPRECATION")
    private fun internalStorageRoot(context: Context): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = context.getSystemService(StorageManager::class.java)
            storageManager.primaryStorageVolume.directory?.let { return it }
        }
        return Environment.getExternalStorageDirectory()
    }

    private fun externalFilesStorageRoot(externalFilesDirectory: File): File? {
        var current: File? = externalFilesDirectory
        while (current != null && current.name != "Android") {
            current = current.parentFile
        }
        return current?.parentFile
    }

    private val DEVICE_ID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
}
