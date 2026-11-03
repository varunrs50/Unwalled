package app.unwalled.extras

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.unwalled.R
import org.json.JSONObject

/** "Find my phone": rings loudly on the alarm stream, even in silent mode, until stopped or 60 s pass. */
class PhoneRinger(private val context: Context, private val send: (String, JSONObject) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null

    val ringing: Boolean get() = player != null

    fun start() = handler.post {
        if (player != null) return@post
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            showNotification()
            handler.postDelayed(::stop, MAX_RING_MS)
            Log.i(TAG, "Ringing (find my phone)")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't ring: ${e.message}")
            stop()
        }
        report()
    }

    fun stop() = handler.post {
        handler.removeCallbacksAndMessages(null)
        player?.run { runCatching { stop() }; release() }
        player = null
        savedVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        savedVolume = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        report()
    }

    private fun report() = send("phone.ring.state", JSONObject().put("ringing", ringing))

    private fun showNotification() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Find my phone", NotificationManager.IMPORTANCE_HIGH))
        val stopIntent = PendingIntent.getService(
            context, 0, Intent(context, app.unwalled.link.LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Your Mac is ringing this phone")
            .setContentText("Tap to stop")
            .setContentIntent(stopIntent)
            .setDeleteIntent(stopIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setCategory(Notification.CATEGORY_ALARM)
            .build())
    }

    companion object {
        const val ACTION_STOP = "app.unwalled.action.STOP_RINGING"
        private const val TAG = "Unwalled"
        private const val CHANNEL = "ring"
        private const val NOTIFICATION_ID = 7
        private const val MAX_RING_MS = 60_000L
    }
}
