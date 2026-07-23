package pl.bitforge.domofon.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM around the two values that must not sit in plaintext on disk: the broker
 * password and the RTSP URL (which carries the camera credentials inline).
 *
 * The key lives in the Android Keystore, so it is hardware-backed where the phone has a
 * TEE/StrongBox and never leaves it — [encrypt] and [decrypt] pass data through, the raw
 * key is not readable by this process or by anyone with the app's data directory.
 *
 * Deliberately hand-rolled rather than `androidx.security:security-crypto`: Jetpack
 * Security is deprecated and its `EncryptedSharedPreferences` is no longer recommended.
 * This is the replacement Google points at — Keystore directly.
 *
 * **No user authentication is required on the key.** That is intentional, not an
 * oversight: [GeofenceReceiver] decrypts the broker password while the phone is locked in
 * a pocket during the drive home. Requiring auth here would break the one feature the
 * geofence exists for. Confirmation for *acting* on the gate is enforced separately, at
 * the notification action, by `requireUnlockForCommands`.
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "domofon.config.secrets.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val TAG = "Domofon"

    /** Base64 of `iv || ciphertext`, or "" for empty input. */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Never log the plaintext, and never crash the app over a settings write.
            Log.e(TAG, "secret encryption failed", e)
            ""
        }
    }

    /** Inverse of [encrypt]. Returns "" for anything unreadable — see the note below. */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
                )
            }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Expected and survivable in one real case: the Keystore drops app keys when
            // the user adds or removes a lock screen. The password is then simply gone and
            // must be re-entered — which the settings screen surfaces as an empty field
            // rather than an inexplicable auth failure against the broker.
            Log.w(TAG, "secret could not be decrypted; treating as unset", e)
            ""
        }
    }

    /**
     * Synchronized, and that is load-bearing. The settings UI thread, [GateRepository]'s
     * scope and [GeofenceReceiver]'s coroutine can all arrive here at once on first run;
     * unguarded, two of them miss the alias and both call `generateKey()`, which silently
     * *overwrites* the existing entry. Whatever the first thread encrypted then fails its
     * GCM tag forever after — the broker password vanishes, indistinguishable from never
     * having been set.
     */
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Let the platform pick the IV. A reused IV under GCM is catastrophic, and
                // this is the only way to be sure we never supply one.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
