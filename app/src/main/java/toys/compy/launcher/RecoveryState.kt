/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.content.Context
import java.io.File
import java.io.IOException

/** Cross-process gate that prevents the UI process from re-arming during recovery. */
object RecoveryState {
    private const val MARKER_NAME = "recovery-requested"

    fun isRequested(context: Context): Boolean {
        return marker(context).isFile
    }

    @Throws(IOException::class)
    fun request(context: Context) {
        val marker = marker(context)
        marker.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Could not create recovery state directory")
            }
        }
        if (!marker.createNewFile() && !marker.isFile) {
            throw IOException("Could not persist recovery request")
        }
    }

    @Throws(IOException::class)
    fun clear(context: Context) {
        val marker = marker(context)
        if (marker.exists() && !marker.delete()) {
            throw IOException("Could not clear recovery request")
        }
    }

    private fun marker(context: Context): File {
        return File(context.noBackupFilesDir, MARKER_NAME)
    }
}
