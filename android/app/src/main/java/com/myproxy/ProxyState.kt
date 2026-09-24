package com.myproxy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ProxyState {
    var running by mutableStateOf(false)
    var status by mutableStateOf("Отключено")
    var mode by mutableStateOf("wifi") // "wifi" или "usb"
    var ssid by mutableStateOf("DIRECT-RV-RyVox")
    var password by mutableStateOf("proxy12345")
    var clients by mutableStateOf(0)
    var speedDown by mutableStateOf("0 KB/s")
    var speedUp by mutableStateOf("0 KB/s")
    var totalDown by mutableStateOf(0L)
    var totalUp by mutableStateOf(0L)
    val logLines = mutableStateListOf<String>()

    @Synchronized
    fun log(msg: String) {
        logLines.add(0, msg)
        while (logLines.size > 30) logLines.removeAt(logLines.size - 1)
    }

    fun formatBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        val kb = b / 1024.0
        if (kb < 1024) return String.format("%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f МБ", mb)
        val gb = mb / 1024.0
        return String.format("%.2f ГБ", gb)
    }
}
