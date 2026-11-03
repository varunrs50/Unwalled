package app.pixellink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pixellink.calls.CallBridge
import app.pixellink.link.LinkService
import app.pixellink.link.LinkState
import app.pixellink.link.LinkStatus
import app.pixellink.link.PairPayload
import app.pixellink.link.PairingStore
import app.pixellink.notifications.NotificationBridge
import app.pixellink.notifications.TestNotifications
import app.pixellink.extras.PhotosBridge
import app.pixellink.extras.requestClipTile
import app.pixellink.extras.ClipWatcher
import app.pixellink.sms.SmsBridge
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {

    /** A scanned or pasted pairing code waiting for the user to confirm. */
    private val pendingPayload = mutableStateOf<String?>(null)
    /** Bumped on every resume so settings changed outside the app are re-read. */
    private val resumeTick = mutableIntStateOf(0)

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick.intValue++
        // Lets the service pick up newly granted call access straight away.
        if (PairingStore(this).mac != null) LinkService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val mac = PairingStore(this).mac
        if (mac != null) {
            LinkService.start(this)
            if (LinkState.status.value == LinkStatus.NotPaired) LinkState.status.value = LinkStatus.Searching(mac.name)
        }
        requestMissingPermissions()
        pairingLink(intent)?.let { pendingPayload.value = it }

        setContent {
            PixelLinkTheme {
                HomeScreen(
                    pendingPayload, resumeTick.intValue, onScan = ::scan,
                    onRequestPermissions = ::requestMissingPermissions,
                    onRequestPermissionsFor = { permissionRequest.launch(it.toTypedArray()) },
                )
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairingLink(intent)?.let { pendingPayload.value = it }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("testNotification", false)) {
            TestNotifications.post(this, intent.getStringExtra("testText") ?: "Hi! This is a PixelLink test message.")
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("cancelTestNotification", false)) TestNotifications.cancel(this)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    /** Debug builds accept a pairing code from adb for testing; release builds never do. */
    /**
     * A `pixellink://pair?…` link, e.g. from scanning the Mac's code with the Camera app. It still
     * goes through the "Pair with…?" confirmation here and on the Mac.
     */
    private fun pairingLink(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "pixellink" && it.host == "pair" }?.toString()
            ?: if (BuildConfig.DEBUG) intent?.getStringExtra("pairPayload") else null

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { pendingPayload.value = it } }
            .addOnFailureListener { LinkState.error.value = "The scanner isn't available: ${it.message}" }
    }

    companion object {
        val REQUIRED_PERMISSIONS = listOf(Manifest.permission.ACCESS_LOCAL_NETWORK, Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun PixelLinkTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun HomeScreen(
    pendingPayload: MutableState<String?>,
    resumeTick: Int,
    onScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestPermissionsFor: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val status by LinkState.status.collectAsState()
    val error by LinkState.error.collectAsState()
    val localNetworkAllowed = remember(resumeTick) {
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    }

    pendingPayload.value?.let { text ->
        val payload = remember(text) { PairPayload.parse(text) }
        if (payload == null) {
            LinkState.error.value = "That isn't a PixelLink pairing code."
            pendingPayload.value = null
        } else {
            AlertDialog(
                onDismissRequest = { pendingPayload.value = null },
                title = { Text("Pair with ${payload.name}?") },
                text = { Text("This phone will connect to ${payload.name} at ${payload.host}. You'll confirm on the Mac too.") },
                confirmButton = {
                    TextButton(onClick = {
                        LinkService.pair(context, text)
                        pendingPayload.value = null
                    }) { Text("Pair") }
                },
                dismissButton = { TextButton(onClick = { pendingPayload.value = null }) { Text("Cancel") } },
            )
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PixelLink", style = MaterialTheme.typography.headlineMedium)

            if (!localNetworkAllowed) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PixelLink needs local network access to reach your Mac on this Wi-Fi.")
                        Button(onClick = onRequestPermissions) { Text("Allow") }
                    }
                }
            }

            when (val s = status) {
                LinkStatus.NotPaired -> NotPairedContent(onScan) { pendingPayload.value = it }
                is LinkStatus.Pairing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Click Allow on ${s.macName} to finish pairing.")
                }
                is LinkStatus.Searching -> PairedContent(s.macName, connected = false, resumeTick, onRequestPermissionsFor)
                is LinkStatus.Connected -> PairedContent(s.macName, connected = true, resumeTick, onRequestPermissionsFor)
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NotPairedContent(onScan: () -> Unit, onPaste: (String) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    Text("On your Mac, click the PixelLink icon in the menu bar and choose Pair a phone. Then scan the code it shows.")
    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan pairing code") }
    Text("Or paste the code copied from the Mac:", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        singleLine = true,
        placeholder = { Text("pixellink://pair?…") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { onPaste(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("Pair with pasted code")
    }
}

@Composable
private fun PairedContent(macName: String, connected: Boolean, resumeTick: Int, onRequestPermissionsFor: (List<String>) -> Unit) {
    val context = LocalContext.current
    val ignoringBattery = remember(resumeTick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val notificationsEnabled = remember(resumeTick) { NotificationBridge.isEnabled(context) }
    val callsAllowed = remember(resumeTick) {
        CallBridge.PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    val photosAllowed = remember(resumeTick) {
        PhotosBridge.PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    val smsAllowed = remember(resumeTick) {
        SmsBridge.PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    val controlOn = remember(resumeTick) { app.pixellink.mirror.ControlService.isEnabled(context) }
    val clipTileAdded = remember(resumeTick) { context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("clipTile", false) }
    val autoClip = remember(resumeTick) { ClipWatcher.canRun(context) }
    val activity = context as? android.app.Activity

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(macName, style = MaterialTheme.typography.titleMedium)
            Text(if (connected) "Connected" else "Looking for your Mac on this Wi-Fi…")
        }
    }

    val required = listOf(
        SetupItem("Notifications", "Show this phone's notifications on your Mac and reply from there.", notificationsEnabled, "Allow notification access") {
            context.startActivity(NotificationBridge.settingsIntent(context))
        },
        SetupItem("Calls", "See who's calling, answer or decline, and call from the Mac. Call audio stays on the phone.", callsAllowed, "Allow calls") {
            onRequestPermissionsFor(CallBridge.PERMISSIONS)
        },
        SetupItem("Messages", "Read and send texts from the Mac. They're sent from this phone's SIM.", smsAllowed, "Allow messages") {
            onRequestPermissionsFor(SmsBridge.PERMISSIONS)
        },
        SetupItem("Photos", "Browse this phone's photos on the Mac. Choose Allow all.", photosAllowed, "Allow photos") {
            onRequestPermissionsFor(PhotosBridge.PERMISSIONS)
        },
        SetupItem("Run in the background", "So Android doesn't cut the connection while the phone is idle.", ignoringBattery, "Allow") {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        },
        SetupItem("Phone control", "Use your Mac's mouse and keyboard on this phone's screen, and open the camera from the Mac. " +
            "In the list that opens, tap PixelLink phone control and switch it on. If Android says it's a restricted setting: " +
            "Settings → Apps → PixelLink → ⋮ → Allow restricted settings, then try again.", controlOn, "Turn on phone control") {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
    )
    val optional = listOf(
        SetupItem("Copy to Mac tile", "Send what you've copied to the Mac with one tap from Quick Settings.", clipTileAdded, "Add tile") {
            activity?.let(::requestClipTile)
        },
        SetupItem("Automatic clipboard", "Send what you copy to the Mac without tapping anything. Needs a one-time command from the Mac " +
            "(see the README, step 9)" + if (ClipWatcher.hasOverlay(context)) "." else ", and display over other apps.",
            autoClip, if (ClipWatcher.hasOverlay(context)) null else "Allow display over other apps") {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        },
    )
    val done = required.count { it.done }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (done == required.size) "All set" else "Set up: $done of ${required.size} done", style = MaterialTheme.typography.titleMedium)
            required.forEach { SetupRow(it) }
            Text("Optional", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            optional.forEach { SetupRow(it) }
        }
    }

    OutlinedButton(onClick = { LinkService.unpair(context) }) { Text("Unpair") }

    if (BuildConfig.DEBUG) {
        OutlinedButton(onClick = { TestNotifications.post(context) }) { Text("Send test notification") }
    }
}

private class SetupItem(val title: String, val detail: String, val done: Boolean, val action: String?, val onAction: () -> Unit)

/** One line of the setup checklist: a tick when done, otherwise what it's for and a button. */
@Composable
private fun SetupRow(item: SetupItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (item.done) "✓" else "○", style = MaterialTheme.typography.titleMedium,
            color = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            if (!item.done) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.action?.let { Button(onClick = item.onAction) { Text(it) } }
            }
        }
    }
}
