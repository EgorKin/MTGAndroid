package io.github.romanvht.mtgandroid.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    companion object {
        private const val TAG = "QuickTileService"
        private var instance: QuickTileService? = null

        fun updateTile() {
            instance?.updateStatus()
        }
    }

    private var appTile: Tile? = null

    override fun onStartListening() {
        super.onStartListening()
        instance = this
        appTile = qsTile
        updateStatus()
    }

    override fun onStopListening() {
        super.onStopListening()
        instance = null
        appTile = null
    }

    override fun onClick() {
        super.onClick()
        handleClick()
    }

    private fun handleClick() {
        if (MtgProxyService.isRunning()) {
            ServiceManager.stop(this)
            setState(Tile.STATE_INACTIVE)
        } else {
            ServiceManager.start(this)
            setState(Tile.STATE_ACTIVE)
        }
        Log.i(TAG, "Tile clicked")
    }

    private fun updateStatus() {
        val newState = if (MtgProxyService.isRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        setState(newState)
    }

    private fun setState(state: Int) {
        appTile?.apply {
            this.state = state
            updateTile()
        }
    }
}