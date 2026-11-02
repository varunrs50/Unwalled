package app.pixellink.link

import org.json.JSONObject

/**
 * Lets other components (like the notification listener) send frames to the Mac
 * without knowing about the connection. Messages are dropped while disconnected.
 */
object LinkBus {
    @Volatile var sender: ((String, JSONObject) -> Unit)? = null

    val connected: Boolean get() = sender != null

    fun send(type: String, body: JSONObject) {
        sender?.invoke(type, body)
    }
}
