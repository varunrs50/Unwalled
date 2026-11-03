package app.unwalled.extras

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.service.quicksettings.TileService
import android.widget.Toast
import app.unwalled.link.LinkBus
import org.json.JSONObject

/**
 * Shared clipboard. Mac → phone is automatic; phone → Mac needs a tap (Quick Settings tile or
 * share sheet), because Android only lets the app in front read the clipboard.
 */
object ClipboardBridge {
    private const val MAX_CHARS = 200_000
    @Volatile private var lastFromMac: String? = null
    @Volatile private var lastSent: String? = null

    fun setFromMac(context: Context, text: String) {
        lastFromMac = text
        lastSent = text
        Handler(Looper.getMainLooper()).post {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From Mac", text))
        }
    }

    /**
     * Sends text to the Mac's clipboard; returns a message to show the user. Automatic sends skip
     * text the Mac just gave us or that was already sent, so nothing bounces back and forth.
     */
    fun sendToMac(text: String, auto: Boolean = false): String {
        if (text.isBlank()) return "Clipboard is empty"
        if (!LinkBus.connected) return "Mac isn't connected"
        if (auto && (text == lastFromMac || text == lastSent)) return "Already on the Mac"
        lastSent = text
        LinkBus.send("clip.set", JSONObject().put("text", text.take(MAX_CHARS)))
        return "Copied to Mac"
    }
}

/** Invisible activity: reads the clipboard once it has focus (the only time Android allows it), sends it, closes. */
class ClipSendActivity : Activity() {
    companion object {
        /** Opened by [ClipWatcher] rather than a tap: no toast, and repeats are skipped. */
        const val EXTRA_AUTO = "auto"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("Unwalled", "Copy to Mac opened")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val result = ClipboardBridge.sendToMac(text, auto)
        Log.i("Unwalled", "Copy to Mac${if (auto) " (auto)" else ""}: $result (${text.length} characters)")
        if (!auto) Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** Opens [ClipSendActivity]; used by the tile and the button on Unwalled's notification. */
fun clipSendIntent(context: Context): android.app.PendingIntent = android.app.PendingIntent.getActivity(
    context, 1, android.content.Intent(context, ClipSendActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    android.app.PendingIntent.FLAG_IMMUTABLE,
)

/** Asks Android to add the "Copy to Mac" tile to Quick Settings (Android shows its own prompt). */
fun requestClipTile(activity: Activity) {
    val statusBar = activity.getSystemService(android.app.StatusBarManager::class.java)
    statusBar.requestAddTileService(
        ComponentName(activity, ClipTileService::class.java), "Copy to Mac",
        android.graphics.drawable.Icon.createWithResource(activity, app.unwalled.R.drawable.ic_stat_link),
        activity.mainExecutor,
    ) { result ->
        val added = result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        if (added) activity.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("clipTile", true).apply()
        Log.i("Unwalled", "Copy to Mac tile request: $result")
    }
}

/** Quick Settings tile: "Copy to Mac". */
class ClipTileService : TileService() {
    override fun onTileAdded() {
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("clipTile", true).apply()
    }

    override fun onClick() {
        Log.i("Unwalled", "Copy to Mac tile tapped")
        startActivityAndCollapse(clipSendIntent(this))
    }
}
