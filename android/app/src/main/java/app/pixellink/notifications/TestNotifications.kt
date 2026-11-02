package app.pixellink.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only: posts a fake chat notification with a Reply button, so notification
 * forwarding and replies can be tested without messaging real people.
 */
object TestNotifications {
    const val CHANNEL = "test"
    private const val ID = 4242
    private const val RESULT_KEY = "reply"
    private val contact = Person.Builder().setName("Test Contact").build()
    private val me = Person.Builder().setName("You").build()
    private val messages = mutableListOf<Notification.MessagingStyle.Message>()

    fun post(context: Context, text: String = "Hi! This is a PixelLink test message.") {
        messages += Notification.MessagingStyle.Message(text, System.currentTimeMillis(), contact)
        show(context, alert = true)
    }

    fun cancel(context: Context) {
        messages.clear()
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun onReply(context: Context, text: String) {
        Log.i("PixelLink", "Test reply received: $text")
        messages += Notification.MessagingStyle.Message(text, System.currentTimeMillis(), me)
        show(context, alert = false)
    }

    private fun show(context: Context, alert: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Test messages", NotificationManager.IMPORTANCE_HIGH))

        val replyIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, TestReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val reply = Notification.Action.Builder(null, "Reply", replyIntent)
            .addRemoteInput(RemoteInput.Builder(RESULT_KEY).setLabel("Reply").build())
            .build()
        val style = Notification.MessagingStyle(me).apply { messages.forEach { addMessage(it) } }

        nm.notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(app.pixellink.R.drawable.ic_stat_link)
            .setStyle(style)
            // Real chat apps also set plain title/text; listeners don't always get the message list.
            .setContentTitle(messages.last().senderPerson?.name)
            .setContentText(messages.last().text)
            .addAction(reply)
            .setOnlyAlertOnce(!alert)
            .build())
    }

    class TestReplyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(RESULT_KEY) ?: return
            onReply(context, text.toString())
        }
    }
}
