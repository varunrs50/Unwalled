package app.unwalled.extras

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import app.unwalled.R
import app.unwalled.link.FrameSocket
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** File transfer in both directions. See protocol/PROTOCOL.md, "Files". */
class FileTransfer(private val context: Context) {

    private class Incoming(
        val name: String,
        val size: Long,
        val mime: String,
        val temp: File,
        val out: FileOutputStream,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
        var received: Long = 0,
        var nextSeq: Int = 0,
    )

    private val incoming = HashMap<String, Incoming>()
    private val tempDir = File(context.cacheDir, "incoming").apply { mkdirs() }

    // MARK: Receiving (Mac → phone)

    fun offer(body: JSONObject) {
        val id = body.getString("id")
        val temp = File(tempDir, id.filter { it.isLetterOrDigit() || it == '-' })
        incoming[id] = Incoming(
            name = body.getString("name").replace('/', '_'),
            size = body.getLong("size"),
            mime = body.optString("mime").ifBlank { "application/octet-stream" },
            temp = temp,
            out = FileOutputStream(temp),
        )
        Log.i(TAG, "Receiving ${body.getString("name")} (${body.getLong("size")} bytes)")
    }

    /** Returns a failure `file.result` if the chunk is bad, otherwise null. */
    fun chunk(body: JSONObject): JSONObject? {
        val id = body.getString("id")
        val file = incoming[id] ?: return null
        if (body.getInt("seq") != file.nextSeq) return fail(id, "Chunks arrived out of order")
        val data = Base64.decode(body.getString("data"), Base64.DEFAULT)
        file.out.write(data)
        file.digest.update(data)
        file.received += data.size
        file.nextSeq++
        return if (file.received > file.size) fail(id, "More data than announced") else null
    }

    fun done(body: JSONObject): JSONObject {
        val id = body.getString("id")
        val file = incoming.remove(id) ?: return JSONObject().put("id", id).put("ok", false).put("error", "Unknown transfer")
        file.out.close()
        val sha = file.digest.digest().joinToString("") { "%02x".format(it) }
        if (file.received != file.size || sha != body.getString("sha256")) {
            file.temp.delete()
            return JSONObject().put("id", id).put("ok", false).put("error", "File arrived damaged")
        }
        return try {
            val uri = saveToDownloads(file)
            notifyReceived(file.name, uri, file.mime)
            Log.i(TAG, "Saved ${file.name} to Downloads/Unwalled")
            JSONObject().put("id", id).put("ok", true)
        } catch (e: Exception) {
            JSONObject().put("id", id).put("ok", false).put("error", "Couldn't save: ${e.message}")
        } finally {
            file.temp.delete()
        }
    }

    fun cancel(id: String) {
        incoming.remove(id)?.let { it.out.close(); it.temp.delete() }
    }

    private fun fail(id: String, error: String): JSONObject {
        cancel(id)
        return JSONObject().put("id", id).put("ok", false).put("error", error)
    }

    private fun saveToDownloads(file: Incoming): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, file.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Unwalled")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads unavailable")
        resolver.openOutputStream(uri)!!.use { out -> file.temp.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun notifyReceived(name: String, uri: Uri, mime: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Files from Mac", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, name.hashCode(),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(name.hashCode(), Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Received from Mac")
            .setContentText(name)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    // MARK: Sending (phone → Mac)

    /** Streams `uri` to the Mac over `fs`. Blocks until sent; the Mac's `file.result` arrives later. */
    fun send(fs: FrameSocket, uri: Uri, id: String) {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        fs.send("file.offer", JSONObject().put("id", id).put("name", name).put("size", size).put("mime", mime))

        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK_BYTES)
        var seq = 0
        resolver.openInputStream(uri)!!.use { input ->
            while (true) {
                // Fill the buffer completely so every chunk but the last is full size.
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                digest.update(buf, 0, n)
                fs.send("file.chunk", JSONObject().put("id", id).put("seq", seq++).put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
                if (n < buf.size) break
            }
        }
        fs.send("file.done", JSONObject().put("id", id).put("sha256", digest.digest().joinToString("") { "%02x".format(it) }))
        Log.i(TAG, "Sent $name ($size bytes) to the Mac")
    }

    companion object {
        private const val TAG = "Unwalled"
        private const val CHANNEL = "files"
        const val CHUNK_BYTES = 192 * 1024
    }
}
