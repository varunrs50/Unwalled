package app.pixellink.mirror

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The phone's apps for the Mac's Apps window; clicking one opens it on the phone (see [ControlService.launch]). */
class AppList(private val context: Context) {

    /** Launchable apps with labels and small icons, for the Mac's app windows. */
    fun apps(): JSONObject {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = JSONArray()
        pm.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
            .forEach { (info, label) ->
                val icon = runCatching { info.loadIcon(pm).toPng(ICON_PX) }.getOrNull()
                list.put(JSONObject().put("package", info.activityInfo.packageName).put("label", label).put("icon", icon ?: JSONObject.NULL))
            }
        return JSONObject().put("apps", list)
    }

    private fun android.graphics.drawable.Drawable.toPng(px: Int): String {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, px, px)
        draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val ICON_PX = 64
    }
}
