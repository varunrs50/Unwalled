package app.pixellink.extras

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Photos for the Mac's Photos window; full images go over the file transfer. */
class PhotosBridge(private val context: Context) {

    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /**
     * One page of photos, newest first, optionally from a single album (folder), plus every album
     * with its count. Filtering and paging are done here rather than in the query, since Android
     * doesn't apply those arguments reliably across providers.
     */
    fun page(limit: Int, offset: Int, album: String): JSONObject {
        val photos = JSONArray()
        val albums = linkedMapOf<String, Int>()
        var total = 0
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val bucket = c.getString(bucketCol) ?: "Other"
                    albums[bucket] = (albums[bucket] ?: 0) + 1
                    if (album.isNotEmpty() && bucket != album) continue
                    val index = total++
                    if (index < offset || photos.length() >= limit) continue
                    val id = c.getLong(0)
                    val thumb = runCatching { context.contentResolver.loadThumbnail(uri(id), Size(THUMB_PX, THUMB_PX), null) }
                        .getOrNull() ?: continue
                    val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                    photos.put(JSONObject()
                        .put("id", id)
                        .put("name", c.getString(1).orEmpty())
                        .put("time", taken)
                        .put("width", c.getInt(4))
                        .put("height", c.getInt(5))
                        .put("size", c.getLong(6))
                        .put("thumb", thumb.toBase64Jpeg()))
                }
            }
        }.onFailure { Log.w("PixelLink", "Photos unavailable: ${it.message}") }
        val albumList = JSONArray()
        albums.entries.sortedByDescending { it.value }.forEach { albumList.put(JSONObject().put("name", it.key).put("count", it.value)) }
        return JSONObject().put("photos", photos).put("offset", offset).put("total", total).put("album", album).put("albums", albumList)
    }

    fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun Bitmap.toBase64Jpeg(): String {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val THUMB_PX = 240
        val PERMISSIONS = listOf(Manifest.permission.READ_MEDIA_IMAGES)
    }
}
