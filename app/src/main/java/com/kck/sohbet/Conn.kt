package com.kck.sohbet

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/** Sunucuya açık bağlantı: düz TCP (mIRC gibi) ya da WebSocket (web sohbetlerinin kullandığı). */
abstract class Conn {
    abstract val label: String
    abstract fun write(b: ByteArray)
    abstract fun close()

    companion object {
        /**
         * Bağlantıyı açar. Başarısız olursa istisna fırlatır.
         * onData: gelen ham baytlar, onClosed: bağlantı koptuğunda (sebep).
         */
        fun open(
            host: String, port: Int, ssl: Boolean, ws: Boolean, timeoutMs: Int,
            onData: (ByteArray, Int) -> Unit, onClosed: (String) -> Unit
        ): Conn = if (ws) WsConn.open(host, port, ssl, timeoutMs, onData, onClosed)
        else TcpConn.open(host, port, ssl, timeoutMs, onData, onClosed)
    }
}

/** Gelen baytları satırlara böler. */
class LineSplitter(private val onLine: (ByteArray, Int) -> Unit) {
    private val buf = ByteArrayOutputStream(512)
    @Synchronized
    fun feed(b: ByteArray, len: Int) {
        for (i in 0 until len) {
            val c = b[i]
            if (c == '\n'.code.toByte()) {
                val bytes = buf.toByteArray()
                buf.reset()
                var n = bytes.size
                if (n > 0 && bytes[n - 1] == '\r'.code.toByte()) n--
                if (n > 0) onLine(bytes, n)
            } else buf.write(c.toInt())
        }
    }
}

class TcpConn private constructor(private val s: Socket, override val label: String) : Conn() {
    private val out: OutputStream = s.getOutputStream()

    override fun write(b: ByteArray) {
        synchronized(out) { out.write(b); out.flush() }
    }

    override fun close() { try { s.close() } catch (_: Exception) { } }

    companion object {
        fun open(
            host: String, port: Int, ssl: Boolean, timeoutMs: Int,
            onData: (ByteArray, Int) -> Unit, onClosed: (String) -> Unit
        ): Conn {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            val s: Socket = if (ssl)
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).also {
                    it.soTimeout = timeoutMs
                    (it as javax.net.ssl.SSLSocket).startHandshake()
                    it.soTimeout = 0
                }
            else raw
            s.keepAlive = true
            val c = TcpConn(s, "$host:$port" + if (ssl) " (SSL)" else "")
            Thread {
                var reason = "Sunucu bağlantıyı kapattı."
                try {
                    val input = s.getInputStream()
                    val b = ByteArray(4096)
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        if (n > 0) onData(b, n)
                    }
                } catch (e: IOException) {
                    reason = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
                }
                onClosed(reason)
            }.start()
            return c
        }
    }
}

class WsConn private constructor(override val label: String) : Conn() {
    @Volatile private var ws: WebSocket? = null
    @Volatile private var base64 = false

    override fun write(b: ByteArray) {
        val w = ws ?: throw IOException("bağlı değil")
        val ok = if (base64) w.send(Base64.encodeToString(b, Base64.NO_WRAP)) else w.send(b.toByteString())
        if (!ok) throw IOException("gönderilemedi")
    }

    override fun close() { try { ws?.close(1000, null) } catch (_: Exception) { } }

    companion object {
        private val client by lazy {
            OkHttpClient.Builder()
                .pingInterval(60, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        fun open(
            host: String, port: Int, ssl: Boolean, timeoutMs: Int,
            onData: (ByteArray, Int) -> Unit, onClosed: (String) -> Unit
        ): Conn {
            val scheme = if (ssl) "wss" else "ws"
            val c = WsConn("$scheme://$host:$port")
            val opened = CountDownLatch(1)
            var failure: Throwable? = null
            var failResponse: Int = 0
            val isOpenF = java.util.concurrent.atomic.AtomicBoolean(false)
            val origin = (if (ssl) "https://" else "http://") + "$host:$port"
            val req = Request.Builder()
                .url((if (ssl) "https://" else "http://") + "$host:$port/")
                .header("Sec-WebSocket-Protocol", "binary, base64")
                .header("Origin", origin)
                .build()
            c.ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    c.base64 = response.header("Sec-WebSocket-Protocol")?.trim()?.equals("base64", true) == true
                    isOpenF.set(true)
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray(); onData(b, b.size)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val b = if (c.base64) try { Base64.decode(text, Base64.DEFAULT) } catch (_: Exception) { text.toByteArray() }
                    else text.toByteArray(Charsets.UTF_8)
                    // Bazı ağ geçitleri satır sonu eklemez
                    val withNl = if (b.isNotEmpty() && b.last() != '\n'.code.toByte()) b + '\n'.code.toByte() else b
                    onData(withNl, withNl.size)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isOpenF.get()) onClosed("Sunucu bağlantıyı kapattı.")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (isOpenF.get()) onClosed("Bağlantı koptu: ${t.message ?: t.javaClass.simpleName}")
                    else { failure = t; failResponse = response?.code ?: 0; opened.countDown() }
                }
            })
            if (!opened.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                c.ws?.cancel()
                throw java.net.SocketTimeoutException("WebSocket zaman aşımı")
            }
            if (!isOpenF.get()) {
                val f = failure
                if (failResponse != 0) throw IOException("WebSocket reddedildi (HTTP $failResponse)")
                throw (f as? Exception) ?: IOException(f?.message ?: "WebSocket açılamadı")
            }
            return c
        }
    }
}
