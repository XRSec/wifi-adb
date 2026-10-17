package com.xrsec.wifiadb.core

import dadb.Dadb
import java.nio.charset.StandardCharsets

/**
 * Restarts adbd listening on TCP on the specified [port] (default 5555).
 * Issues the native ADB transport command `tcpip:<port>`.
 * Does NOT require root privileges.
 */
fun Dadb.tcpip(port: Int = 5555): String {
    open("tcpip:$port").use { stream ->
        return stream.source.readString(StandardCharsets.UTF_8)
    }
}
