package app.pixellink.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.pixellink.contacts.ContactLookup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the phone's SMS threads and messages for the Mac, pushes new and changed messages as they
 * happen, and sends texts from the SIM. Plain SMS only; see protocol/PROTOCOL.md, "SMS".
 */
class SmsBridge(private val context: Context, private val send: (String, JSONObject) -> Unit) {

    private val resolver = context.contentResolver
    private val thread = HandlerThread("PixelLink-sms").apply { start() }
    private val handler = Handler(thread.looper)
    private var observer: ContentObserver? = null
    private var sentReceiver: BroadcastReceiver? = null
    /** Message ID → status we last reported, for the most recent messages. */
    private val known = HashMap<Long, String>()
    private val names = HashMap<String, String?>()

    val hasPermissions: Boolean get() = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Starts watching for new messages if allowed. Returns true the first time it starts. */
    fun start(): Boolean {
        if (observer != null || context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        // Remember what's already there so only real changes are pushed.
        recent().forEach { known[it.getLong("id")] = it.getString("status") }
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = pushChanges()
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o)
        observer = o

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: return
                val ok = resultCode == Activity.RESULT_OK
                send("sms.send.result", JSONObject().put("client_id", clientId).put("ok", ok).apply {
                    if (!ok) put("error", sendError(resultCode))
                })
                Log.i(TAG, "SMS ${if (ok) "sent" else "failed (${sendError(resultCode)})"}")
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        sentReceiver = r
        return true
    }

    fun stop() {
        observer?.let { resolver.unregisterContentObserver(it) }
        observer = null
        sentReceiver?.let { context.unregisterReceiver(it) }
        sentReceiver = null
        thread.quitSafely()
    }

    // MARK: Requests from the Mac

    fun threads(limit: Int): JSONObject {
        val threads = JSONArray()
        val projection = arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read")
        val uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val recipients = canonicalAddresses()
        runCatching {
            resolver.query(uri, projection, null, null, "date DESC")?.use { c ->
                while (c.moveToNext() && threads.length() < limit) {
                    val addresses = c.getString(3).orEmpty().split(" ").mapNotNull { it.toLongOrNull()?.let(recipients::get) }
                    if (addresses.isEmpty()) continue
                    threads.put(JSONObject()
                        .put("id", c.getLong(0))
                        .put("addresses", JSONArray(addresses))
                        .put("name", addresses.map { name(it) ?: it }.joinToString(", ").takeIf { addresses.any { a -> name(a) != null } } ?: JSONObject.NULL)
                        // Some threads come back without a snippet; use their newest message instead. Both stay
                        // empty when Android is withholding a message from apps (e.g. a recent one-time code).
                        .put("snippet", c.getString(4)?.takeIf { it.isNotBlank() }
                            ?: query("${Telephony.Sms.THREAD_ID} = ?", arrayOf(c.getLong(0).toString()), 1)
                                .firstOrNull()?.getString("body").orEmpty())
                        .put("time", c.getLong(1))
                        .put("unread", c.getInt(5) == 0)
                        .put("count", c.getInt(2)))
                }
            }
        }.onFailure { Log.w(TAG, "SMS threads unavailable: ${it.message}") }
        return JSONObject().put("threads", threads)
    }

    fun messages(thread: Long, limit: Int, before: Long?): JSONObject {
        val selection = buildString {
            append("${Telephony.Sms.THREAD_ID} = ?")
            if (before != null) append(" AND ${Telephony.Sms.DATE} < ?")
        }
        val args = listOfNotNull(thread.toString(), before?.toString()).toTypedArray()
        val rows = query(selection, args, limit).reversed()
        return JSONObject().put("thread", thread).put("messages", JSONArray(rows))
    }

    fun sendSms(to: String, body: String, clientId: String): JSONObject? {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("client_id", clientId).put("ok", false).put("error", "SMS permission not granted")
        }
        return try {
            val sms = context.getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(body)
            val sent = PendingIntent.getBroadcast(
                context, clientId.hashCode(),
                Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_CLIENT_ID, clientId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Only the last part reports back, so one result arrives per message.
            val sentIntents = ArrayList(parts.indices.map { if (it == parts.lastIndex) sent else null })
            sms.sendMultipartTextMessage(to, null, parts, sentIntents, null)
            null // The result arrives later via the sent broadcast.
        } catch (e: Exception) {
            Log.w(TAG, "SMS send failed: ${e.message}")
            JSONObject().put("client_id", clientId).put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
    }

    // MARK: Live updates

    /** Called when the SMS store changes: push messages that are new or whose status changed. */
    private fun pushChanges() {
        val changed = recent().filter { m ->
            val id = m.getLong("id")
            val status = m.getString("status")
            (known.put(id, status) != status)
        }
        if (known.size > KNOWN_LIMIT) known.keys.sorted().take(known.size - KNOWN_LIMIT).forEach(known::remove)
        Log.i(TAG, "SMS store changed: ${changed.size} new or updated")
        if (changed.isNotEmpty()) send("sms.updated", JSONObject().put("messages", JSONArray(changed)))
    }

    private fun recent(): List<JSONObject> = query(null, null, RECENT_LIMIT)

    // MARK: Queries

    /** Newest-first messages matching `selection`. */
    private fun query(selection: String?, args: Array<String>?, limit: Int): List<JSONObject> {
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.STATUS,
        )
        val queryArgs = Bundle().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            args?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${Telephony.Sms.DATE} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val rows = mutableListOf<JSONObject>()
        runCatching {
            resolver.query(Telephony.Sms.CONTENT_URI, projection, queryArgs, null)?.use { c ->
                // The SMS provider ignores QUERY_ARG_LIMIT, so stop reading once we have enough.
                while (rows.size < limit && c.moveToNext()) {
                    val type = c.getInt(5)
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    rows += JSONObject()
                        .put("id", c.getLong(0))
                        .put("thread", c.getLong(1))
                        .put("address", c.getString(2).orEmpty())
                        .put("body", c.getString(3).orEmpty())
                        .put("time", c.getLong(4))
                        .put("direction", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "in" else "out")
                        .put("status", status(type, c.getInt(6)))
                }
            }
        }.onFailure { Log.w(TAG, "SMS unavailable: ${it.message}") }
        return rows
    }

    private fun status(type: Int, status: Int) = when {
        type == Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
        type == Telephony.Sms.MESSAGE_TYPE_FAILED || status == Telephony.Sms.STATUS_FAILED -> "failed"
        type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED -> "sending"
        status == Telephony.Sms.STATUS_COMPLETE -> "delivered"
        else -> "sent"
    }

    /**
     * Recipient ID → phone number. This table ignores the projection and selection on some
     * devices, so read it whole and find columns by name.
     */
    private fun canonicalAddresses(): Map<Long, String> = runCatching {
        resolver.query(Uri.parse("content://mms-sms/canonical-addresses"), null, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val address = c.getColumnIndexOrThrow("address")
            buildMap { while (c.moveToNext()) c.getString(address)?.let { put(c.getLong(id), it) } }
        }
    }.getOrNull().orEmpty()

    private fun name(address: String): String? = names.getOrPut(address) { ContactLookup.find(context, address)?.name }

    private fun sendError(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile network is off"
        SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "The network rejected the message"
        else -> "Error $code"
    }

    companion object {
        private const val TAG = "PixelLink"
        private const val ACTION_SENT = "app.pixellink.SMS_SENT"
        private const val EXTRA_CLIENT_ID = "client_id"
        private const val RECENT_LIMIT = 30
        private const val KNOWN_LIMIT = 500

        val PERMISSIONS = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
    }
}
