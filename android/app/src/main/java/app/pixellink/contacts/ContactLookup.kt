package app.pixellink.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Finds the contact behind a phone number, for calls and messages. */
object ContactLookup {
    private const val PHOTO_PX = 96

    data class Match(val name: String, val photo: String?)

    /** Name, and optionally photo (base64 PNG), for a number in contacts; null if unknown or not allowed. */
    fun find(context: Context, number: String, withPhoto: Boolean = false): Match? {
        if (number.isBlank()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val photo = if (withPhoto) c.getString(1)?.let { photo(context, it) } else null
                Match(c.getString(0), photo)
            }
        }.getOrNull()
    }

    private fun photo(context: Context, uri: String): String? = runCatching {
        val bitmap = context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } ?: return null
        val out = ByteArrayOutputStream()
        Bitmap.createScaledBitmap(bitmap, PHOTO_PX, PHOTO_PX, true).compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
