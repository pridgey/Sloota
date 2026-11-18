package dev.pridgey.sloota.watch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.pridgey.sloota.State
import dev.pridgey.sloota.Targets
import dev.pridgey.sloota.log.EventLog

/**
 * "This would be a good place to stop." Marks are the ground truth the stopping-point rules
 * get checked against: the events just before each mark show what a natural stop looks like.
 */
class MarkTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val app = State.foreground
        EventLog.write("mark", app)
        val where = app?.let { Targets.nameOf(it) ?: it }
        Toast.makeText(this, if (where != null) "Marked a stop in $where" else "Marked a stop", Toast.LENGTH_SHORT).show()
    }
}
