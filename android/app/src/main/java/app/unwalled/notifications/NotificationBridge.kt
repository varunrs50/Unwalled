package app.unwalled.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import app.unwalled.link.LinkBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Forwards the phone's notifications to the Mac and carries out the Mac's
 * dismiss, reply and button presses. See protocol/PROTOCOL.md, "Notifications".
 */
class NotificationBridge : NotificationListenerService() {

    /** Notification key → hash of what was last sent, to skip updates that change nothing visible. */
    private val sent = ConcurrentHashMap<String, Int>()
    /** Packages whose icon has already gone to the Mac this session. */
    private val iconsSent = ConcurrentHashMap.newKeySet<String>()

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "Notification listener connected")
        if (LinkBus.connected) sendSnapshot()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!shouldForward(sbn, rankingMap)) return
        forward(sbn, initial = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (sent.remove(sbn.key) != null) LinkBus.send("notif.removed", JSONObject().put("key", sbn.key))
    }

    /** Called when a session starts: re-send everything currently showing, without alerting. */
    fun sendSnapshot() {
        sent.clear()
        iconsSent.clear()
        val ranking = currentRanking
        runCatching { activeNotifications }.getOrNull()?.forEach { sbn ->
            if (shouldForward(sbn, ranking)) forward(sbn, initial = true)
        }
    }

    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }.onFailure { Log.w(TAG, "Dismiss failed", it) }
    }

    fun reply(key: String, actionIndex: Int, text: String): Boolean {
        val action = findAction(key, actionIndex) ?: return false
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent) }
            .onFailure { Log.w(TAG, "Reply failed", it) }
            .isSuccess
    }

    fun perform(key: String, actionIndex: Int): Boolean {
        val action = findAction(key, actionIndex) ?: return false
        return runCatching { action.actionIntent.send() }.onFailure { Log.w(TAG, "Action failed", it) }.isSuccess
    }

    private fun findAction(key: String, index: Int): Notification.Action? =
        runCatching { activeNotifications }.getOrNull()
            ?.firstOrNull { it.key == key }
            ?.notification?.actions?.getOrNull(index)

    private fun shouldForward(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName && n.channelId != TestNotifications.CHANNEL) return false
        if (!sbn.isClearable) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // With call access on, the call bridge reports calls and missed calls itself.
        if ((n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL) &&
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true && ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            return false
        }
        return !title(n).isNullOrBlank() || !text(n).isNullOrBlank()
    }

    private fun forward(sbn: StatusBarNotification, initial: Boolean) {
        val n = sbn.notification
        val title = title(n)?.toString().orEmpty()
        val text = text(n)?.toString().orEmpty()
        val actions = n.actions.orEmpty()
        val signature = listOf(title, text, actions.map { it.title?.toString() }).hashCode()
        if (!initial && sent[sbn.key] == signature) return
        // Android doesn't re-alert for updates flagged "alert once" (e.g. after you reply), so neither does the Mac.
        val silent = !initial && sent.containsKey(sbn.key) && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        sent[sbn.key] = signature

        val body = JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("app", appLabel(sbn.packageName))
            .put("title", title)
            .put("text", text)
            .put("time", sbn.postTime)
            .put("initial", initial)
            .put("silent", silent)
            .put("actions", JSONArray().apply {
                actions.forEachIndexed { i, a ->
                    put(JSONObject()
                        .put("index", i)
                        .put("title", a.title?.toString().orEmpty())
                        .put("reply", a.remoteInputs?.any { it.allowFreeFormInput } == true))
                }
            })
        if (iconsSent.add(sbn.packageName)) {
            runCatching { packageManager.getApplicationIcon(sbn.packageName).toBitmap(ICON_PX, ICON_PX) }
                .getOrNull()?.let { body.put("icon", it.toBase64Png()) }
        }
        n.getLargeIcon()?.loadDrawable(this)?.let { drawable ->
            runCatching { drawable.toBitmap(PICTURE_PX, PICTURE_PX) }.getOrNull()?.let { body.put("picture", it.toBase64Png()) }
        }
        Log.i(TAG, "Forwarding notification from ${sbn.packageName} (initial=$initial, connected=${LinkBus.connected})")
        LinkBus.send("notif.posted", body)
    }

    private fun title(n: Notification): CharSequence? =
        n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: lastMessage(n)?.first

    private fun text(n: Notification): CharSequence? =
        n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: lastMessage(n)?.second

    /** Sender and text of the newest message in a chat-style notification that has no plain title/text. */
    private fun lastMessage(n: Notification): Pair<CharSequence?, CharSequence?>? {
        val last = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            ?.filterIsInstance<Bundle>()?.lastOrNull() ?: return null
        val sender = last.getParcelable("sender_person", Person::class.java)?.name ?: last.getCharSequence("sender")
        return sender to last.getCharSequence("text")
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun Bitmap.toBase64Png(): String {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "Unwalled"
        private const val ICON_PX = 64
        private const val PICTURE_PX = 128

        @Volatile var instance: NotificationBridge? = null
            private set

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationBridge::class.java).flattenToString(),
            )
    }
}
