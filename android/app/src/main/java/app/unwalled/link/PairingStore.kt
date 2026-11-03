package app.unwalled.link

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

data class PairedMac(val name: String, val host: String, val port: Int, val fingerprint: String)

/** Contents of the QR code the Mac shows: unwalled://pair?v=1&name=&host=&port=&fp=&token= */
data class PairPayload(val name: String, val host: String, val port: Int, val fingerprint: String, val token: String) {
    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parse(text: String): PairPayload? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "unwalled" || uri.host != "pair" || uri.getQueryParameter("v") != "1") return null
            val fp = uri.getQueryParameter("fp")?.takeIf { HEX64.matches(it) } ?: return null
            return PairPayload(
                name = uri.getQueryParameter("name") ?: "Mac",
                host = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() } ?: return null,
                port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null,
                fingerprint = fp,
                token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null,
            )
        }
    }
}

class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    var mac: PairedMac?
        get() {
            val fp = prefs.getString("fingerprint", null) ?: return null
            return PairedMac(
                name = prefs.getString("name", "Mac")!!,
                host = prefs.getString("host", "")!!,
                port = prefs.getInt("port", 47820),
                fingerprint = fp,
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                clear()
            } else {
                putString("name", value.name)
                putString("host", value.host)
                putInt("port", value.port)
                putString("fingerprint", value.fingerprint)
            }
        }
}
