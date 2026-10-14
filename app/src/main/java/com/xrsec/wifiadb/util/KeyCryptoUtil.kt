package com.xrsec.wifiadb.util

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * ADB 密钥加密工具类：
 * 提供基于 AES-256-GCM 与 PBKDF2WithHmacSHA1 的高强度密钥落盘加密与还原能力。
 * 全面兼容 Android 4.4 (KitKat / API 19) 至 Android 15+。
 */
object KeyCryptoUtil {

    private const val MAGIC_HEADER = "ENC:GCM:v1\n"
    private val MAGIC_BYTES = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH_BITS = 256
    private const val ITERATION_COUNT = 10000
    private const val GCM_TAG_LENGTH = 128

    /**
     * 判断数据块是否为经本工具加密的格式（通过 MAGIC HEADER 判定）
     */
    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < MAGIC_BYTES.size + SALT_LENGTH + IV_LENGTH) return false
        for (i in MAGIC_BYTES.indices) {
            if (data[i] != MAGIC_BYTES[i]) return false
        }
        return true
    }

    /**
     * 使用 passphrase 加密私钥字节。
     * 若 passphrase 为空，直接返回明文。
     */
    fun encrypt(plainBytes: ByteArray, passphrase: String): ByteArray {
        if (passphrase.isBlank()) {
            return plainBytes
        }

        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        val random = SecureRandom()
        random.nextBytes(salt)
        random.nextBytes(iv)

        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plainBytes)

        val result = ByteArray(MAGIC_BYTES.size + SALT_LENGTH + IV_LENGTH + ciphertext.size)
        System.arraycopy(MAGIC_BYTES, 0, result, 0, MAGIC_BYTES.size)
        System.arraycopy(salt, 0, result, MAGIC_BYTES.size, SALT_LENGTH)
        System.arraycopy(iv, 0, result, MAGIC_BYTES.size + SALT_LENGTH, IV_LENGTH)
        System.arraycopy(ciphertext, 0, result, MAGIC_BYTES.size + SALT_LENGTH + IV_LENGTH, ciphertext.size)
        return result
    }

    /**
     * 使用 passphrase 解密私钥字节。
     * 若未加密，直接返回原数据；若加密但 passphrase 为空或错误，将抛出异常。
     */
    fun decrypt(encryptedBytes: ByteArray, passphrase: String): ByteArray {
        if (!isEncrypted(encryptedBytes)) {
            return encryptedBytes
        }

        if (passphrase.isBlank()) {
            throw IllegalArgumentException("Passphrase is required to decrypt ADB key")
        }

        val headerSize = MAGIC_BYTES.size
        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        val ciphertextSize = encryptedBytes.size - headerSize - SALT_LENGTH - IV_LENGTH
        val ciphertext = ByteArray(ciphertextSize)

        System.arraycopy(encryptedBytes, headerSize, salt, 0, SALT_LENGTH)
        System.arraycopy(encryptedBytes, headerSize + SALT_LENGTH, iv, 0, IV_LENGTH)
        System.arraycopy(encryptedBytes, headerSize + SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertextSize)

        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }
}
