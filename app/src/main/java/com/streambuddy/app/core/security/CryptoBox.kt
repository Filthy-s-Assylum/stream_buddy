package com.streambuddy.app.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption used for data that Stream Buddy stores on disk.
 *
 * The format is self-describing: "v1.<iv hex>.<ciphertext+tag hex>". AES-256-GCM
 * provides confidentiality and integrity; a fresh 96-bit IV is generated for
 * every value. The production key is generated inside the Android Keystore and
 * never leaves it (StrongBox is used when the device supports it).
 */
interface StringCipher {
    fun encrypt(plainText: String): String
    fun decrypt(payload: String): String
}

class AesGcmCipher(
    private val key: SecretKey,
    private val random: SecureRandom = SecureRandom(),
) : StringCipher {

    override fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return "$PREFIX${iv.toHex()}.${cipherText.toHex()}"
    }

    override fun decrypt(payload: String): String {
        require(payload.startsWith(PREFIX)) { "Unsupported ciphertext" }
        val body = payload.removePrefix(PREFIX)
        val separator = body.indexOf('.')
        require(separator > 0 && separator < body.length - 1) { "Malformed ciphertext" }
        val iv = body.substring(0, separator).hexToBytes()
        val cipherText = body.substring(separator + 1).hexToBytes()
        require(iv.size == IV_BYTES) { "Malformed IV" }
        require(cipherText.size >= TAG_BITS / 8) { "Malformed ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    companion object {
        private const val PREFIX = "v1."
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Generates and caches the app's AES-256-GCM key inside the Android Keystore. */
class KeystoreCipher private constructor(private val cipher: AesGcmCipher) : StringCipher {
    override fun encrypt(plainText: String): String = cipher.encrypt(plainText)
    override fun decrypt(payload: String): String = cipher.decrypt(payload)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "stream_buddy_data_key_v1"

        fun create(): KeystoreCipher {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(ALIAS, null) as? SecretKey
            val key = existing ?: generateKey(useStrongBox = Build.VERSION.SDK_INT >= 28)
            return KeystoreCipher(AesGcmCipher(key))
        }

        private fun generateKey(useStrongBox: Boolean): SecretKey {
            fun generate(strongBox: Boolean): SecretKey {
                val builder = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                if (strongBox && Build.VERSION.SDK_INT >= 28) {
                    builder.setIsStrongBoxBacked(true)
                }
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE,
                )
                generator.init(builder.build())
                return generator.generateKey()
            }
            return try {
                generate(useStrongBox)
            } catch (_: StrongBoxUnavailableException) {
                generate(false)
            } catch (_: java.security.ProviderException) {
                // Some OEMs advertise StrongBox but fail at generation time.
                generate(false)
            }
        }
    }
}

/**
 * Process-wide access point. It is initialised by [com.streambuddy.app.StreamBuddyApp]
 * before Room or preferences are touched.
 */
object AppCrypto {
    @Volatile
    private var cipher: StringCipher? = null

    fun init(context: Context) {
        if (cipher == null) {
            synchronized(this) {
                if (cipher == null) cipher = KeystoreCipher.create()
            }
        }
    }

    fun installForTests(testCipher: StringCipher) {
        cipher = testCipher
    }

    fun encrypt(plainText: String): String = requireCipher().encrypt(plainText)

    fun decrypt(payload: String): String = requireCipher().decrypt(payload)

    fun decryptOrNull(payload: String?): String? {
        if (payload.isNullOrEmpty()) return null
        return runCatching { decrypt(payload) }.getOrNull()
    }

    private fun requireCipher(): StringCipher =
        cipher ?: error("AppCrypto.init() must be called before use")
}

private fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        out[i * 2] = digits[value ushr 4]
        out[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(out)
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = Character.digit(this[i * 2], 16)
        val low = Character.digit(this[i * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "Invalid hex" }
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

/** Small encrypted preference facade. Values are opaque on disk. */
class SecurePreferences(
    context: Context,
    fileName: String,
    private val codec: StringCipher = AppCrypto.let { crypto ->
        object : StringCipher {
            override fun encrypt(plainText: String) = crypto.encrypt(plainText)
            override fun decrypt(payload: String) = crypto.decrypt(payload)
        }
    },
) {
    private val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    fun getString(key: String, defaultValue: String? = null): String? {
        val stored = prefs.getString(key, null) ?: return defaultValue
        return runCatching { codec.decrypt(stored) }.getOrElse { defaultValue }
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, codec.encrypt(value)).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
