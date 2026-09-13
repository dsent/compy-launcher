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
 */
internal object CardVolumeSelection {
    const val MMC_BLOCK_MAJOR = 179

    private val VOLD_MOUNT = Regex("""^/dev/block/vold/public:(\d+),\d+\s+/mnt/media_rw/(\S+)\s""")

    fun cardVolume(volumes: List<StorageVolume>): StorageVolume? =
        select(volumes.filter(StorageVolume::isRemovable), { it.uuid }, readBlockMajorsByUuid())

    /**
     * The first volume backed by an MMC device, or else the first whose block device is unknown.
     *
     * A volume without a mount line is still unmounted or being checked at boot and may be the
     * card, so it stays a candidate. A mounted volume on any other block device is USB storage and
     * is never the card. Without readable mount lines every volume is unknown, which keeps the
     * earlier first-removable choice.
     */
    fun <T> select(candidates: List<T>, uuidOf: (T) -> String?, majorsByUuid: Map<String, Int>): T? {
        fun majorOf(candidate: T): Int? = uuidOf(candidate)?.let { majorsByUuid[it.lowercase(Locale.US)] }
        return candidates.firstOrNull { majorOf(it) == MMC_BLOCK_MAJOR }
            ?: candidates.firstOrNull { majorOf(it) == null }
    }

    fun blockMajorsByUuid(mounts: String): Map<String, Int> =
        mounts.lineSequence()
            .mapNotNull { VOLD_MOUNT.find(it) }
            .associate { it.groupValues[2].lowercase(Locale.US) to it.groupValues[1].toInt() }

    private fun readBlockMajorsByUuid(): Map<String, Int> =
        runCatching { blockMajorsByUuid(File("/proc/self/mounts").readText()) }.getOrDefault(emptyMap())
}
