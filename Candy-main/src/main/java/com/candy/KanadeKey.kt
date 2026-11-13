package com.candy

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/*
 * KanadeKey: a sign-in key that lives inside this phone, like a passkey.
 * The private half is made in the phone's secure hardware and is released only by a fingerprint or face,
 * so it never leaves the phone and cannot be copied. The bot keeps only the public half.
 * Signing in = the bot sends a random challenge, the phone signs it after the fingerprint, the bot checks it.
 * Your password still works. Needs Android 9 or newer.
 */
object KanadeKey {
    private const val PREFS = "candy_kanade_key"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when this phone can hold the key (Android 9+ and a fingerprint, face or iris sensor). */
    fun supported(c: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Impl.hardware(c)

    /** The id of the key kept on this phone, if there is one. */
    fun localId(c: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Impl.hasKey()) prefs(c).getString("id", null) else null

    /** Makes the key and gives back its public half (base64), or null if the phone refused. */
    fun createKey(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Impl.create() else null

    fun saveId(c: Context, id: String) {
        prefs(c).edit().putString("id", id).apply()
    }

    /** Throws the key away (used when it is deleted, or the account signs out for good). */
    fun forget(c: Context) {
        prefs(c).edit().remove("id").apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Impl.delete()
    }

    /** Asks for the fingerprint, then signs [message]. */
    fun sign(c: Context, title: String, cancel: String, message: ByteArray, done: (ByteArray) -> Unit, failed: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            failed()
            return
        }
        Impl.sign(c, title, cancel, message, done, failed)
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    @RequiresApi(Build.VERSION_CODES.P)
    private object Impl {
        private const val ALIAS = "kanade_key_candy"

        fun hardware(c: Context): Boolean {
            val pm = c.packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                pm.hasSystemFeature(PackageManager.FEATURE_FACE) ||
                pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
        }

        private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

        fun hasKey(): Boolean = try {
            store().containsAlias(ALIAS)
        } catch (e: Exception) {
            false
        }

        fun delete() {
            try {
                store().deleteEntry(ALIAS)
            } catch (e: Exception) {
                // Nothing to delete.
            }
        }

        @Suppress("DEPRECATION")
        fun create(): String? = try {
            delete()
            val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                b.setUserAuthenticationValidityDurationSeconds(-1)
            }
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            gen.initialize(b.build())
            KanadeKey.b64(gen.generateKeyPair().public.encoded)
        } catch (e: Exception) {
            null
        }

        fun sign(c: Context, title: String, cancel: String, message: ByteArray, done: (ByteArray) -> Unit, failed: () -> Unit) {
            try {
                val priv = store().getKey(ALIAS, null) as? PrivateKey
                if (priv == null) {
                    failed()
                    return
                }
                val sig = Signature.getInstance("SHA256withECDSA")
                sig.initSign(priv)
                val prompt = BiometricPrompt.Builder(c)
                    .setTitle(title)
                    .setNegativeButton(cancel, c.mainExecutor) { _, _ -> failed() }
                    .build()
                prompt.authenticate(
                    BiometricPrompt.CryptoObject(sig),
                    CancellationSignal(),
                    c.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val s = result.cryptoObject?.signature
                            if (s == null) {
                                failed()
                                return
                            }
                            try {
                                s.update(message)
                                done(s.sign())
                            } catch (e: Exception) {
                                failed()
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            failed()
                        }
                    },
                )
            } catch (e: Exception) {
                // The key is gone or was invalidated (a new fingerprint was added).
                failed()
            }
        }
    }
}
