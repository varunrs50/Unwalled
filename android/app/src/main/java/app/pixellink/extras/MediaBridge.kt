package app.pixellink.extras

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import app.pixellink.notifications.NotificationBridge
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Reports what's playing on the phone and passes play/pause/skip from the Mac to it.
 * Needs notification access, which is what lets an app see other apps' media sessions.
 */
class MediaBridge(private val context: Context, private val send: (String, JSONObject) -> Unit) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val listenerComponent = ComponentName(context, NotificationBridge::class.java)
    private var controller: MediaController? = null
    private var lastSent: String? = null
    private var lastArtKey: String? = null
    private var started = false

    /** Every active session is watched, since the one in front is often an idle one (a news app, say). */
    private val watched = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    /** Starts following media sessions if notification access is on. Safe to call repeatedly. */
    fun start() = handler.post {
        if (started || !NotificationBridge.isEnabled(context)) return@post
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler)
            started = true
            watch(sessions.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            Log.w(TAG, "Media sessions unavailable: ${e.message}")
        }
    }

    fun stop() = handler.post {
        if (started) runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watch(emptyList())
        started = false
    }

    /** Re-sends the current state, e.g. to a Mac that just connected. */
    fun resend() = handler.post {
        lastSent = null
        lastArtKey = null
        report()
    }

    fun control(action: String) = handler.post {
        val controls = controller?.transportControls ?: return@post
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (playing) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
        }
    }

    private fun watch(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { token -> watched.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        for (c in list) {
            if (c.sessionToken in watched) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = choose()
                override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
                override fun onSessionDestroyed() = watch(watched.values.map { it.first }.filter { it.sessionToken != c.sessionToken })
            }
            c.registerCallback(cb, handler)
            watched[c.sessionToken] = c to cb
        }
        choose()
    }

    /** Follows the app that's playing; otherwise stays on the last one, as long as it still has something loaded. */
    private fun choose() {
        val all = watched.values.map { it.first }
        fun usable(c: MediaController) = c.metadata != null && (c.playbackState?.state ?: PlaybackState.STATE_NONE) != PlaybackState.STATE_NONE
        val playing = all.firstOrNull { it.playbackState?.state in PLAYING_STATES && it.metadata != null }
        val next = playing
            ?: controller?.takeIf { current -> all.any { it.sessionToken == current.sessionToken } && usable(current) }
            ?: all.firstOrNull(::usable)
        if (next?.sessionToken != controller?.sessionToken) {
            Log.i(TAG, "Now playing from ${next?.packageName ?: "nothing"}")
            controller = next
        }
        report()
    }

    private fun report() {
        val c = controller
        val meta = c?.metadata
        val body = JSONObject().put("active", c != null && meta != null)
        if (c != null && meta != null) {
            body.put("app", appLabel(c.packageName))
                .put("title", meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
                .put("artist", (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty())
                .put("album", meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
                .put("playing", c.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
        val key = body.toString()
        if (key == lastSent) return
        lastSent = key

        // Artwork only when the track changes, since it's the bulky part.
        val artKey = "${c?.packageName}|${body.optString("title")}|${body.optString("album")}"
        if (meta != null && artKey != lastArtKey) {
            lastArtKey = artKey
            val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            art?.let { body.put("art", it.toBase64Jpeg()) }
        }
        send("media.state", body)
    }

    private fun appLabel(pkg: String) = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun Bitmap.toBase64Jpeg(): String {
        val scale = ART_PX.toFloat() / maxOf(width, height)
        val scaled = if (scale < 1) Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true) else this
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "PixelLink"
        private const val ART_PX = 160
        private val PLAYING_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
    }
}
