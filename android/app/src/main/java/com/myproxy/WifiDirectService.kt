package com.myproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_EXIT = "exit"
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
        when (intent?.action) {
            ACTION_STOP -> {
                shouldBeRunning = false
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (shouldBeRunning) {
                    shouldBeRunning = false
                    shutdown()
                } else {
                    shouldBeRunning = true
                    startForegroundNow()
                    startAll()
                }
                return START_STICKY
            }
            ACTION_EXIT -> {
                shouldBeRunning = false
                try { shutdown() } catch (_: Exception) {}
                handler.postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 300)
                return START_NOT_STICKY
            }
            else -> {
                shouldBeRunning = true
                startForegroundNow()
                startAll()
                return START_STICKY
            }
        }
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(this, WifiDirectService::class.java).setAction(ACTION_TOGGLE)
        val togglePending = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitIntent = Intent(this, WifiDirectService::class.java).setAction(ACTION_EXIT)
        val exitPending = PendingIntent.getService(
            this, 2, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel = if (shouldBeRunning) "Отключить" else "Подключить"

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("RyVox")
            .setContentText(ProxyState.status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(0, toggleLabel, togglePending)
            .addAction(0, "Выход", exitPending)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification())
    }

    private fun startForegroundNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "RyVox", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun startAll() {
        ProxyState.status = "Запуск..."
        ProxyState.log("Запуск сервиса (${if (ProxyState.mode == "usb") "USB" else "Wi-Fi Direct"})")
        updateNotification()

        try {
            socks = Socks5Server(PORT).also { it.start() }
            ProxyState.log("SOCKS5 запущен на порту $PORT")
        } catch (e: Exception) {
            ProxyState.log("Ошибка SOCKS5: ${e.message}")
            ProxyState.status = "Ошибка"
            updateNotification()
            return
        }

        if (ProxyState.mode == "usb") {
            ProxyState.running = true
            ProxyState.status = "Подключено (USB). Ждём ПК"
            ProxyState.log("Подключите телефон к ПК кабелем и разрешите отладку по USB")
            updateNotification()
            startStatsUsb()
            return
        }

        val mgr = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val ch = mgr.initialize(this, Looper.getMainLooper(), null)
        if (ch == null) {
            ProxyState.log("Wi-Fi Direct недоступен на этом устройстве")
            ProxyState.status = "Ошибка Wi-Fi Direct"
            updateNotification()
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
                updateNotification()
                startStatsWifi(mgr, ch)
            }

            override fun onFailure(reason: Int) {
                ProxyState.log("Ошибка группы, код $reason (2 = занято, 0 = ошибка)")
                ProxyState.status = "Ошибка Wi-Fi Direct"
                recreating = false
                updateNotification()
            }
        })
    }

    private fun updateByteStats() {
        val s = socks ?: return
        val d = s.bytesDown.get()
        val u = s.bytesUp.get()
        val deltaDown = d - lastDown
        val deltaUp = u - lastUp
        ProxyState.speedDown = "${if (deltaDown > 0) deltaDown / 1024 else 0} KB/s"
        ProxyState.speedUp = "${if (deltaUp > 0) deltaUp / 1024 else 0} KB/s"
        if (deltaDown > 0) ProxyState.totalDown += deltaDown
        if (deltaUp > 0) ProxyState.totalUp += deltaUp
        lastDown = d
        lastUp = u
    }

    private fun startStatsWifi(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        handler.post(object : Runnable {
            override fun run() {
                if (!shouldBeRunning) return
                updateByteStats()
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

    private fun startStatsUsb() {
        handler.post(object : Runnable {
            override fun run() {
                if (!shouldBeRunning) return
                updateByteStats()
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
        updateNotification()

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
