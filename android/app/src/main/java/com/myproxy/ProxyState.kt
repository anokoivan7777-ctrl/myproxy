package com.myproxy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Общее состояние: сервис пишет сюда, экран читает
object ProxyState {
    var running by mutableStateOf(false)
    var status by mutableStateOf("Отключено")
    var ssid by mutableStateOf("DIRECT-MP-MyProxy")
    var password by mutableStateOf("proxy12345")
    var clients by mutableStateOf(0)
    var speedDown by mutableStateOf("0 KB/s")
    var speedUp by mutableStateOf("0 KB/s")
    val logLines = mutableStateListOf<String>()

    fun log(msg: String) {
        logLines.add(0, msg)
        if (logLines.size > 100) logLines.removeAt(logLines.size - 1)
    }
}
