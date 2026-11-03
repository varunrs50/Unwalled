package app.unwalled.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Display
import app.unwalled.R
import app.unwalled.link.Identity
import app.unwalled.link.LinkBus
import app.unwalled.link.PairingStore
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Native screen mirroring (M6). The Mac asks with `screen.start`; Android requires the user to
 * confirm every capture, so we post a notification that opens [ScreenCaptureActivity], which shows
 * Android's "share your screen" dialog and then starts [ScreenCaptureService].
 */
object ScreenMirror {
    private const val CHANNEL = "mirror"
    private const val PROMPT_ID = 8
    /** The Mac listens for video three ports above its message port (47820 → 47823). */
    const val VIDEO_PORT_OFFSET = 3

    /**
     * The Mac wants the screen. If a session the user approved earlier is still open, resume it
     * straight away; Android only asks again once that session has ended (e.g. after a restart).
     */
    fun requestStart(context: Context) {
        ScreenCaptureService.instance?.let { it.resume(); return }
        // With phone control on, Android lets us open the approval dialog straight away.
        ControlService.instance?.let { control ->
            control.startActivity(Intent(control, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            state("waiting")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen mirroring", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(PROMPT_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Show this screen on your Mac?")
            .setContentText("Tap to allow once. Your Mac can then open it any time until the phone restarts.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build())
        state("waiting")
    }

    /** The Mac closed its window: stop streaming but keep the session for next time. */
    fun pause() {
        ScreenCaptureService.instance?.pause() ?: state("ended")
    }

    /** Ends the session entirely; the next start asks the user again. */
    fun end(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)
        context.stopService(Intent(context, ScreenCaptureService::class.java))
    }

    /** Tells a newly connected Mac whether a session is ready, so it can say so in its menu. */
    fun reportState() = state(when {
        ScreenCaptureService.instance?.streaming == true -> "running"
        ScreenCaptureService.instance != null -> "paused"
        else -> "ended"
    })

    internal fun cancelPrompt(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)

    /** `on`, `off`, or `needs_permission` (the recording permission isn't granted). */
    internal fun sound(state: String) = LinkBus.send("screen.sound.state", JSONObject().put("state", state))

    internal fun state(state: String, error: String? = null) {
        LinkBus.send("screen.state", JSONObject()
            .put("state", state)
            .put("control", ControlService.instance != null)
            .apply { error?.let { put("error", it) } })
    }
}

/** Invisible: shows Android's screen-capture consent and hands the result to the service. */
class ScreenCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenMirror.cancelPrompt(this)
        val pm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java).putExtra(EXTRA_RESULT, data))
        } else {
            Log.i(TAG, "Screen mirroring declined")
            ScreenMirror.state("declined")
        }
        finish()
    }

    companion object {
        private const val REQUEST = 1
        const val EXTRA_RESULT = "result"
        private const val TAG = "Unwalled"
    }
}

/**
 * Holds the approved screen-capture session and streams it to the Mac on request. Streaming:
 * the screen goes into a hardware H.264 encoder and out to the Mac's video port over pinned TLS.
 * Paused: the encoder and connection are gone and the virtual display has no surface, so nothing
 * is captured or sent, but the session stays open so the Mac can resume without a new prompt.
 *
 * Packets: u32 length, u8 kind, u64 presentation time (µs), payload. Kinds: 0 = JSON info (size),
 * 1 = codec config (SPS/PPS), 2 = key frame, 3 = frame. A still screen sends nothing.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var socket: SSLSocket? = null
    private var out: DataOutputStream? = null
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var size = 0 to 0
    /** The phone's own screen size when the encoder was set up; rotating changes it. */
    private var screen = 0 to 0
    @Volatile var streaming = false
        private set
    private var audio: AudioCapture? = null
    private val speaker by lazy { SpeakerMute(this) }
    /** The Mac's Sound button; on unless the user turned it off. */
    @Volatile private var soundWanted = true
    private val writeLock = Any()
    /** The mirrored screen is the phone's real one: wake it when the Mac opens it, and don't let it sleep while shown. */
    @Suppress("DEPRECATION")
    private val awake by lazy {
        getSystemService(android.os.PowerManager::class.java).newWakeLock(
            android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "Unwalled:mirroring")
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            // Rotation changes the screen's shape; restart the encoder at the new size.
            if (streaming && displayId == Display.DEFAULT_DISPLAY && screenSize() != screen) handler.post { restartEncoder() }
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A volume left muted by a session that ended unexpectedly.
        if (projection == null) speaker.restore()
        startForeground(NOTIFICATION_ID, notification(streaming = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (intent?.action == ACTION_END) { stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION")
        val result = intent?.getParcelableExtra<Intent>(ScreenCaptureActivity.EXTRA_RESULT)
        if (result == null || projection != null) return START_NOT_STICKY
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, result)
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Android ended the screen-sharing session")
                ScreenMirror.state("ended", "Android ended screen sharing")
                stopSelf()
            }
        }, handler)
        instance = this
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        resume()
        return START_NOT_STICKY
    }

    private fun notification(streaming: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val end = PendingIntent.getService(this, 4, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(if (streaming) "Showing this screen on your Mac" else "Screen sharing ready for your Mac")
            .setContentText(if (streaming) null else "Your Mac can show this screen without asking again. Tap Stop to end.")
            .addAction(Notification.Action.Builder(null, "Stop", end).build())
            .setOngoing(true)
            .build()
    }

    /** Starts (or restarts) streaming to the Mac. */
    fun resume() = handler.post {
        if (streaming) { requestKeyFrameNow(); ScreenMirror.state("running"); return@post }
        val mac = PairingStore(this).mac ?: return@post
        try {
            val raw = Socket().apply { connect(InetSocketAddress(mac.host, mac.port + ScreenMirror.VIDEO_PORT_OFFSET), 5_000); tcpNoDelay = true }
            val ssl = Identity.sslContext(mac.fingerprint).socketFactory.createSocket(raw, mac.host, raw.port, true) as SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.startHandshake()
            socket = ssl
            out = DataOutputStream(BufferedOutputStream(ssl.outputStream, 256 * 1024))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the video stream: ${e.message}")
            ScreenMirror.state("error", "Couldn't reach the Mac for video")
            return@post
        }
        streaming = true
        awake.acquire(4 * 60 * 60_000L)
        if (!startEncoderOrReport()) return@post
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(streaming = true))
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        ScreenMirror.state("running")
        if (soundWanted) startSound()
        Log.i(TAG, "Screen mirroring streaming at ${size.first}x${size.second}${if (locked) " (phone locked)" else ""}")
    }

    /** Stops streaming but keeps the approved session. */
    fun pause() = handler.post {
        if (!streaming) { ScreenMirror.state("paused"); return@post }
        stopStreaming()
        ScreenMirror.state("paused")
        Log.i(TAG, "Screen mirroring paused; session kept")
    }

    private fun stopStreaming() {
        streaming = false
        audio?.stop()
        audio = null
        speaker.restore()
        display?.surface = null
        codec?.let { runCatching { it.stop() }; it.release() }
        codec = null
        runCatching { socket?.close() }
        socket = null
        out = null
        if (awake.isHeld) awake.release()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(streaming = false))
    }

    private fun screenSize(): Pair<Int, Int> =
        getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }

    /**
     * Sizes to try, largest first: the screen scaled so its long side is at most 1600, 1280, 960 or
     * 720 px, aligned the way this encoder needs. Phone makers' encoders differ in what they accept.
     */
    private fun candidateSizes(caps: MediaCodecInfo.VideoCapabilities): List<Pair<Int, Int>> {
        val (w, h) = screen
        val alignW = maxOf(16, caps.widthAlignment)
        val alignH = maxOf(16, caps.heightAlignment)
        return LONG_SIDES.map { side ->
            val scale = minOf(1f, side.toFloat() / maxOf(w, h))
            ((w * scale).toInt() / alignW * alignW) to ((h * scale).toInt() / alignH * alignH)
        }.distinct().filter { (cw, ch) -> cw > 0 && ch > 0 && caps.isSizeSupported(cw, ch) }
    }

    private fun startEncoder() {
        screen = screenSize()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            ?: run { encoder.release(); throw IllegalStateException("No H.264 video capabilities") }
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    val kind = when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> 1
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> 2
                        else -> 3
                    }
                    write(kind, info.presentationTimeUs, bytes)
                }
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "Encoder error: ${e.message}")
                handler.post { if (streaming) { stopStreaming(); ScreenMirror.state("error", "The phone's video encoder stopped") } }
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }
        // Take the largest size this encoder accepts; some reject sizes their capabilities list.
        var chosen: Pair<Int, Int>? = null
        for (candidate in candidateSizes(caps)) {
            val (cw, ch) = candidate
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cw, ch).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, caps.bitrateRange.clamp(BIT_RATE))
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            try {
                encoder.setCallback(callback, handler)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                chosen = candidate
                break
            } catch (e: Exception) {
                Log.i(TAG, "Encoder refused ${cw}x$ch: ${e.message}")
                encoder.reset()
            }
        }
        if (chosen == null) {
            encoder.release()
            throw IllegalStateException("This phone's video encoder doesn't support any mirroring size")
        }
        size = chosen
        val (w, h) = chosen
        val surface = encoder.createInputSurface()
        write(0, 0, JSONObject().put("width", w).put("height", h).toString().toByteArray())
        encoder.start()
        codec = encoder
        val dpi = resources.displayMetrics.densityDpi
        // One virtual display per session (Android allows only one); it's resized and re-pointed.
        display?.let { it.resize(w, h, dpi); it.surface = surface }
            ?: run {
                display = projection?.createVirtualDisplay("Unwalled", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler)
            }
    }

    private fun restartEncoder() {
        codec?.let { runCatching { it.stop() }; it.release() }
        codec = null
        if (startEncoderOrReport()) Log.i(TAG, "Screen mirroring resized to ${size.first}x${size.second}")
    }

    /** Starts the encoder, or stops streaming and tells the Mac why it couldn't. */
    private fun startEncoderOrReport(): Boolean = try {
        startEncoder()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't start the video encoder: ${e.message}")
        stopStreaming()
        ScreenMirror.state("error", "This phone's video encoder couldn't start: ${e.message}")
        false
    }

    fun requestKeyFrame() = handler.post { requestKeyFrameNow() }

    /** The Mac's Sound button. */
    fun setSound(on: Boolean) = handler.post {
        soundWanted = on
        if (on && streaming) startSound() else stopSound()
    }

    private fun startSound() {
        val p = projection ?: return
        val capture = audio ?: AudioCapture(this, p, ::write).also { audio = it }
        if (!capture.allowed) return ScreenMirror.sound("needs_permission")
        capture.start()
        speaker.mute()
        ScreenMirror.sound("on")
    }

    private fun stopSound() {
        audio?.stop()
        audio = null
        speaker.restore()
        ScreenMirror.sound("off")
    }

    private fun requestKeyFrameNow() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Called from the video encoder and the sound capture threads. */
    private fun write(kind: Int, pts: Long, data: ByteArray) {
        val o = out ?: return
        try {
            synchronized(writeLock) {
                o.writeInt(data.size + 9)
                o.writeByte(kind)
                o.writeLong(pts)
                o.write(data)
                o.flush()
            }
        } catch (e: Exception) {
            // The Mac went away (closed, slept, left the network): keep the session, stop streaming.
            Log.i(TAG, "Video stream closed: ${e.message}")
            handler.post { if (streaming) { stopStreaming(); ScreenMirror.state("paused") } }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        handler.post {
            stopStreaming()
            display?.release()
            display = null
            projection?.stop()
            projection = null
            thread.quitSafely()
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        ScreenMirror.state("ended")
        Log.i(TAG, "Screen-sharing session ended")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Unwalled"
        private const val CHANNEL = "mirror.active"
        private const val NOTIFICATION_ID = 9
        private const val ACTION_END = "app.unwalled.action.END_MIRROR"
        /** Long sides to try, largest first; the first one the encoder accepts is used. */
        private val LONG_SIDES = listOf(1600, 1280, 960, 720)
        private const val BIT_RATE = 6_000_000
        @Volatile var instance: ScreenCaptureService? = null
            private set
    }
}
