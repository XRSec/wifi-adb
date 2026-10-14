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
}
