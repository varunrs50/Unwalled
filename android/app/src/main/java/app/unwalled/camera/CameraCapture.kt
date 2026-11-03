package app.unwalled.camera

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.unwalled.R
import app.unwalled.link.LinkBus
import app.unwalled.link.LinkService
import app.unwalled.mirror.ControlService
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import org.json.JSONObject
import java.io.File

/**
 * Insert from Phone (M7): the Mac asks for a photo or a document scan, the phone opens its camera
 * or Google's document scanner, and the result goes back as a file transfer with id `camera-<id>`.
 */
object CameraCapture {
    private const val CHANNEL = "camera"
    private const val PROMPT_ID = 10

    fun request(context: Context, id: String, mode: String) {
        val open = Intent(context, CameraCaptureActivity::class.java)
            .putExtra(CameraCaptureActivity.EXTRA_ID, id)
            .putExtra(CameraCaptureActivity.EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // With phone control on, Android lets us open the camera straight away.
        ControlService.instance?.let { it.startActivity(open); return }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Insert from Phone", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(PROMPT_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(if (mode == "scan") "Scan a document for your Mac" else "Take a photo for your Mac")
            .setContentText("Tap to open the ${if (mode == "scan") "scanner" else "camera"}")
            .setContentIntent(PendingIntent.getActivity(context, 5, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build())
    }

    internal fun cancelPrompt(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)

    internal fun result(id: String, ok: Boolean, error: String? = null) {
        LinkBus.send("camera.result", JSONObject().put("id", id).put("ok", ok).apply { error?.let { put("error", it) } })
    }
}

/** Invisible: wakes the phone, asks to unlock if needed, then runs the camera or the scanner. */
class CameraCaptureActivity : ComponentActivity() {
    private lateinit var id: String
    private var photo: Uri? = null

    private val takePicture = registerForActivityResult(TakePhoto()) { ok ->
        val uri = photo
        if (ok && uri != null) send(uri) else done(false)
    }
    private var started = false

    private val scan = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val scanned = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        // One page goes over as an image, several as a single PDF.
        val pages = scanned?.pages.orEmpty()
        val uri = if (pages.size == 1) pages[0].imageUri else scanned?.pdf?.uri
        if (result.resultCode == RESULT_OK && uri != null) send(uri) else done(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraCapture.cancelPrompt(this)
        id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        started = savedInstanceState != null
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    /** Starts once the screen is on and this is in front; asking to unlock earlier gets cancelled. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        val keyguard = getSystemService(KeyguardManager::class.java)
        val locked = keyguard.isKeyguardLocked
        // The secure camera works over the lock screen, like the camera shortcut; the scanner doesn't.
        if (locked && intent.getStringExtra(EXTRA_MODE) == "scan") {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = start(locked = false)
                override fun onDismissCancelled() = done(false, "Unlock your phone to scan")
                override fun onDismissError() = done(false, "Unlock your phone to scan")
            })
        } else {
            start(locked)
        }
    }

    private fun start(locked: Boolean) {
        if (intent.getStringExtra(EXTRA_MODE) == "scan") {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener { scan.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { done(false, "The document scanner isn't available: ${it.message}") }
        } else {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "Photo-${System.currentTimeMillis()}.jpg")
            photo = FileProvider.getUriForFile(this, "$packageName.files", file)
            takePicture.launch(photo!! to locked)
        }
    }

    private fun send(uri: Uri) {
        Log.i(TAG, "Sending ${intent.getStringExtra(EXTRA_MODE)} result to the Mac")
        LinkService.sendFiles(this, listOf(uri), id = "camera-$id")
        finish()
    }

    private fun done(ok: Boolean, error: String? = null) {
        if (!ok) Log.i(TAG, "Insert from Phone cancelled${error?.let { ": $it" } ?: ""}")
        CameraCapture.result(id, ok, error ?: if (ok) null else "Cancelled on the phone")
        finish()
    }

    /** Like [ActivityResultContracts.TakePicture], but uses the secure camera when the phone is locked. */
    private class TakePhoto : androidx.activity.result.contract.ActivityResultContract<Pair<Uri, Boolean>, Boolean>() {
        override fun createIntent(context: Context, input: Pair<Uri, Boolean>) =
            Intent(if (input.second) android.provider.MediaStore.ACTION_IMAGE_CAPTURE_SECURE else android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, input.first)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == RESULT_OK
    }

    companion object {
        private const val TAG = "Unwalled"
        const val EXTRA_ID = "id"
        const val EXTRA_MODE = "mode"
    }
}
