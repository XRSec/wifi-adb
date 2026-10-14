package com.xrsec.wifiadb.core

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

object ShellUtils {

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean
            get() = exitCode == 0
    }

    private const val DEFAULT_TIMEOUT_MS = 10000L

    /**
     * 检查当前环境是否具备 Root 权限。
     */
    fun isRootAvailable(): Boolean {
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )
        val hasSuBinary = suPaths.any { File(it).exists() } || canExecuteSu()
        if (!hasSuBinary) {
            return false
        }
        val result = execRoot("id")
        return result.isSuccess && result.stdout.contains("uid=0")
    }

    private fun canExecuteSu(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exitCode = waitForProcessWithTimeout(process, 2000L)
            exitCode == 0
        } catch (_: Throwable) {
            false
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 以普通 Shell 权限执行命令。
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CommandResult {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            readProcessOutput(process, timeoutMs)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Unknown execution error")
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 以 Root (su) 权限执行命令。
     */
    fun execRoot(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CommandResult {
        var process: Process? = null
        var outputStream: DataOutputStream? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            outputStream = DataOutputStream(process.outputStream)
            outputStream.write(command.toByteArray(Charsets.UTF_8))
            outputStream.writeBytes("\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()

            readProcessOutput(process, timeoutMs)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Failed to execute root command")
        } finally {
            try {
                outputStream?.close()
            } catch (_: Throwable) {}
            try {
                process?.destroy()
            } catch (_: Throwable) {}
        }
    }

    private fun readProcessOutput(process: Process, timeoutMs: Long): CommandResult {
        val stdoutSb = StringBuilder()
        val stderrSb = StringBuilder()

        val stdoutThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stdoutSb.append(line).append("\n")
                    }
                }
            } catch (_: Throwable) {}
        }

        val stderrThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrSb.append(line).append("\n")
                    }
                }
            } catch (_: Throwable) {}
        }

        stdoutThread.start()
        stderrThread.start()

        val exitCode = waitForProcessWithTimeout(process, timeoutMs)

        try {
            stdoutThread.join(1000)
            stderrThread.join(1000)
        } catch (_: Throwable) {}

        return CommandResult(
            exitCode = exitCode,
            stdout = stdoutSb.toString().trim(),
            stderr = stderrSb.toString().trim()
        )
    }

    /**
     * 兼容 Android 4.4 (API 19) 的带超时机制 Process 等待函数。
     */
    private fun waitForProcessWithTimeout(process: Process, timeoutMs: Long): Int {
        val isFinished = AtomicBoolean(false)
        val exitVal = IntArray(1) { -1 }

        val waitThread = Thread {
            try {
                exitVal[0] = process.waitFor()
                isFinished.set(true)
            } catch (_: InterruptedException) {
                // 等待被外部中断
            }
        }
        waitThread.start()

        try {
            waitThread.join(timeoutMs)
        } catch (_: InterruptedException) {}

        if (!isFinished.get()) {
            waitThread.interrupt()
            try {
                process.destroy()
            } catch (_: Throwable) {}
            return -1
        }
        return exitVal[0]
    }
}
