package com.myproxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Простой SOCKS5-сервер: только CONNECT (TCP), без авторизации
class Socks5Server(private val port: Int) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    val bytesDown = AtomicLong(0)
    val bytesUp = AtomicLong(0)

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        pool.execute {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 30000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // Приветствие: VER, NMETHODS, METHODS
            if (cin.read() != 5) return
            val n = cin.read()
            if (n < 0) return
            cin.skip(n.toLong())
            cout.write(byteArrayOf(5, 0)) // без авторизации
            cout.flush()

            // Запрос: VER, CMD, RSV, ATYP
            val head = ByteArray(4)
            readFully(cin, head)
            if (head[0].toInt() != 5 || head[1].toInt() != 1) {
                cout.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            val host: String = when (head[3].toInt()) {
                1 -> {
                    val b = ByteArray(4); readFully(cin, b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                3 -> {
                    val len = cin.read()
                    val b = ByteArray(len); readFully(cin, b)
                    String(b)
                }
                4 -> {
                    val b = ByteArray(16); readFully(cin, b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                else -> return
            }
            val pb = ByteArray(2); readFully(cin, pb)
            val dstPort = ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)

            try {
                remote = Socket()
                remote.connect(InetSocketAddress(host, dstPort), 10000)
            } catch (e: Exception) {
                cout.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                cout.flush()
                return
            }
            cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            cout.flush()

            client.soTimeout = 0
            val r = remote
            val t = Thread { pipe(cin, r.getOutputStream(), bytesUp) }
            t.start()
            pipe(r.getInputStream(), cout, bytesDown)
            t.join(1000)
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = i.read(b, off, b.size - off)
            if (r < 0) throw java.io.IOException("eof")
            off += r
        }
    }

    private fun pipe(i: InputStream, o: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val r = i.read(buf)
                if (r < 0) break
                o.write(buf, 0, r)
                o.flush()
                counter.addAndGet(r.toLong())
            }
        } catch (_: Exception) {
        }
    }
}
