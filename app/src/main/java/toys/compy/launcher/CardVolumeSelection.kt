/*
 * Copyright (c) 2026 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import android.os.storage.StorageVolume
import java.io.File
import java.util.Locale

/**
 * Chooses the SD card among Android's removable volumes.
 *
 * Android lists USB mass storage as removable too. A micro:bit plugged in over USB appears as a
 * second removable drive whose FAT serial has the same shape as a card UUID, so taking the first
 * removable volume can inspect, write to, or restore projects onto that drive instead of the card.
 *
 * StorageVolume offers no public way to tell an SD card from USB storage. The block device behind
 * each volume's vold mount does: MMC block devices use major 179, while USB mass storage appears
 * as a SCSI disk. Those mount lines are visible in every app's own /proc/self/mounts. An SD
 * card in a USB card reader is a SCSI disk too, so only a card in the built-in slot counts.
 *
 * A volume counts as the card only when its mount line shows the MMC device. A volume without one
 * is either still mounting, and the boot mount wait keeps polling until its line appears, or
 * mounted where the lookup cannot see it. A card reader or a micro:bit drive in that state must
 * never be treated as the card, so without an identified card the launcher uses internal storage.
 */
internal object CardVolumeSelection {
    const val MMC_BLOCK_MAJOR = 179

    private val VOLD_MOUNT = Regex("""^/dev/block/vold/public:(\d+),\d+\s+/mnt/media_rw/(\S+)\s""")

    fun cardVolume(volumes: List<StorageVolume>): StorageVolume? =
        select(volumes.filter(StorageVolume::isRemovable), { it.uuid }, readBlockMajorsByUuid())

    /** Removable volumes whose block device no mount line shows; the launcher uses none of them. */
    fun unidentifiedVolumes(volumes: List<StorageVolume>): List<StorageVolume> =
        unidentified(volumes.filter(StorageVolume::isRemovable), { it.uuid }, readBlockMajorsByUuid())

    /** The first volume backed by an MMC device, or null when no volume is identified as one. */
    fun <T> select(candidates: List<T>, uuidOf: (T) -> String?, majorsByUuid: Map<String, Int>): T? =
        candidates.firstOrNull { majorOf(it, uuidOf, majorsByUuid) == MMC_BLOCK_MAJOR }

    fun <T> unidentified(candidates: List<T>, uuidOf: (T) -> String?, majorsByUuid: Map<String, Int>): List<T> =
        candidates.filter { majorOf(it, uuidOf, majorsByUuid) == null }

    private fun <T> majorOf(candidate: T, uuidOf: (T) -> String?, majorsByUuid: Map<String, Int>): Int? =
        uuidOf(candidate)?.let { majorsByUuid[it.lowercase(Locale.US)] }

    fun blockMajorsByUuid(mounts: String): Map<String, Int> =
        mounts.lineSequence()
            .mapNotNull { VOLD_MOUNT.find(it) }
            .associate { it.groupValues[2].lowercase(Locale.US) to it.groupValues[1].toInt() }

    private fun readBlockMajorsByUuid(): Map<String, Int> =
        runCatching { blockMajorsByUuid(File("/proc/self/mounts").readText()) }.getOrDefault(emptyMap())
}
