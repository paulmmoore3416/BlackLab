package com.blacklab.app.assistant

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.blacklab.app.MainActivity

/** Quick Settings tile: one tap from the notification shade starts a voice conversation. */
class AssistTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; subtitle = "Talk"; updateTile() }
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction("com.blacklab.app.ASSIST")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivityAndCollapse(PendingIntent.getActivity(this, 4401, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
}
