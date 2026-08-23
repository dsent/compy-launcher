/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

object CompyStorage {
    fun removableCompyDirectory(context: Context): File {
        val root = removableStorageRoot(context)
            ?: throw IOException("No mounted removable storage is available")
        return File(root, "Documents/compy")
    }

    private fun removableStorageRoot(context: Context): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = context.getSystemService(StorageManager::class.java)
            return storageManager.storageVolumes
                .firstOrNull { volume ->
                    volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED
                }
                ?.directory
        }

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
}
