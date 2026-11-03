package app.unwalled.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import app.unwalled.BuildConfig
import app.unwalled.extras.ClipWatcher
import app.unwalled.link.LinkState
import app.unwalled.link.PairingStore
import app.unwalled.mirror.ControlService
import app.unwalled.notifications.NotificationBridge
import java.io.File

/**
 * "Send diagnostics": what a bug report needs about this phone, plus Unwalled's own recent log
 * (only this app's lines, which never contain message text, names or numbers), shared as a file.
 */
object Diagnostics {
    fun share(activity: Activity) {
        val dir = File(activity.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "unwalled-diagnostics.txt")
        file.writeText(report(activity))
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Unwalled diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, "Send diagnostics"))
    }

    private fun report(context: Context): String = buildString {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        fun line(label: String, value: Any?) = appendLine("$label: $value")

        appendLine("Unwalled diagnostics")
        line("Unwalled", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
        line("Phone", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
        line("Build", Build.DISPLAY)
        line("Google Play services", if (MakerSettings.hasGoogleServices(context)) "yes" else "no")
        line("Maker battery guide", MakerSettings.guide()?.maker ?: "none needed")
        line("Paired", if (PairingStore(context).mac != null) "yes" else "no")
        line("Connection", LinkState.status.value::class.simpleName)
        appendLine()
        appendLine("Setup")
        line("  Notification access", NotificationBridge.isEnabled(context))
        line("  Calls", granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.READ_CALL_LOG))
        line("  Messages", granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS))
        line("  Photos", granted(Manifest.permission.READ_MEDIA_IMAGES))
        line("  Run in background", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
        line("  Phone control", ControlService.isEnabled(context))
        line("  Automatic clipboard", ClipWatcher.canRun(context))
        line("  Display over other apps", Settings.canDrawOverlays(context))
        appendLine()
        line("H.264 encoders", MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .joinToString { it.name })
        appendLine()
        appendLine("Log (this app only)")
        // An app can always read its own process's log.
        val log = runCatching {
            ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500", "--pid=${android.os.Process.myPid()}")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        }.getOrElse { "Couldn't read the log: ${it.message}" }
        append(log)
    }
}
