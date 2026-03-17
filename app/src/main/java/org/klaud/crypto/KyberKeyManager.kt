package org.klaud.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KyberKeyManager {
    private const val TAG = "KyberKeyManager"
    private const val PREFS_NAME = "klaud_kyber_keys"
    private const val KEY_PRIVATE_ENCRYPTED = "kyber_private_key_encrypted"
    private const val KEY_PUBLIC = "kyber_public_key"
    private const val KEY_AES_KEY = "kyber_aes_key"

    const val PUBLIC_KEY_SIZE = 1184
    const val CIPHERTEXT_SIZE = 1088
    const val SHARED_SECRET_SIZE = 32

    private lateinit var prefs: SharedPreferences
    private var privateKey: MLKEMPrivateKeyParameters? = null
    private var publicKey: MLKEMPublicKeyParameters? = null
    private var keyStore: KeyStore? = null

    init {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(BouncyCastleProvider())
        }
    }

    fun init(context: Context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initializeKeyStore()
        loadOrGenerateKeys()
        Log.i(TAG, "Kyber Public Key Hash: ${getPublicKeyHash()}")
    }

    private fun initializeKeyStore() {
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing KeyStore", e)
        }
    }

    private fun loadOrGenerateKeys() {
        val publicKeyBytes = prefs.getString(KEY_PUBLIC, null)
        val encryptedPrivateKey = prefs.getString(KEY_PRIVATE_ENCRYPTED, null)

        if (publicKeyBytes != null && encryptedPrivateKey != null) {
            try {
                val pubKeyBytes = Base64.decode(publicKeyBytes, Base64.NO_WRAP)
                val privKeyBytes = decryptPrivateKey(encryptedPrivateKey)

                publicKey = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pubKeyBytes)
                privateKey = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, privKeyBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading keys, generating new pair", e)
                generateNewKeys()
            }
        } else {
            generateNewKeys()
        }
    }

    private fun generateNewKeys() {
        try {
            val generator = MLKEMKeyPairGenerator()
            val genParams = MLKEMKeyGenerationParameters(SecureRandom(), MLKEMParameters.ml_kem_768)
            generator.init(genParams)
            val keyPair = generator.generateKeyPair()

            publicKey = keyPair.public as MLKEMPublicKeyParameters
            privateKey = keyPair.private as MLKEMPrivateKeyParameters

            val publicKeyBytes = Base64.encodeToString(publicKey!!.encoded, Base64.NO_WRAP)
            prefs.edit().putString(KEY_PUBLIC, publicKeyBytes).apply()

            val encryptedPrivateKey = encryptPrivateKey(privateKey!!.encoded)
            prefs.edit().putString(KEY_PRIVATE_ENCRYPTED, encryptedPrivateKey).apply()

            Log.i(TAG, "Generated new ML-KEM-768 keypair")
        } catch (e: Exception) {
            Log.e(TAG, "Error generating ML-KEM-768 keypair", e)
            throw RuntimeException("Failed to generate Kyber keypair", e)
        }
    }

    private fun encryptPrivateKey(keyBytes: ByteArray): String {
        try {
            val aesKey = getOrCreateAesKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey)

            val iv = cipher.iv
            val encrypted = cipher.doFinal(keyBytes)

            val combined = iv + encrypted
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting private key", e)
            throw RuntimeException("Failed to encrypt private key", e)
        }
    }

    private fun decryptPrivateKey(encryptedBase64: String): ByteArray {
        try {
            val aesKey = getOrCreateAesKey()
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)

            val iv = combined.sliceArray(0 until 12)
            val ciphertext = combined.sliceArray(12 until combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, aesKey, spec)

            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting private key", e)
            throw RuntimeException("Failed to decrypt private key", e)
        }
    }

    private fun getOrCreateAesKey(): SecretKey {
        val keyAlias = KEY_AES_KEY
        try {
            val existingEntry = keyStore?.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (existingEntry != null) {
                return existingEntry.secretKey
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting/creating AES key", e)
            throw RuntimeException("Failed to get AES key", e)
        }
    }

    fun getPublicKeyBytes(): ByteArray {
        return publicKey?.encoded ?: throw IllegalStateException("Public key not initialized")
    }

    fun getPublicKeyHash(): String {
        val bytes = getPublicKeyBytes()
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun decapsulate(ciphertext: ByteArray): ByteArray {
        val privateKey = privateKey ?: throw IllegalStateException("Private key not initialized")
        val extractor = MLKEMExtractor(privateKey)
        return extractor.extractSecret(ciphertext)
    }

    fun encapsulate(peerPublicKeyBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val peerPublicKey = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, peerPublicKeyBytes)
        val kemGenerator = MLKEMGenerator(SecureRandom())
        val secretWithEncapsulation = kemGenerator.generateEncapsulated(peerPublicKey)

        val ciphertext = secretWithEncapsulation.encapsulation
        val sharedSecret = secretWithEncapsulation.secret

        return Pair(ciphertext, sharedSecret)
    }

    fun clearKeys() {
        prefs.edit().remove(KEY_PUBLIC).remove(KEY_PRIVATE_ENCRYPTED).apply()
        privateKey = null
        publicKey = null
        Log.i(TAG, "Keys cleared")
    }
}
