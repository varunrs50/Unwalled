package app.pixellink.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import app.pixellink.BuildConfig
import app.pixellink.MainActivity
import app.pixellink.R
import app.pixellink.calls.CallBridge
import app.pixellink.notifications.NotificationBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Foreground service that keeps the encrypted connection to the paired Mac alive:
 * pairing, reconnecting with backoff, heartbeats and status updates.
 */
class LinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: PairingStore
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var frames: FrameSocket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private lateinit var calls: CallBridge
    @Volatile private var nudgeSocket: DatagramSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = PairingStore(this)
        calls = CallBridge(this, LinkBus::send)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Connection to Mac", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        watchWifi()
        watchBattery()
        listenForNudges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCalls()
        when (intent?.action) {
            ACTION_PAIR -> {
                val payload = PairPayload.parse(intent.getStringExtra(EXTRA_PAYLOAD).orEmpty())
                if (payload == null) {
                    LinkState.error.value = "That isn't a PixelLink pairing code."
                } else {
                    restart { pairThenRun(payload) }
                }
            }
            ACTION_UNPAIR -> scope.launch {
                runCatching { frames?.send("unpair") }
                forget()
            }
            else -> if (loop?.isActive != true) {
                if (store.mac == null) forget() else restart { runLoop() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        nudgeSocket?.close()
        calls.stop()
        frames?.close()
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        batteryReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun restart(block: suspend () -> Unit) {
        loop?.cancel()
        frames?.close()
        loop = scope.launch { block() }
    }

    // MARK: Pairing

    private suspend fun pairThenRun(payload: PairPayload) {
        LinkState.error.value = null
        LinkState.status.value = LinkStatus.Pairing(payload.name)
        updateNotification("Pairing with ${payload.name}…")
        try {
            connect(payload.host, payload.port, payload.fingerprint).use { fs ->
                fs.send("pair.request", JSONObject()
                    .put("token", payload.token)
                    .put("name", deviceName())
                    .put("model", Build.MODEL)
                    .put("app_version", BuildConfig.VERSION_NAME))
                // Give the user time to click Allow on the Mac.
                fs.socket.soTimeout = PAIRING_TIMEOUT_MS
                val reply = fs.receive()
                if (reply.type != "pair.result" || !reply.body.optBoolean("ok")) {
                    LinkState.error.value = when (reply.body.optString("reason")) {
                        "denied" -> "The Mac didn't allow this phone."
                        "bad_token" -> "That pairing code has expired. Show a new one on the Mac."
                        else -> "Pairing didn't complete. Try again."
                    }
                    return@use
                }
                val mac = PairedMac(reply.body.optString("name", payload.name), payload.host, payload.port, payload.fingerprint)
                store.mac = mac
                Log.i(TAG, "Paired with ${mac.name}")
                session(fs, mac, greet = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pairing failed", e)
            if (store.mac == null) LinkState.error.value = "Couldn't reach the Mac: ${e.message}"
        }
        if (store.mac != null) runLoop() else forget()
    }

    // MARK: Connection loop

    private suspend fun runLoop() {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            val mac = store.mac ?: return
            LinkState.status.value = LinkStatus.Searching(mac.name)
            updateNotification("Looking for ${mac.name}…")
            val fs = tryConnect(mac)
            if (fs != null) {
                backoff = MIN_BACKOFF_MS
                try {
                    fs.use { session(it, mac, greet = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "Session ended: ${e.message}")
                }
                if (store.mac == null) return
            }
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun tryConnect(mac: PairedMac): FrameSocket? {
        try {
            return connect(mac.host, mac.port, mac.fingerprint)
        } catch (e: Exception) {
            Log.i(TAG, "Last known address ${mac.host} failed: ${e.message}")
        }
        val host = MacFinder.find(this, mac.fingerprint) ?: return null
        if (host != mac.host) {
            Log.i(TAG, "Mac moved to $host")
            store.mac = mac.copy(host = host)
        }
        return runCatching { connect(host, mac.port, mac.fingerprint) }.getOrNull()
    }

    private fun connect(host: String, port: Int, fingerprint: String): FrameSocket {
        val raw = Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        val ssl = Identity.sslContext(fingerprint).socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.soTimeout = IDLE_TIMEOUT_MS
        ssl.startHandshake()
        return FrameSocket(ssl)
    }

    private suspend fun session(fs: FrameSocket, mac: PairedMac, greet: Boolean) = coroutineScope {
        frames = fs
        // Blocking reads can't be cancelled, so closing the socket is how cancellation unblocks them.
        launch { try { awaitCancellation() } finally { fs.close() } }
        try {
            fs.socket.soTimeout = IDLE_TIMEOUT_MS
            if (greet) {
                fs.send("hello", JSONObject()
                    .put("name", deviceName())
                    .put("model", Build.MODEL)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("android", Build.VERSION.RELEASE))
                val ack = fs.receive()
                if (ack.type != "hello.ack") throw IllegalStateException("Expected hello.ack, got ${ack.type}")
                ack.body.optString("name").takeIf { it.isNotEmpty() && it != mac.name }?.let {
                    store.mac = mac.copy(name = it)
                }
            }
            val name = store.mac?.name ?: mac.name
            LinkState.status.value = LinkStatus.Connected(name)
            LinkState.error.value = null
            updateNotification("Connected to $name")
            Log.i(TAG, "Connected to $name")

            launch { while (true) { delay(PING_INTERVAL_MS); fs.send("ping") } }
            launch { while (true) { sendStatus(); delay(STATUS_INTERVAL_MS) } }

            // Other components queue frames here; one writer keeps them in order and off the main thread.
            val outbox = Channel<Pair<String, JSONObject>>(OUTBOX_SIZE, BufferOverflow.DROP_OLDEST)
            launch { for ((type, body) in outbox) fs.send(type, body) }
            LinkBus.sender = { type, body -> outbox.trySend(type to body) }
            NotificationBridge.instance?.sendSnapshot()
            startCalls()
            calls.resend()

            while (true) {
                val frame = fs.receive()
                when (frame.type) {
                    "unpair" -> {
                        forget()
                        return@coroutineScope
                    }
                    "notif.dismiss" -> NotificationBridge.instance?.dismiss(frame.body.getString("key"))
                    "notif.reply" -> {
                        val ok = NotificationBridge.instance?.reply(
                            frame.body.getString("key"), frame.body.getInt("action"), frame.body.getString("text"),
                        ) ?: false
                        if (!ok) Log.w(TAG, "Couldn't send reply")
                    }
                    "notif.action" -> NotificationBridge.instance?.perform(frame.body.getString("key"), frame.body.getInt("action"))
                    "call.answer" -> fs.send("call.result", calls.answer(), frame.id)
                    "call.end" -> fs.send("call.result", calls.end(), frame.id)
                    "call.silence" -> fs.send("call.result", calls.silence(), frame.id)
                    "call.dial" -> fs.send("call.result", calls.dial(frame.body.getString("number")), frame.id)
                    "calllog.request" -> fs.send("calllog.page", calls.callLog(frame.body.optInt("limit", 100)), frame.id)
                    "contacts.request" -> fs.send("contacts.list", calls.contacts(), frame.id)
                    else -> Unit
                }
            }
        } finally {
            LinkBus.sender = null
            frames = null
            coroutineContext.cancelChildren()
        }
    }

    /** Call access can be granted at any time; when it is, tell the Mac to fetch the call log and contacts. */
    private fun startCalls() {
        if (calls.start()) {
            LinkBus.send("calllog.changed", JSONObject())
            LinkBus.send("contacts.changed", JSONObject())
        }
    }

    private fun sendStatus() {
        runCatching { frames?.send("status.update", PhoneStatus.snapshot(this)) }
    }

    private fun forget() {
        store.mac = null
        loop?.cancel()
        frames?.close()
        LinkState.status.value = LinkStatus.NotPaired
        stopSelf()
    }

    // MARK: System events

    /**
     * In deep sleep our timers stop, so we can miss that the Mac restarted or slept. The Mac
     * sends a UDP nudge when it comes back; an incoming packet wakes the phone, and we drop
     * whatever connection we think we have and reconnect. See PROTOCOL.md, "Wake-up nudge".
     */
    private fun listenForNudges() {
        scope.launch {
            val socket = runCatching { DatagramSocket(NUDGE_PORT) }.getOrElse {
                Log.w(TAG, "Can't listen for nudges: ${it.message}")
                return@launch
            }
            // receive() blocks and ignores cancellation; onDestroy closes this to free the port.
            nudgeSocket = socket
            try {
                val buf = ByteArray(64)
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    val prefix = text.removePrefix("PLNUDGE1 ").takeIf { it != text && it.length == 16 } ?: continue
                    val mac = store.mac ?: continue
                    if (!mac.fingerprint.startsWith(prefix)) continue
                    Log.i(TAG, "Nudged by ${mac.name}, reconnecting")
                    frames?.close()
                    wake.trySend(Unit)
                }
            } finally {
                socket.close()
            }
        }
    }

    private fun watchWifi() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wake.trySend(Unit) }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun watchBattery() {
        var last: Pair<Int, Int>? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val now = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) to intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                if (now != last) {
                    last = now
                    scope.launch { sendStatus() }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryReceiver = receiver
    }

    // MARK: Notification

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("PixelLink")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    companion object {
        private const val TAG = "PixelLink"
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAIR = "app.pixellink.action.PAIR"
        private const val ACTION_UNPAIR = "app.pixellink.action.UNPAIR"
        private const val EXTRA_PAYLOAD = "payload"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IDLE_TIMEOUT_MS = 75_000
        private const val PAIRING_TIMEOUT_MS = 120_000
        private const val PING_INTERVAL_MS = 30_000L
        private const val STATUS_INTERVAL_MS = 60_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val OUTBOX_SIZE = 256
        private const val NUDGE_PORT = 47822

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        fun pair(context: Context, payload: String) {
            context.startForegroundService(
                Intent(context, LinkService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_PAYLOAD, payload)
            )
        }

        fun unpair(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java).setAction(ACTION_UNPAIR))
        }
    }
}
