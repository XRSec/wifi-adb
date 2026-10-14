package com.xrsec.wifiadb

import com.xrsec.wifiadb.util.DeviceInfoHelper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoHelperTest {

    @Test
    fun testDeviceInfoFormatting() {
        val info = DeviceInfoHelper.DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 8",
            serialNumber = "1234567890ABCDEF",
            androidRelease = "14",
            sdkInt = 34,
            cpuAbi = "arm64-v8a"
        )

        assertEqualsSafe("Google Pixel 8", info.modelDisplayName)
        assertEqualsSafe("Android 14 (API 34)", info.systemVersionName)
        val summary = info.getFormattedSummary()
        assertTrue(summary.contains("Pixel 8"))
        assertTrue(summary.contains("1234567890ABCDEF"))
        assertTrue(summary.contains("Android 14 (API 34)"))
        assertTrue(summary.contains("arm64-v8a"))
    }

    @Test
    fun testDeviceInfoModelStartsWidthManufacturer() {
        val info = DeviceInfoHelper.DeviceInfo(
            manufacturer = "Xiaomi",
            model = "Xiaomi 13 Ultra",
            serialNumber = "SN9999",
            androidRelease = "13",
            sdkInt = 33,
            cpuAbi = "arm64-v8a"
        )
        // Should not duplicate manufacturer name
        assertEqualsSafe("Xiaomi 13 Ultra", info.modelDisplayName)
    }

    private fun assertEqualsSafe(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
