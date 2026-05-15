package toys.compy.launcher

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class KioskTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        KioskState.toggleMaintenance(this)
        updateTile()

        if (KioskState.isMaintenanceActive(this)) {
            val intent = Intent(this, KioskControlActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = KioskState.isMaintenanceActive(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }
}
