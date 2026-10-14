package com.xrsec.wifiadb.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object AdbController {

    const val DEFAULT_PORT = 5555

    /**
     * 检测本地指定端口（默认 5555）是否已在监听中。
     * 本检测无需 Root 权限，直接尝试与 127.0.0.1 建立 Socket 连接。
     * 严禁在主线程调用，必须在 IO 调度器中执行。
     */
    fun isPortListening(port: Int = DEFAULT_PORT, timeoutMs: Int = 800): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        } catch (_: Throwable) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 读取系统属性 service.adb.tcp.port 的当前值。
     */
    fun getAdbTcpPort(): Int {
        val res = ShellUtils.exec("getprop service.adb.tcp.port")
        val output = res.stdout.trim()
        return output.toIntOrNull() ?: -1
    }

    /**
     * 判断无线调试是否处于活跃状态。
     * 按照安全与严谨性规范：以实际本地 Loopback Socket 探针连通性作为核心判定准则，
     * 不再单靠未经验证的 service.adb.tcp.port 属性值。
     */
    suspend fun isAdbWirelessActive(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        isPortListening(port)
    }

    /**
     * 通过 Root 权限开启无线调试 (默认端口 5555)。
     * 使用 && 串联原子指令，确保前置指令失败时即刻中止，不覆盖退出状态：
     *   setprop service.adb.tcp.port 5555 && stop adbd && start adbd
     */
    suspend fun startWirelessAdb(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        val command = "setprop service.adb.tcp.port $port && stop adbd && start adbd"
        val result = ShellUtils.execRoot(command)
        if (!result.isSuccess) {
            return@withContext false
        }

        // 轮询等待端口启动，最多等待 3 秒 (6 * 500ms)
        for (i in 0 until 6) {
            delay(500)
            if (isPortListening(port)) {
                return@withContext true
            }
        }
        isPortListening(port)
    }

    /**
     * 通过 Root 权限关闭无线调试，回退到 USB 调试模式 (-1)。
     * 使用 && 串联原子指令：
     *   setprop service.adb.tcp.port -1 && stop adbd && start adbd
     */
    suspend fun stopWirelessAdb(): Boolean = withContext(Dispatchers.IO) {
        val command = "setprop service.adb.tcp.port -1 && stop adbd && start adbd"
        val result = ShellUtils.execRoot(command)
        if (!result.isSuccess) {
            return@withContext false
        }

        // 轮询等待端口关闭，最多等待 2 秒 (4 * 500ms)
        for (i in 0 until 4) {
            delay(500)
            if (!isPortListening(DEFAULT_PORT)) {
                return@withContext true
            }
        }
        !isPortListening(DEFAULT_PORT)
    }
}
