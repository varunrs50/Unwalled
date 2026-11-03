package app.unwalled.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Lets the Mac control the phone while mirroring: clicks and drags become taps and swipes, and
 * keys become Back/Home or typed text. Android only allows this through an Accessibility service,
 * which the user switches on once. It does nothing until the Mac sends input.
 */
class ControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Phone control is on")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // MARK: Live touch

    /**
     * A finger that follows the Mac's mouse or trackpad as it moves, built from continued strokes:
     * "down" starts a stroke that stays pressed, each "move" extends it, "up" lifts it. Android
     * dispatches one piece at a time, so moves that arrive meanwhile are merged into the next piece.
     */
    private var stroke: GestureDescription.StrokeDescription? = null
    private var at = android.graphics.PointF()
    private var target: android.graphics.PointF? = null
    private var lifting = false
    private var inFlight = false
    private var lastPiece = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** `action` is down, move or up; `x` and `y` run from 0 to 1 across the screen. */
    fun touch(body: JSONObject) = main.post {
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val p = android.graphics.PointF(
            (body.optDouble("x", 0.5).coerceIn(0.0, 1.0) * (bounds.width() - 1)).toFloat(),
            (body.optDouble("y", 0.5).coerceIn(0.0, 1.0) * (bounds.height() - 1)).toFloat())
        when (body.optString("action")) {
            "down" -> {
                stroke = null; inFlight = false; lifting = false; target = null
                at = p
                val path = Path().apply { moveTo(p.x, p.y) }
                dispatch(GestureDescription.StrokeDescription(path, 0, 1, true))
            }
            "move" -> { target = p; next() }
            "up" -> { target = p; lifting = true; next() }
        }
    }

    private fun next() {
        val current = stroke ?: return
        if (inFlight) return
        val to = target ?: if (lifting) at else return
        target = null
        val now = android.os.SystemClock.uptimeMillis()
        val duration = (now - lastPiece).coerceIn(8, 40)
        // A continued stroke needs a non-empty path, so a still finger moves by a fraction of a pixel.
        val end = if (to.x == at.x && to.y == at.y) android.graphics.PointF(to.x + 0.5f, to.y) else to
        val path = Path().apply { moveTo(at.x, at.y); lineTo(end.x, end.y) }
        at = end
        dispatch(current.continueStroke(path, 0, duration, !lifting))
    }

    private fun dispatch(piece: GestureDescription.StrokeDescription) {
        stroke = piece
        inFlight = true
        lastPiece = android.os.SystemClock.uptimeMillis()
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(piece).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                if (!piece.willContinue()) { stroke = null; lifting = false } else next()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                inFlight = false
                stroke = null
                lifting = false
            }
        }, main)
        if (!ok) { inFlight = false; stroke = null }
    }

    fun key(name: String) {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }
        if (action != null) { performGlobalAction(action); return }
        val field = focusedField() ?: return
        when (name) {
            "backspace" -> edit(field) { text, start, end ->
                if (start != end) Triple(text.removeRange(start, end), start, start)
                else if (start > 0) Triple(text.removeRange(start - 1, start), start - 1, start - 1)
                else null
            }
            "enter" -> field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    /** Opens an app on the phone. Accessibility services may start activities from the background. */
    fun launch(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Types text into the focused field at the cursor. */
    fun type(text: String) {
        val field = focusedField() ?: return
        edit(field) { current, start, end ->
            Triple(current.replaceRange(start, end, text), start + text.length, start + text.length)
        }
    }

    private fun focusedField(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /** Applies `change(text, selectionStart, selectionEnd) -> (newText, newStart, newEnd)`. */
    private fun edit(field: AccessibilityNodeInfo, change: (String, Int, Int) -> Triple<String, Int, Int>?) {
        val text = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val start = field.textSelectionStart.takeIf { it in 0..text.length } ?: text.length
        val end = field.textSelectionEnd.takeIf { it in start..text.length } ?: start
        val (newText, newStart, newEnd) = change(text, start, end) ?: return
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        })
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newEnd)
        })
    }

    companion object {
        private const val TAG = "Unwalled"
        @Volatile var instance: ControlService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, ControlService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
