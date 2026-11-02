package app.pixellink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pixellink.link.LinkService
import app.pixellink.link.PairingStore

/** Reconnects to the paired Mac after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PairingStore(context).mac != null) LinkService.start(context)
    }
}
