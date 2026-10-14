package com.personal.base.shell

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.personal.base.PersonalBaseApplication

/**
 * Android Quick Settings Tile for quick toggling platform foreground execution and inspecting status.
 *
 * Shell-level: bound to the Application container, so it cannot live in the core library.
 */
@RequiresApi(Build.VERSION_CODES.N)
class PlatformQuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? PersonalBaseApplication ?: return
        val bgManager = app.container.backgroundManager

        if (bgManager.status.value.isForegroundServiceRunning) {
            bgManager.stopForegroundService()
        } else {
            bgManager.startForegroundService()
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val app = application as? PersonalBaseApplication
        val isRunning = app?.container?.backgroundManager?.status?.value?.isForegroundServiceRunning == true

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isRunning) "Star Active" else "Star Inactive"
        tile.updateTile()
    }
}
