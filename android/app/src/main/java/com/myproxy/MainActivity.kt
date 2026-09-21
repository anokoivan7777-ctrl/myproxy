package com.myproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) ProxyState.log("Разрешения выданы")
            else ProxyState.log("Не выданы: " + denied.joinToString { it.substringAfterLast('.') })
        }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isLocationEnabled
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val i = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        }
    }

    private fun onConnectClicked() {
        if (!isLocationEnabled()) {
            ProxyState.log("Включите геолокацию в шторке телефона")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        permissionLauncher.launch(requiredPermissions())
        requestBatteryExemption()
        // Шаг 3: здесь будет запуск WifiDirectService
        ProxyState.log("Сервис будет добавлен в шаге 3")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen(onConnect = { onConnectClicked() })
            }
        }
    }
}

@Composable
fun MainScreen(onConnect: () -> Unit) {
    val green = Color(0xFF2E7D32)
    val red = Color(0xFFC62828)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("MyProxy", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // Статус
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (ProxyState.running) green else Color(0xFF37474F),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(ProxyState.status, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))

            // Кнопка Connect / Disconnect
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ProxyState.running) red else green
                )
            ) {
                Text(
                    if (ProxyState.running) "Отключить" else "Подключить",
                    fontSize = 20.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // Режим
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Wi-Fi Direct") }
                OutlinedButton(onClick = {}, enabled = false) { Text("USB") }
            }

            Spacer(Modifier.height(16.dp))

            // Данные сети
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Сеть: ${ProxyState.ssid}")
                Text("Пароль: ${ProxyState.password}")
                Text("Клиентов: ${ProxyState.clients}")
                Text("Загрузка: ${ProxyState.speedDown}   Отдача: ${ProxyState.speedUp}")
            }

            Spacer(Modifier.height(16.dp))
            Text("Лог", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(ProxyState.logLines) { line ->
                    Text(line, fontSize = 12.sp)
                }
            }
        }
    }
}
