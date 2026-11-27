package com.blacklab.app.assistant

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.blacklab.app.MainActivity
import com.blacklab.app.link.AgentOps

/** Quick Settings tile "Desktop AI" (5.19): Idle / Working / Offline at a pull-down; a tap opens Agent Ops. */
class AgentOpsTile : TileService() {
    override fun onStartListening() {
        AgentOps.load(applicationContext)
        val online = DesktopAi.status.value.state == DesktopAi.State.ONLINE
        val s = AgentOps.snapshot.value
        qsTile?.apply {
            state = if (AgentOps.status(s, online) == AgentOps.Status.WORKING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = AgentOps.summary(s, online, System.currentTimeMillis())
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "agentops")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivityAndCollapse(PendingIntent.getActivity(this, 4402, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    companion object {
        fun requestUpdate(c: Context) = TileService.requestListeningState(c, ComponentName(c, AgentOpsTile::class.java))
    }
}
