package com.myproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

@SuppressLint("MissingPermission")
class WifiDirectService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val PORT = 1080
        private const val CHANNEL = "myproxy"
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var socks: Socks5Server? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastDown = 0L
    private var lastUp = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startForegroundNow()
        startAll()
        return START_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "MyProxy", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("MyProxy работает")
            .setContentText("Wi-Fi Direct, SOCKS5 порт $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun startAll() {
        ProxyState.status = "Запуск..."
        ProxyState.log("Запуск сервиса")

        try {
            socks = Socks5Server(PORT).also { it.start() }
            ProxyState.log("SOCKS5 запущен на порту $PORT")
        } catch (e: Exception) {
            ProxyState.log("Ошибка SOCKS5: ${e.message}")
            ProxyState.status = "Ошибка"
            return
        }

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager!!.initialize(this, Looper.getMainLooper(), null)

        // Сначала убираем старую группу, потом создаём свою
        manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createGroup() }
            override fun onFailure(reason: Int) { createGroup() }
        })
    }

    private fun createGroup() {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ProxyState.ssid)
            .setPassphrase(ProxyState.password)
            .enablePersistentMode(false)
            .build()

        manager!!.createGroup(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                ProxyState.running = true
                ProxyState.status = "Подключено. Ждём ПК"
                ProxyState.log("Группа создана: ${ProxyState.ssid}")
                ProxyState.log("Адрес телефона: 192.168.49.1:$PORT")
                startStats()
            }

            override fun onFailure(reason: Int) {
                ProxyState.log("Ошибка группы, код $reason (2 = занято, 0 = ошибка)")
                ProxyState.status = "Ошибка Wi-Fi Direct"
            }
        })
    }

    private fun startStats() {
        handler.post(object : Runnable {
            override fun run() {
                val s = socks ?: return
                val d = s.bytesDown.get()
                val u = s.bytesUp.get()
                ProxyState.speedDown = "${(d - lastDown) / 1024} KB/s"
                ProxyState.speedUp = "${(u - lastUp) / 1024} KB/s"
                lastDown = d
                lastUp = u
                manager?.requestGroupInfo(channel) { g ->
                    ProxyState.clients = g?.clientList?.size ?: 0
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        socks?.stop()
        socks = null
        manager?.removeGroup(channel, null)
        ProxyState.running = false
        ProxyState.status = "Отключено"
        ProxyState.clients = 0
        ProxyState.speedDown = "0 KB/s"
        ProxyState.speedUp = "0 KB/s"
        ProxyState.log("Остановлено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        socks?.stop()
        super.onDestroy()
    }
}
