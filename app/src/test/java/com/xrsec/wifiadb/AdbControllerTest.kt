package com.xrsec.wifiadb

import com.xrsec.wifiadb.core.AdbController
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbControllerTest {

    @Test
    fun testDefaultPort() {
        assertEquals(5555, AdbController.DEFAULT_PORT)
    }

    @Test
    fun testPermissionTypeEnum() {
        assertEquals(3, AdbController.PermissionType.values().size)
        assertEquals(AdbController.PermissionType.NONE, AdbController.PermissionType.valueOf("NONE"))
        assertEquals(AdbController.PermissionType.SECURE_SETTINGS, AdbController.PermissionType.valueOf("SECURE_SETTINGS"))
        assertEquals(AdbController.PermissionType.ROOT, AdbController.PermissionType.valueOf("ROOT"))
    }

    @Test
    fun testDynamicTlsPortNullWhenCommandFailsOrEmpty() {
        // In local JVM test environment without adbd, getDynamicTlsPort should safely return null
        val port = AdbController.getDynamicTlsPort()
        // Should either be null or a positive port
        if (port != null) {
            org.junit.Assert.assertTrue(port > 0)
        }
    }

    @Test(expected = Exception::class)
    fun testAdbKeyPairReadWithInvalidKeyFails() {
        val tempPriv = java.io.File.createTempFile("test_priv_", ".tmp")
        val tempPub = java.io.File.createTempFile("test_pub_", ".tmp")
        try {
            tempPriv.writeText("INVALID NOT A PRIVATE KEY")
            tempPub.writeText("INVALID NOT A PUBLIC KEY")
            dadb.AdbKeyPair.read(tempPriv, tempPub)
        } finally {
            tempPriv.delete()
            tempPub.delete()
        }
    }

    @Test
    fun testAdbKeyPairGenerateAndReadValid() {
        val tempPriv = java.io.File.createTempFile("test_priv_", ".tmp")
        val tempPub = java.io.File.createTempFile("test_pub_", ".tmp")
        try {
            dadb.AdbKeyPair.generate(tempPriv, tempPub, "test@wifi-adb")
            val keyPair = dadb.AdbKeyPair.read(tempPriv, tempPub)
            org.junit.Assert.assertNotNull(keyPair)
            org.junit.Assert.assertNotNull(keyPair.privateKey())
            org.junit.Assert.assertTrue(keyPair.adbPublicKey().isNotEmpty())
        } finally {
            tempPriv.delete()
            tempPub.delete()
        }
    }
}
