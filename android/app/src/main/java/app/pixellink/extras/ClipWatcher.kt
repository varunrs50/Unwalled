package app.pixellink.extras

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Automatic phone → Mac clipboard. Android only lets the app in front read the clipboard, but it
 * logs "Denying clipboard access to <app>" each time something is copied while a background app
 * listens. With READ_LOGS (granted once over adb) we watch for that line, and with "Display over
 * other apps" we may then open the invisible [ClipSendActivity] for a moment to read and send it.
 */
class ClipWatcher(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    // Registering a listener is what makes Android log the denial for this app on every copy.
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { }
    @Volatile private var process: Process? = null
    /** True once we've seen another app's log line, i.e. the user allowed log access. */
    @Volatile private var fullAccess = false
    private var lastLaunch = 0L

    val canRun: Boolean get() = Companion.canRun(context)

    /**
     * Starts watching. Android 13+ asks the user to allow log access each time; if that was
     * declined or missed (screen off, say), calling this again restarts and asks again.
     */
    fun start() {
        if (!canRun) return
        if (process != null) {
            if (fullAccess) return
            process?.destroy()
            process = null
        }
        handler.post { clipboard.addPrimaryClipChangedListener(listener) }
        Thread({ watch() }, "clip-watcher").apply { isDaemon = true }.start()
    }

    fun stop() {
        handler.post { clipboard.removePrimaryClipChangedListener(listener) }
        process?.destroy()
        process = null
    }

    private fun watch() {
        val p = runCatching {
            // ActivityTaskManager lines come from the system, so seeing one proves we have access.
            ProcessBuilder("logcat", "-T", "1", "-v", "brief", "-s", "ClipboardService:E", "ActivityTaskManager:I")
                .redirectErrorStream(true).start()
        }.getOrElse { Log.w(TAG, "Clipboard watcher unavailable: ${it.message}"); return }
        process = p
        fullAccess = false
        Log.i(TAG, "Clipboard watcher started")
        val marker = "Denying clipboard access to ${context.packageName},"
        runCatching {
            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                for (line in lines) {
                    if (!fullAccess && line.startsWith("I/ActivityTaskManager")) {
                        fullAccess = true
                        Log.i(TAG, "Clipboard watcher has log access")
                    }
                    if (marker in line) handler.post(::onCopied)
                }
            }
        }
        if (process === p) process = null
        Log.i(TAG, "Clipboard watcher stopped")
    }

    private fun onCopied() {
        val now = System.currentTimeMillis()
        // Android logs the denial several times per copy.
        if (now - lastLaunch < DEBOUNCE_MS) return
        lastLaunch = now
        context.startActivity(
            Intent(context, ClipSendActivity::class.java)
                .putExtra(ClipSendActivity.EXTRA_AUTO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    companion object {
        private const val TAG = "PixelLink"
        private const val DEBOUNCE_MS = 1_000L

        fun hasLogAccess(context: Context) = context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        fun hasOverlay(context: Context) = Settings.canDrawOverlays(context)
        fun canRun(context: Context) = hasLogAccess(context) && hasOverlay(context)
    }
}
