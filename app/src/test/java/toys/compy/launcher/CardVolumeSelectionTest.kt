package toys.compy.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardVolumeSelectionTest {
    private data class Volume(val uuid: String?)

    private fun select(volumes: List<Volume>, majors: Map<String, Int>): Volume? =
        CardVolumeSelection.select(volumes, { it.uuid }, majors)

    @Test
    fun mountLinesGiveEachVolumeItsBlockDevice() {
        // The card lines are copied from a Compy's Launcher process; the USB line has the shape of a
        // connected micro:bit drive.
        val mounts =
            """
            /dev/block/vold/public:179,17 /mnt/media_rw/6BBF-F260 exfat rw,dirsync,nosuid,nodev,noexec,noatime 0 0
            /dev/fuse /storage/6BBF-F260 fuse rw,lazytime,nosuid,nodev,noexec,noatime,allow_other 0 0
            /dev/block/vold/public:179,17 /mnt/pass_through/0/6BBF-F260 exfat rw,dirsync,nosuid,nodev 0 0
            /dev/block/vold/public:8,0 /mnt/media_rw/2702-1974 vfat rw,dirsync,nosuid,nodev,noexec,noatime 0 0
            /dev/fuse /storage/2702-1974 fuse rw,lazytime,nosuid,nodev,noexec,noatime,allow_other 0 0
            """.trimIndent()

        assertEquals(mapOf("6bbf-f260" to 179, "2702-1974" to 8), CardVolumeSelection.blockMajorsByUuid(mounts))
    }

    @Test
    fun cardIsChosenOverUsbStorageListedFirst() {
        val usb = Volume("2702-1974")
        val card = Volume("6BBF-F260")

        assertEquals(card, select(listOf(usb, card), mapOf("2702-1974" to 8, "6bbf-f260" to 179)))
    }

    @Test
    fun cardStillMountingIsChosenOverMountedUsbStorage() {
        val usb = Volume("2702-1974")
        val mountingCard = Volume("6BBF-F260")

        assertEquals(mountingCard, select(listOf(usb, mountingCard), mapOf("2702-1974" to 8)))
    }

    @Test
    fun usbStorageAloneIsNeverTheCard() {
        assertNull(select(listOf(Volume("2702-1974")), mapOf("2702-1974" to 8)))
    }

    @Test
    fun withoutMountLinesTheFirstRemovableVolumeIsKept() {
        val first = Volume("2702-1974")

        assertEquals(first, select(listOf(first, Volume("6BBF-F260")), emptyMap()))
        assertEquals(Volume(null), select(listOf(Volume(null)), emptyMap()))
    }
}
