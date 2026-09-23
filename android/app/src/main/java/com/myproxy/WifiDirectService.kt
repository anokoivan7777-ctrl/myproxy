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
        private const val RECONNECT_COOLDOWN_MS = 5000L
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var socks: Socks5Server? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastDown = 0L
    private var lastUp = 0L

    @Volatile private var shouldBeRunning = false
    @Volatile private var recreating = false
    private var lastRecreateAttempt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shouldBeRunning = false
            shutdown()
            return START_NOT_STICKY
        }
        shouldBeRunning = true
        startForegroundNow()
        startAll()
        return START_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "RyVox", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("RyVox работает")
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

        val mgr = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val ch = mgr.initialize(this, Looper.getMainLooper(), null)
        if (ch == null) {
            ProxyState.log("Wi-Fi Direct недоступен на этом устройстве")
            ProxyState.status = "Ошибка Wi-Fi Direct"
            return
        }
        manager = mgr
        channel = ch

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createGroup(mgr, ch) }
            override fun onFailure(reason: Int) { createGroup(mgr, ch) }
        })
    }

    private fun createGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ProxyState.ssid)
            .setPassphrase(ProxyState.password)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .enablePersistentMode(false)
            .build()

        mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                ProxyState.running = true
                ProxyState.status = "Подключено. Ждём ПК"
                ProxyState.log("Группа создана: ${ProxyState.ssid} (2.4 ГГц)")
                ProxyState.log("Адрес телефона: 192.168.49.1:$PORT")
                recreating = false
                startStats(mgr, ch)
            }

            override fun onFailure(reason: Int) {
                ProxyState.log("Ошибка группы, код $reason (2 = занято, 0 = ошибка)")
                ProxyState.status = "Ошибка Wi-Fi Direct"
                recreating = false
            }
        })
    }

    private fun startStats(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        handler.post(object : Runnable {
            override fun run() {
                if (!shouldBeRunning) return

                val s = socks
                if (s != null) {
                    val d = s.bytesDown.get()
                    val u = s.bytesUp.get()
                    ProxyState.speedDown = "${(d - lastDown) / 1024} KB/s"
                    ProxyState.speedUp = "${(u - lastUp) / 1024} KB/s"
                    lastDown = d
                    lastUp = u
                }

                mgr.requestGroupInfo(ch) { g ->
                    if (g == null) {
                        ProxyState.clients = 0
                        maybeRecreate(mgr, ch, "группа исчезла")
                    } else {
                        ProxyState.clients = g.clientList?.size ?: 0
                    }
                }

                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun maybeRecreate(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, reason: String) {
        if (!shouldBeRunning || recreating) return
        val now = System.currentTimeMillis()
        if (now - lastRecreateAttempt < RECONNECT_COOLDOWN_MS) return
        lastRecreateAttempt = now
        recreating = true

        ProxyState.status = "Переподключение..."
        ProxyState.log("Обрыв Wi-Fi Direct ($reason), пересоздаю группу")

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createGroup(mgr, ch) }
            override fun onFailure(reason: Int) { createGroup(mgr, ch) }
        })
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        socks?.stop()
        socks = null
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            mgr.removeGroup(ch, null)
        }
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
        shouldBeRunning = false
        handler.removeCallbacksAndMessages(null)
        socks?.stop()
        super.onDestroy()
    }
}
