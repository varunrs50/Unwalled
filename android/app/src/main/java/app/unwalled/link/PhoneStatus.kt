package app.unwalled.link

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/** Builds the body of a `status.update` frame. */
object PhoneStatus {
    /** The active SIMs, so the Mac can offer a choice for texts; empty unless there's more than one. */
    private fun sims(context: Context): JSONArray {
        val list = JSONArray()
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return list
        val subs = runCatching { context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList }.getOrNull().orEmpty()
        if (subs.size < 2) return list
        subs.sortedBy { it.simSlotIndex }.forEach { sub ->
            val name = (sub.displayName ?: sub.carrierName)?.toString()?.takeIf { it.isNotBlank() }
            list.put(JSONObject().put("id", sub.subscriptionId).put("label", "SIM ${sub.simSlotIndex + 1}${name?.let { " · $it" } ?: ""}"))
        }
        return list
    }

    fun snapshot(context: Context): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        } ?: -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val signal = runCatching {
            context.getSystemService(TelephonyManager::class.java).signalStrength?.level
        }.getOrNull()

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val wifi = caps?.signalStrength?.takeIf { network == "wifi" && it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }?.let {
            val wm = context.getSystemService(WifiManager::class.java)
            wm.calculateSignalLevel(it) * 4 / wm.maxSignalLevel.coerceAtLeast(1)
        }

        return JSONObject()
            .put("sims", sims(context))
            .put("battery", level)
            .put("charging", charging)
            .put("signal", signal ?: JSONObject.NULL)
            .put("network", network)
            .put("wifi", wifi ?: JSONObject.NULL)
    }
}
