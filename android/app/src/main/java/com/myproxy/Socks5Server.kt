package com.myproxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// SOCKS5-сервер: CONNECT (TCP) и UDP ASSOCIATE (UDP), без авторизации
class Socks5Server(private val port: Int) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    val bytesDown = AtomicLong(0)
    val bytesUp = AtomicLong(0)
    val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)

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
        activeConnections.incrementAndGet()
        try {
            client.soTimeout = 30000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            if (cin.read() != 5) return
            val n = cin.read()
            if (n < 0) return
            readFully(cin, ByteArray(n))
            cout.write(byteArrayOf(5, 0))
            cout.flush()

            val head = ByteArray(4)
            readFully(cin, head)
            if (head[0].toInt() != 5) return
            val cmd = head[1].toInt()

            val host: String = when (head[3].toInt()) {
                1 -> {
                    val b = ByteArray(4); readFully(cin, b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                3 -> {
                    val len = cin.read()
                    if (len < 0) return
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

            when (cmd) {
                1 -> {
                    val r = Socket()
                    remote = r
                    try {
                        r.connect(InetSocketAddress(host, dstPort), 10000)
                    } catch (e: Exception) {
                        cout.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                        cout.flush()
                        return
                    }
                    cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    cout.flush()

                    client.soTimeout = 0
                    val t = Thread { pipe(cin, r.getOutputStream(), bytesUp) }
                    t.start()
                    pipe(r.getInputStream(), cout, bytesDown)
                    t.join(1000)
                }
                3 -> handleUdp(client, cin, cout)
                else -> {
                    cout.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                    cout.flush()
                }
            }
                } catch (_: Exception) {
        } finally {
            activeConnections.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
    }
    private fun handleUdp(client: Socket, cin: InputStream, cout: OutputStream) {
        val udp = DatagramSocket(0)
        try {
            client.soTimeout = 0

            var localIp = client.localAddress.address
            if (localIp.size != 4) localIp = byteArrayOf(192.toByte(), 168.toByte(), 49, 1)
            val bindPort = udp.localPort
            val reply = ByteArray(10)
            reply[0] = 5; reply[1] = 0; reply[2] = 0; reply[3] = 1
            System.arraycopy(localIp, 0, reply, 4, 4)
            reply[8] = (bindPort shr 8).toByte()
            reply[9] = bindPort.toByte()
            cout.write(reply)
            cout.flush()

            val watcher = Thread {
                try { while (cin.read() >= 0) { } } catch (_: Exception) {}
                udp.close()
            }
            watcher.isDaemon = true
            watcher.start()

            val clientIp = client.inetAddress
            var clientPort = -1
            val buf = ByteArray(65535)

            while (!udp.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                udp.receive(p)
                val data = p.data
                val off = p.offset
                val len = p.length

                val fromClient = p.address == clientIp && (clientPort == -1 || p.port == clientPort)

                if (fromClient) {
                    clientPort = p.port
                    if (len < 10 || data[off + 2].toInt() != 0) continue
                    var pos = off + 4
                    val dest: InetAddress? = when (data[off + 3].toInt()) {
                        1 -> {
                            val b = data.copyOfRange(pos, pos + 4); pos += 4
                            InetAddress.getByAddress(b)
                        }
                        4 -> {
                            if (len < 22) continue
                            val b = data.copyOfRange(pos, pos + 16); pos += 16
                            InetAddress.getByAddress(b)
                        }
                        3 -> {
                            val l = data[pos].toInt() and 0xFF
                            val name = String(data, pos + 1, l)
                            pos += 1 + l
                            val dp = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                            pos += 2
                            val payload = data.copyOfRange(pos, off + len)
                            pool.execute {
                                try {
                                    val addr = InetAddress.getByName(name)
                                    udp.send(DatagramPacket(payload, payload.size, addr, dp))
                                    bytesUp.addAndGet(payload.size.toLong())
                                } catch (_: Exception) {}
                            }
                            null
                        }
                        else -> null
                    }
                    if (dest != null) {
                        val dp = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                        pos += 2
                        val payload = data.copyOfRange(pos, off + len)
                        try {
                            udp.send(DatagramPacket(payload, payload.size, dest, dp))
                            bytesUp.addAndGet(payload.size.toLong())
                        } catch (_: Exception) {}
                    }
                } else if (clientPort != -1) {
                    val addr = p.address.address
                    val isV6 = p.address is Inet6Address
                    val hdrLen = if (isV6) 22 else 10
                    val out = ByteArray(hdrLen + len)
                    out[3] = if (isV6) 4 else 1
                    System.arraycopy(addr, 0, out, 4, addr.size)
                    out[hdrLen - 2] = (p.port shr 8).toByte()
                    out[hdrLen - 1] = p.port.toByte()
                    System.arraycopy(data, off, out, hdrLen, len)
                    try {
                        udp.send(DatagramPacket(out, out.size, clientIp, clientPort))
                        bytesDown.addAndGet(len.toLong())
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            udp.close()
        }
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = i.read(b, off, b.size - off)
            if (r < 0) throw IOException("eof")
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
