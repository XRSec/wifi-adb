package com.xrsec.wifiadb

import com.xrsec.wifiadb.util.KeyCryptoUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class KeyCryptoUtilTest {

    @Test
    fun testPlaintextPassthroughWhenNoPassphrase() {
        val original = "my-private-key-data".toByteArray()
        val encrypted = KeyCryptoUtil.encrypt(original, "")
        assertArrayEquals(original, encrypted)
        assertFalse(KeyCryptoUtil.isEncrypted(encrypted))

        val decrypted = KeyCryptoUtil.decrypt(encrypted, "")
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun testEncryptionAndDecryptionWithPassphrase() {
        val original = "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----".toByteArray()
        val passphrase = "SecretPassword123!"

        val encrypted = KeyCryptoUtil.encrypt(original, passphrase)
        assertTrue(KeyCryptoUtil.isEncrypted(encrypted))
        assertFalse(original.contentEquals(encrypted))

        val decrypted = KeyCryptoUtil.decrypt(encrypted, passphrase)
        assertArrayEquals(original, decrypted)
    }

    @Test(expected = Exception::class)
    fun testDecryptionWithWrongPassphraseFails() {
        val original = "sensitive-rsa-data".toByteArray()
        val passphrase = "CorrectPassword"
        val wrongPassphrase = "WrongPassword"

        val encrypted = KeyCryptoUtil.encrypt(original, passphrase)
        KeyCryptoUtil.decrypt(encrypted, wrongPassphrase)
    }
}
