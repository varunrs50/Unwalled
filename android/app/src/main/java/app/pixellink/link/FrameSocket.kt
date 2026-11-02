package app.pixellink.link

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import javax.net.ssl.SSLSocket

data class Frame(val type: String, val id: String, val body: JSONObject)

/** Length-prefixed JSON envelopes over TLS, as described in protocol/PROTOCOL.md. */
class FrameSocket(val socket: SSLSocket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
    private val writeLock = Any()

    fun send(type: String, body: JSONObject = JSONObject(), replyTo: String? = null): String {
        val id = replyTo ?: UUID.randomUUID().toString()
        val envelope = JSONObject()
            .put("v", 1)
            .put("id", id)
            .put("type", type)
            .put("ts", System.currentTimeMillis())
            .put("body", body)
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
        return id
    }

    /** Blocks until a frame arrives; throws on timeout, close or malformed data. */
    fun receive(): Frame {
        val length = input.readInt()
        if (length !in 1..MAX_FRAME) throw IOException("Bad frame length $length")
        val buf = ByteArray(length)
        input.readFully(buf)
        val envelope = JSONObject(String(buf, Charsets.UTF_8))
        return Frame(envelope.getString("type"), envelope.optString("id"), envelope.optJSONObject("body") ?: JSONObject())
    }

    override fun close() = runCatching { socket.close() }.let { }

    companion object {
        const val MAX_FRAME = 16 * 1024 * 1024
    }
}
