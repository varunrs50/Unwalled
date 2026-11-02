package app.pixellink.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.pixellink.contacts.ContactLookup
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Reports the phone's calls to the Mac and carries out answer, decline, silence and dial.
 * Audio never leaves the phone. See protocol/PROTOCOL.md, "Calls".
 */
class CallBridge(private val context: Context, private val send: (String, JSONObject) -> Unit) {

    private data class Call(
        val id: String = UUID.randomUUID().toString(),
        val direction: String,
        var number: String?,
        var answered: Boolean = false,
        var declinedHere: Boolean = false,
    )

    private val telecom = context.getSystemService(TelecomManager::class.java)
    private var receiver: BroadcastReceiver? = null
    private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    private var current: Call? = null
    /** Number we just dialled from the Mac, so the outgoing call can show who it's to. */
    private var dialled: String? = null
    private var dialledAt = 0L
    /** Set while we've muted the ringer for a call, so it can be restored afterwards. */
    private var ringMuted = false
    private val audio = context.getSystemService(AudioManager::class.java)

    val hasPermissions: Boolean get() = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Starts watching calls if allowed. Returns true the first time it starts. */
    fun start(): Boolean {
        if (receiver != null || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onPhoneState(intent)
        }
        ContextCompat.registerReceiver(
            context, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r
        return true
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    /** Re-sends the current call, if any, so a Mac that just connected shows it. */
    fun resend() {
        current?.let { report(it, if (it.answered || it.direction == "outgoing") "active" else "ringing") }
    }

    @Suppress("DEPRECATION")
    private fun onPhoneState(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        // Only filled in when we hold READ_CALL_LOG; often arrives in a second broadcast.
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val call = current?.takeIf { it.direction == "incoming" } ?: Call(direction = "incoming", number = number)
                if (number != null) call.number = number
                current = call
                report(call, "ringing")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val call = current
                if (call != null && lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                    call.answered = true
                    report(call, "active")
                } else if (lastState == TelephonyManager.EXTRA_STATE_IDLE) {
                    val recent = dialled?.takeIf { System.currentTimeMillis() - dialledAt < DIAL_MATCH_MS }
                    val outgoing = Call(direction = "outgoing", number = recent ?: number)
                    dialled = null
                    current = outgoing
                    report(outgoing, "active")
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                restoreRinger()
                current?.let { call ->
                    val reason = when {
                        call.direction == "outgoing" || call.answered -> "completed"
                        call.declinedHere -> "declined"
                        else -> "missed"
                    }
                    report(call, "ended", reason)
                    // The call log entry is written a moment after the call ends.
                    android.os.Handler(context.mainLooper).postDelayed({ send("calllog.changed", JSONObject()) }, LOG_DELAY_MS)
                }
                current = null
            }
        }
        lastState = state
    }

    private fun report(call: Call, state: String, reason: String? = null) {
        val body = JSONObject()
            .put("id", call.id)
            .put("state", state)
            .put("direction", call.direction)
        call.number?.let { number ->
            body.put("number", number)
            ContactLookup.find(context, number, withPhoto = true)?.let { match ->
                body.put("name", match.name)
                match.photo?.let { body.put("photo", it) }
            }
        }
        reason?.let { body.put("reason", it) }
        Log.i(TAG, "Call ${call.direction} $state${reason?.let { " ($it)" } ?: ""}")
        send("call.state", body)
    }

    // MARK: Control from the Mac

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(): JSONObject = attempt("answer") { telecom.acceptRingingCall() }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun end(): JSONObject {
        current?.takeIf { !it.answered && it.direction == "incoming" }?.declinedHere = true
        return attempt("end") { check(telecom.endCall()) { "No call to end" } }
    }

    /**
     * TelecomManager.silenceRinger() is reserved for the default dialer, so mute the ring
     * stream for the rest of this call instead; it's restored when the call ends.
     */
    fun silence(): JSONObject = attempt("silence") {
        if (!audio.isStreamMute(AudioManager.STREAM_RING)) {
            audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            ringMuted = true
        }
    }

    private fun restoreRinger() {
        if (!ringMuted) return
        ringMuted = false
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
    }

    @SuppressLint("MissingPermission")
    fun dial(number: String): JSONObject = attempt("dial") {
        dialled = number
        dialledAt = System.currentTimeMillis()
        telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
    }

    private fun attempt(action: String, block: () -> Unit): JSONObject = try {
        block()
        JSONObject().put("ok", true).put("action", action)
    } catch (e: Exception) {
        Log.w(TAG, "Call $action failed: ${e.message}")
        JSONObject().put("ok", false).put("action", action).put("error", e.message ?: e.javaClass.simpleName)
    }

    // MARK: Call log and contacts

    fun callLog(limit: Int): JSONObject {
        val entries = JSONArray()
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        runCatching {
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, args, null)?.use { c ->
                // Not every provider honours QUERY_ARG_LIMIT, so stop reading once we have enough.
                while (entries.length() < limit && c.moveToNext()) {
                    entries.put(JSONObject()
                        .put("number", c.getString(0).orEmpty())
                        .put("name", c.getString(1)?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                        .put("type", callType(c.getInt(2)))
                        .put("time", c.getLong(3))
                        .put("duration", c.getLong(4)))
                }
            }
        }.onFailure { Log.w(TAG, "Call log unavailable: ${it.message}") }
        return JSONObject().put("entries", entries)
    }

    fun contacts(): JSONObject {
        val byId = linkedMapOf<Long, Pair<String, JSONArray>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4))
                    val numbers = byId.getOrPut(c.getLong(0)) { name to JSONArray() }.second
                    numbers.put(JSONObject().put("number", number).put("label", label.toString()))
                }
            }
        }.onFailure { Log.w(TAG, "Contacts unavailable: ${it.message}") }
        val contacts = JSONArray()
        byId.values.forEach { (name, numbers) -> contacts.put(JSONObject().put("name", name).put("numbers", numbers)) }
        return JSONObject().put("contacts", contacts)
    }

    private fun callType(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "incoming"
    }

    companion object {
        private const val TAG = "PixelLink"
        private const val LOG_DELAY_MS = 1_500L
        private const val DIAL_MATCH_MS = 30_000L

        val PERMISSIONS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
        )
    }
}
