package com.example.security

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionManager {

    private const val RSA_ALGORITHM = "RSA"
    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val AES_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(RSA_ALGORITHM)
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    fun publicKeyToString(publicKey: PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun stringToPublicKey(keyStr: String): PublicKey {
        val bytes = Base64.decode(keyStr, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(bytes)
        val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
        return keyFactory.generatePublic(spec)
    }

    fun privateKeyToString(privateKey: PrivateKey): String {
        return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
    }

    fun stringToPrivateKey(keyStr: String): PrivateKey {
        val bytes = Base64.decode(keyStr, Base64.NO_WRAP)
        val spec = PKCS8EncodedKeySpec(bytes)
        val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
        return keyFactory.generatePrivate(spec)
    }

    fun generateSessionAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(AES_ALGORITHM)
        keyGen.init(256)
        return keyGen.generateKey()
    }

    fun encryptAesKeyWithRsa(aesKey: SecretKey, recipientPublicKey: PublicKey): String {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey)
        val encryptedBytes = cipher.doFinal(aesKey.encoded)
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    fun decryptAesKeyWithRsa(encryptedAesKeyStr: String, myPrivateKey: PrivateKey): SecretKey {
        val encryptedBytes = Base64.decode(encryptedAesKeyStr, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, myPrivateKey)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return SecretKeySpec(decryptedBytes, AES_ALGORITHM)
    }

    fun encryptWithAes(plainText: String, aesKey: SecretKey): AesEncryptionResult {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, ivSpec)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        return AesEncryptionResult(
            encryptedDataBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptWithAes(encryptedDataBase64: String, aesKey: SecretKey, ivBase64: String): String {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val ivSpec = IvParameterSpec(iv)
        val encryptedBytes = Base64.decode(encryptedDataBase64, Base64.NO_WRAP)
        
        cipher.init(Cipher.DECRYPT_MODE, aesKey, ivSpec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun getFingerprint(publicKeyStr: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(Base64.decode(publicKeyStr, Base64.NO_WRAP))
            hash.joinToString(":") { "%02X".format(it) }.take(23) + "..."
        } catch (e: Exception) {
            "Unknown"
        }
    }

    data class AesEncryptionResult(
        val encryptedDataBase64: String,
        val ivBase64: String
    )
}
