package app.unwalled.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phone makers that stop background apps beyond what Android's own battery setting allows, and
 * where their switch lives (see dontkillmyapp.com). The screens move between OS versions, so each
 * guide lists the places we know and falls back to Unwalled's App info page.
 */
object MakerSettings {
    class Guide(val maker: String, val steps: String, private val screens: List<ComponentName>) {
        fun open(context: Context) {
            val pm = context.packageManager
            val intent = screens.asSequence()
                .map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                .firstOrNull { it.resolveActivity(pm) != null }
                ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /** The guide for this phone's maker, or null when Android's own setting is enough (Pixel, Motorola…). */
    fun guide(): Guide? = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> Guide("Samsung",
            "Open Settings → Battery → Background usage limits → Never sleeping apps, tap +, and add Unwalled.",
            listOf(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")))
        "xiaomi", "redmi", "poco" -> Guide("Xiaomi",
            "Turn on Autostart for Unwalled, then set its Battery saver to No restrictions (Settings → Apps → Manage apps → Unwalled).",
            listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        "oneplus", "oppo", "realme" -> Guide(Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            "In Settings → Apps → Unwalled → Battery, choose Unrestricted (or allow background activity) and turn on Auto launch.",
            listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
            ))
        "vivo", "iqoo" -> Guide("vivo",
            "In Settings → Battery → Background power consumption management, set Unwalled to Allow, and allow it to auto-start.",
            listOf(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
        "huawei", "honor" -> Guide(Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            "In Settings → Battery → App launch, turn Unwalled to Manage manually and allow all three options.",
            listOf(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
        "asus" -> Guide("ASUS",
            "In Mobile Manager → Auto-start manager, allow Unwalled, and turn off battery optimisation for it.",
            listOf(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity")))
        else -> null
    }

    /** Google Play services provides the QR and document scanners; some phones ship without it. */
    fun hasGoogleServices(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled
    }.getOrDefault(false)
}
