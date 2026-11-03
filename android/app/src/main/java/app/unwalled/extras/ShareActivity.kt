package app.unwalled.extras

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.unwalled.link.LinkBus
import app.unwalled.link.LinkService

/** "Send to Mac" in Android's share sheet: files go to the Mac's Downloads, text to its clipboard. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val message = when {
            !LinkBus.connected -> "Mac isn't connected"
            uris.isNotEmpty() -> {
                LinkService.sendFiles(this, uris)
                if (uris.size == 1) "Sending to Mac…" else "Sending ${uris.size} files to Mac…"
            }
            else -> ClipboardBridge.sendToMac(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
