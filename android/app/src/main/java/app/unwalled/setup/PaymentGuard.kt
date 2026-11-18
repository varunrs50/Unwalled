package app.unwalled.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.unwalled.R
import app.unwalled.mirror.ControlService
import app.unwalled.mirror.ScreenMirror

/**
 * Keeps UPI and banking apps working. Their fraud checks refuse payments while the screen is being
 * shared or an app can control the phone (screen-sharing scams work exactly that way), so when one
 * comes to the front Unwalled ends screen sharing and switches its phone control off.
 *
 * Payment apps are found the way Android routes payments: every UPI app opens `upi://` links.
 * Everyday apps that also take UPI (WhatsApp, Amazon…) are only guarded once the NPCI PIN screen,
 * which every UPI app uses, appears.
 */
object PaymentGuard {
    private const val TAG = "Unwalled"
    private const val CHANNEL = "payments"
    private const val NOTIFICATION_ID = 11
    private const val CACHE_MS = 10 * 60_000L

    /** Apps that take UPI payments but are mostly used for other things. */
    private val EVERYDAY_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "in.amazon.mShop.android.shopping", "com.tatadigital.tcp",
        "com.flipkart.android", "in.swiggy.android", "com.application.zomato", "com.google.android.youtube",
    )

    /** Bank apps that may not open `upi://` links themselves. */
    private val BANK_APPS = setOf(
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.snapwork.hdfc", "com.hdfcbank.payzapp",
        "com.csam.icici.bank.imobile", "com.axis.mobile", "com.msf.kbank.mobile", "com.bankofbaroda.mconnect",
        "com.idfcfirstbank.optimus", "com.fss.unbipsp", "com.canarabank.mobility", "com.infrasoft.uboi",
    )

    /** The NPCI UPI PIN screen's package, used inside every UPI app. */
    private const val NPCI_PIN_SCREEN = "org.npci."

    private var paymentApps: Set<String> = emptySet()
    private var cachedAt = 0L

    private fun paymentApps(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt > CACHE_MS || paymentApps.isEmpty()) {
            val upi = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
            paymentApps = context.packageManager.queryIntentActivities(upi, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }.toSet() - EVERYDAY_APPS + BANK_APPS - context.packageName
            cachedAt = now
        }
        return paymentApps
    }

    /** Called with each new window the Accessibility service sees. */
    fun onWindow(service: ControlService, packageName: String, className: String) {
        val payment = className.startsWith(NPCI_PIN_SCREEN) || packageName in paymentApps(service)
        if (payment) stepAside(service, label(service, packageName))
    }

    /** Ends screen sharing and switches phone control off, so the payment app's checks pass. */
    fun stepAside(context: Context, app: String?) {
        Log.i(TAG, "Stepping aside for a payment app")
        ScreenMirror.end(context)
        val control = ControlService.instance
        if (control != null) {
            notifyPaused(context, app)
            // An app can switch its own Accessibility service off, but only the user can switch it on.
            control.disableSelf()
        }
    }

    private fun label(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private fun notifyPaused(context: Context, app: String?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Payments", NotificationManager.IMPORTANCE_DEFAULT))
        val settings = PendingIntent.getActivity(
            context, 6, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Phone control is off so ${app ?: "your payment app"} works")
            .setContentText("Turn it back on after paying: tap here, then Unwalled phone control.")
            .setStyle(Notification.BigTextStyle().bigText(
                "Payment apps refuse to work while another app can control the phone, so Unwalled switched its phone control off " +
                    "and ended screen sharing. After paying, tap here and switch on Unwalled phone control to use the phone from your Mac again."))
            .setContentIntent(settings)
            .setAutoCancel(true)
            .build())
    }
}

/** Quick Settings tile "Payment mode": ends screen sharing and switches phone control off now. */
class PaymentModeTile : android.service.quicksettings.TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (ControlService.instance != null) android.service.quicksettings.Tile.STATE_INACTIVE
                else android.service.quicksettings.Tile.STATE_ACTIVE
            subtitle = if (ControlService.instance != null) "Tap before paying" else "Ready to pay"
            updateTile()
        }
    }

    override fun onClick() {
        PaymentGuard.stepAside(this, null)
        android.widget.Toast.makeText(this, "Ready to pay: Unwalled's phone control and screen sharing are off", android.widget.Toast.LENGTH_LONG).show()
        onStartListening()
    }
}
