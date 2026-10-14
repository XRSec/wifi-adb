package com.xrsec.wifiadb

import com.xrsec.wifiadb.core.AdbController
import com.xrsec.wifiadb.core.ShellUtils
import com.xrsec.wifiadb.util.NetworkUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun testFormatAdbConnectCommand() {
        val cmdDefault = NetworkUtils.formatAdbConnectCommand("192.168.1.100")
        assertEquals("adb connect 192.168.1.100:5555", cmdDefault)

        val cmdCustomPort = NetworkUtils.formatAdbConnectCommand("10.0.0.5", 5556)
        assertEquals("adb connect 10.0.0.5:5556", cmdCustomPort)
    }

    @Test
    fun testAdbControllerConstants() {
        assertEquals(5555, AdbController.DEFAULT_PORT)
    }

    @Test
    fun testCommandResultSuccess() {
        val success = ShellUtils.CommandResult(0, "ok", "")
        assertTrue(success.isSuccess)

        val failed = ShellUtils.CommandResult(1, "", "error")
        assertFalse(failed.isSuccess)
    }
}
