package com.myproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) ProxyState.log("Разрешения выданы. Нажмите «Подключить» ещё раз")
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
        if (ProxyState.running) {
            startService(
                Intent(this, WifiDirectService::class.java)
                    .setAction(WifiDirectService.ACTION_STOP)
            )
            return
        }
        if (ProxyState.mode == "wifi" && !isLocationEnabled()) {
            ProxyState.log("Включите геолокацию в шторке телефона")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val missing = requiredPermissions().any {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        if (ProxyState.mode == "wifi") requestBatteryExemption()
        startForegroundService(
            Intent(this, WifiDirectService::class.java)
                .setAction(WifiDirectService.ACTION_START)
        )
    }

    private fun savePassword(newPassword: String) {
        if (newPassword.length < 8) {
            ProxyState.log("Пароль должен быть не короче 8 символов")
            return
        }
        ProxyState.password = newPassword
        prefs.edit().putString("password", newPassword).apply()
        ProxyState.log("Пароль сохранён, применится при следующем подключении")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("myproxy", Context.MODE_PRIVATE)
        ProxyState.password = prefs.getString("password", ProxyState.password) ?: ProxyState.password

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen(
                    onConnect = { onConnectClicked() },
                    onModeChange = { newMode -> if (!ProxyState.running) ProxyState.mode = newMode },
                    onSavePassword = { savePassword(it) }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    onConnect: () -> Unit,
    onModeChange: (String) -> Unit,
    onSavePassword: (String) -> Unit
) {
    val green = Color(0xFF2E7D32)
    val red = Color(0xFFC62828)
    var passwordField by remember { mutableStateOf(ProxyState.password) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "RyVox",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
                Text(
                    "Поддержка",
                    fontSize = 13.sp,
                    color = Color(0xFF80CBC4),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RyVoxApp"))
                            context.startActivity(intent)
                        }
                )
            }
            Spacer(Modifier.height(16.dp))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ProxyState.running) red else green
                )
            ) {
                Text(if (ProxyState.running) "Отключить" else "Подключить", fontSize = 20.sp)
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onModeChange("wifi") },
                    enabled = !ProxyState.running,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ProxyState.mode == "wifi") Color(0xFFB39DDB) else Color(0xFF37474F)
                    )
                ) { Text("Wi-Fi Direct") }
                Button(
                    onClick = { onModeChange("usb") },
                    enabled = !ProxyState.running,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ProxyState.mode == "usb") Color(0xFFB39DDB) else Color(0xFF37474F)
                    )
                ) { Text("USB") }
            }

            Spacer(Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                if (ProxyState.mode == "wifi") {
                    Text("Сеть: ${ProxyState.ssid}")
                    Text("Пароль: ${ProxyState.password}")
                    Text("Клиентов: ${ProxyState.clients}")
                } else {
                    Text("Режим: USB (кабель + отладка по USB)")
                }
                Text("Загрузка: ${ProxyState.speedDown}   Отдача: ${ProxyState.speedUp}")
                Text("Всего: ↓ ${ProxyState.formatBytes(ProxyState.totalDown)}   ↑ ${ProxyState.formatBytes(ProxyState.totalUp)}")
            }

            Spacer(Modifier.height(16.dp))

            if (ProxyState.mode == "wifi" && !ProxyState.running) {
                Text("Сменить пароль сети (мин. 8 символов)", fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = passwordField,
                        onValueChange = { passwordField = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSavePassword(passwordField) }) { Text("Сохранить") }
                }
                Spacer(Modifier.height(16.dp))
            }

                        Text("Лог", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(ProxyState.logLines) { line -> Text(line, fontSize = 12.sp) }
            }
            } // закрытие вложенной Column с CenterHorizontally

            Text(
                "Версия 1.0-apk",
                fontSize = 12.sp,
                color = Color(0xFF888888),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
