package app.unwalled.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * The phone's long-term TLS identity: an EC P-256 key in the Android Keystore with the
 * self-signed certificate Android generates for it. The Mac pins this certificate's fingerprint.
 */
object Identity {
    private const val ALIAS = "unwalled-identity"
    private const val DAY_MS = 86_400_000L

    val fingerprint: String get() = sha256Hex(entry().certificate.encoded)

    /** TLS 1.3 context that presents our certificate and accepts only the pinned Mac. */
    fun sslContext(pinnedMacFingerprint: String): SSLContext {
        val entry = entry()
        val chain = arrayOf(entry.certificate as X509Certificate)
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = chain
            override fun getPrivateKey(alias: String?): PrivateKey = entry.privateKey
        }
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String?) =
                throw CertificateException("Phone never acts as a server")

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String?) {
                val fp = sha256Hex(chain.first().encoded)
                if (fp != pinnedMacFingerprint) throw CertificateException("Unexpected Mac certificate ${fp.take(16)}")
            }

            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(trustManager), null) }
    }

    private fun entry(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generate()
        return keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS signs pre-hashed data, so NONE is needed alongside SHA-256.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=Unwalled Phone"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 3650 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
