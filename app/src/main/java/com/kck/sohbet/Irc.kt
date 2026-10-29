package com.kck.sohbet

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory

/** Basit IRC istemcisi. Tüm durum değişiklikleri ana iş parçacığında yapılır. */
object Irc {

    const val STATUS = "Sunucu"

    const val K_NORMAL = 0
    const val K_ACTION = 1
    const val K_INFO = 2
    const val K_SELF = 3
    const val K_ERROR = 4
    const val K_NOTICE = 5

    class Msg(val time: Long, val nick: String?, val text: String, val kind: Int)

    class Conv(var name: String) {
        val isChannel get() = isChannelName(name)
        val isStatus get() = name == STATUS
        val msgs = ArrayList<Msg>()
        val users = ArrayList<String>()
        var topic = ""
        var unread = 0
        var mention = false
        var joined = true
    }

    class ChanInfo(val name: String, val users: Int, val topic: String)

    fun interface Listener { fun onIrcChanged() }

    val convs = LinkedHashMap<String, Conv>()
    val channelList = ArrayList<ChanInfo>()
    var listing = false
    var listLoaded = false

    /** 0 = bağlı değil, 1 = bağlanıyor, 2 = bağlı */
    var state = 0
    var myNick = ""
    var lastError = ""
    var activeConv: String? = null

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val sendExec = Executors.newSingleThreadExecutor()
    private val namesBuf = HashMap<String, ArrayList<String>>()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var generation = 0
    private var wantedNick = ""
    private var nickServPass = ""
    private var notifyPending = false

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    fun isChannelName(s: String) = s.startsWith("#") || s.startsWith("&")

    fun key(s: String) = s.lowercase()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Değişiklikleri biriktirip saniyede birkaç kez ekrana bildir. */
    private fun changed() {
        if (notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            for (l in listeners.toList()) l.onIrcChanged()
        }, 120)
    }

    fun conv(name: String, create: Boolean = true): Conv? {
        val k = key(name)
        convs[k]?.let { return it }
        if (!create) return null
        val c = Conv(name)
        convs[k] = c
        return c
    }

    fun status(): Conv = conv(STATUS)!!

    private fun add(c: Conv, nick: String?, text: String, kind: Int) {
        c.msgs.add(Msg(System.currentTimeMillis(), nick, text, kind))
        if (c.msgs.size > 1500) c.msgs.subList(0, 300).clear()
        if (activeConv != key(c.name) && (kind == K_NORMAL || kind == K_ACTION || (kind == K_NOTICE && !c.isStatus))) {
            c.unread++
            if (!c.isChannel || (myNick.isNotEmpty() && text.contains(myNick, ignoreCase = true))) c.mention = true
        }
        changed()
    }

    // ---------------- Bağlantı ----------------

    fun connect(host: String, port: Int, ssl: Boolean, nick: String, pass: String) {
        disconnect(quiet = true)
        val gen = ++generation
        state = 1
        wantedNick = nick
        myNick = nick
        nickServPass = pass
        lastError = ""
        listLoaded = false
        channelList.clear()
        add(status(), null, "$host:$port adresine bağlanılıyor…", K_INFO)
        try {
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, IrcService::class.java))
        } catch (_: Exception) { }

        Thread {
            try {
                val s: Socket = if (ssl) {
                    val raw = Socket()
                    raw.connect(InetSocketAddress(host, port), 20000)
                    (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true)
                } else {
                    Socket().also { it.connect(InetSocketAddress(host, port), 20000) }
                }
                s.keepAlive = true
                socket = s
                out = s.getOutputStream()
                sendNow("NICK $nick")
                sendNow("USER ${safeIdent(nick)} 0 * :Kalbim Sohbet")
                val input = BufferedInputStream(s.getInputStream())
                val buf = ByteArrayOutputStream(512)
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == '\n'.code) {
                        val bytes = buf.toByteArray()
                        buf.reset()
                        var len = bytes.size
                        if (len > 0 && bytes[len - 1] == '\r'.code.toByte()) len--
                        if (len == 0) continue
                        val line = decode(bytes, len)
                        if (line.startsWith("PING")) sendNow("PONG" + line.substring(4))
                        main.post { if (gen == generation) handle(line) }
                    } else buf.write(b)
                }
                main.post { if (gen == generation) lost("Sunucu bağlantıyı kapattı.") }
            } catch (e: Exception) {
                val msg = when {
                    e is java.net.UnknownHostException -> "Sunucu bulunamadı. Adresi kontrol et."
                    e is java.net.SocketTimeoutException -> "Sunucu cevap vermedi (zaman aşımı)."
                    e is java.net.ConnectException -> "Bağlantı reddedildi. Port numarasını kontrol et."
                    e is javax.net.ssl.SSLException -> "SSL hatası. SSL ayarını kapatıp port 6667 dene."
                    else -> "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
                }
                main.post { if (gen == generation) lost(msg) }
            }
        }.start()
        changed()
    }

    private fun safeIdent(n: String): String {
        val s = n.filter { it.isLetterOrDigit() && it.code < 128 }.lowercase()
        return if (s.isEmpty()) "sohbet" else s.take(10)
    }

    private fun lost(reason: String) {
        if (state == 0) return
        state = 0
        lastError = reason
        closeSocket()
        for (c in convs.values) {
            if (c.isChannel) c.users.clear()
        }
        add(status(), null, reason, K_ERROR)
        stopService()
        changed()
    }

    fun disconnect(quiet: Boolean = false) {
        if (state == 0 && socket == null) return
        generation++
        val s = socket
        val o = out
        if (s != null) {
            sendExec.execute {
                try { o?.write("QUIT :Görüşürüz\r\n".toByteArray()); o?.flush() } catch (_: Exception) { }
                try { s.close() } catch (_: Exception) { }
            }
        }
        socket = null; out = null
        state = 0
        for (c in convs.values) if (c.isChannel) c.users.clear()
        if (!quiet) add(status(), null, "Bağlantı kesildi.", K_INFO)
        stopService()
        changed()
    }

    private fun closeSocket() {
        val s = socket
        socket = null; out = null
        if (s != null) sendExec.execute { try { s.close() } catch (_: Exception) { } }
    }

    private fun stopService() {
        try { appCtx.stopService(Intent(appCtx, IrcService::class.java)) } catch (_: Exception) { }
    }

    private fun sendNow(line: String) {
        try {
            out?.write((line.replace("\r", "").replace("\n", " ") + "\r\n").toByteArray(Charsets.UTF_8))
            out?.flush()
        } catch (_: Exception) { }
    }

    fun send(line: String) { sendExec.execute { sendNow(line) } }

    // ---------------- Kod çözme ----------------

    private val utf8: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private val turkish: Charset = try { Charset.forName("windows-1254") } catch (_: Exception) { Charsets.ISO_8859_1 }

    private fun decode(b: ByteArray, len: Int): String = try {
        synchronized(utf8) { utf8.reset(); utf8.decode(ByteBuffer.wrap(b, 0, len)).toString() }
    } catch (_: Exception) {
        String(b, 0, len, turkish)
    }

    private val fmt = Regex("\u0003(\\d{1,2}(,\\d{1,2})?)?|\u0004[0-9a-fA-F]{0,6}|[\u0002\u000F\u0016\u001D\u001F\u001E\u0011]")
    fun strip(s: String) = s.replace(fmt, "")

    // ---------------- Gelen satırlar ----------------

    private fun handle(raw: String) {
        var line = raw
        if (line.startsWith("@")) line = line.substringAfter(' ', "")
        var prefix = ""
        if (line.startsWith(":")) {
            prefix = line.substring(1).substringBefore(' ')
            line = line.substringAfter(' ', "")
        }
        val cmd = line.substringBefore(' ').uppercase()
        var rest = line.substringAfter(' ', "")
        val params = ArrayList<String>()
        while (rest.isNotEmpty()) {
            if (rest.startsWith(":")) { params.add(rest.substring(1)); break }
            val sp = rest.indexOf(' ')
            if (sp < 0) { params.add(rest); break }
            params.add(rest.substring(0, sp))
            rest = rest.substring(sp + 1).trimStart(' ')
        }
        val from = prefix.substringBefore('!')
        fun p(i: Int) = params.getOrElse(i) { "" }
        fun last() = params.lastOrNull() ?: ""

        when (cmd) {
            "PING" -> { /* okuma döngüsünde cevaplandı */ }
            "001" -> {
                state = 2
                myNick = p(0)
                add(status(), null, strip(last()), K_INFO)
                if (nickServPass.isNotBlank()) send("PRIVMSG NickServ :IDENTIFY $nickServPass")
                // Daha önce girilen odalara yeniden gir
                for (c in convs.values) if (c.isChannel && c.joined) send("JOIN ${c.name}")
                changed()
            }
            "433" -> {
                if (state != 2) {
                    val tried = p(1).ifEmpty { wantedNick }
                    val next = if (tried.length < 14) tried + "_" else wantedNick.take(10) + (100..999).random()
                    myNick = next
                    add(status(), null, "\"$tried\" rumuzu kullanımda, \"$next\" deneniyor.", K_ERROR)
                    send("NICK $next")
                } else add(activeOrStatus(), null, "\"${p(1)}\" rumuzu kullanımda.", K_ERROR)
            }
            "321" -> { channelList.clear(); listing = true; changed() }
            "322" -> {
                channelList.add(ChanInfo(p(1), p(2).toIntOrNull() ?: 0, strip(p(3)).replace(Regex("^\\[[^]]*]\\s*"), "")))
                if (channelList.size % 50 == 0) changed()
            }
            "323" -> { listing = false; listLoaded = true; changed() }
            "332" -> { conv(p(1))!!.topic = strip(last()); changed() }
            "333" -> { }
            "353" -> {
                val ch = p(2)
                namesBuf.getOrPut(key(ch)) { ArrayList() }.addAll(last().split(' ').filter { it.isNotBlank() })
            }
            "366" -> {
                val c = conv(p(1), false)
                val list = namesBuf.remove(key(p(1)))
                if (c != null && list != null) {
                    c.users.clear(); c.users.addAll(list.map { stripUserhost(it) }); sortUsers(c)
                    changed()
                }
            }
            "JOIN" -> {
                val ch = p(0)
                val c = conv(ch)!!
                if (from.equals(myNick, true)) {
                    c.joined = true
                    c.users.clear()
                    add(c, null, "$ch odasına girdin.", K_INFO)
                } else {
                    if (c.users.none { bare(it).equals(from, true) }) { c.users.add(from); sortUsers(c) }
                    add(c, null, "$from odaya girdi.", K_INFO)
                }
            }
            "PART" -> {
                val c = conv(p(0), false) ?: return
                if (from.equals(myNick, true)) {
                    c.joined = false; c.users.clear()
                    add(c, null, "Odadan çıktın.", K_INFO)
                } else {
                    c.users.removeAll { bare(it).equals(from, true) }
                    add(c, null, "$from odadan çıktı.", K_INFO)
                }
            }
            "KICK" -> {
                val c = conv(p(0), false) ?: return
                val who = p(1)
                c.users.removeAll { bare(it).equals(who, true) }
                if (who.equals(myNick, true)) {
                    c.joined = false; c.users.clear()
                    add(c, null, "$from seni odadan attı: ${strip(p(2))}", K_ERROR)
                } else add(c, null, "$who, $from tarafından atıldı.", K_INFO)
            }
            "QUIT" -> {
                for (c in convs.values) {
                    if (c.isChannel && c.users.removeAll { bare(it).equals(from, true) })
                        add(c, null, "$from çıkış yaptı.", K_INFO)
                }
            }
            "NICK" -> {
                val nn = p(0)
                if (from.equals(myNick, true)) {
                    myNick = nn
                    add(status(), null, "Rumuzun artık $nn.", K_INFO)
                }
                for (c in convs.values) {
                    if (!c.isChannel) continue
                    val i = c.users.indexOfFirst { bare(it).equals(from, true) }
                    if (i >= 0) {
                        val pre = prefixOf(c.users[i])
                        c.users[i] = pre + nn; sortUsers(c)
                        add(c, null, "$from artık $nn olarak biliniyor.", K_INFO)
                    }
                }
                val pm = convs.remove(key(from))
                if (pm != null && !pm.isChannel && !pm.isStatus) { pm.name = nn; convs[key(nn)] = pm }
                changed()
            }
            "MODE" -> {
                val t = p(0)
                if (isChannelName(t)) {
                    val c = conv(t, false)
                    if (c != null) {
                        add(c, null, "$from modu değiştirdi: ${params.drop(1).joinToString(" ")}", K_INFO)
                        if (params.size > 2) send("NAMES $t")
                    }
                }
            }
            "TOPIC" -> {
                val c = conv(p(0))!!
                c.topic = strip(p(1))
                add(c, null, "$from konuyu değiştirdi: ${c.topic}", K_INFO)
            }
            "PRIVMSG" -> {
                val target = p(0)
                var text = p(1)
                var kind = K_NORMAL
                if (text.startsWith("\u0001")) {
                    val ctcp = text.trim('\u0001')
                    val ccmd = ctcp.substringBefore(' ').uppercase()
                    when (ccmd) {
                        "ACTION" -> { kind = K_ACTION; text = ctcp.substringAfter(' ', "") }
                        "VERSION" -> { send("NOTICE $from :\u0001VERSION Kalbim Sohbet (Android)\u0001"); return }
                        "PING" -> { send("NOTICE $from :\u0001$ctcp\u0001"); return }
                        "TIME" -> { send("NOTICE $from :\u0001TIME ${java.util.Date()}\u0001"); return }
                        else -> return
                    }
                }
                val c = if (isChannelName(target)) conv(target)!! else if (from.contains('.') && !prefix.contains('!')) status() else conv(from)!!
                add(c, from, strip(text), kind)
            }
            "NOTICE" -> {
                val text = strip(p(1)).trim('\u0001')
                val target = p(0)
                val c = when {
                    isChannelName(target) -> conv(target)!!
                    !prefix.contains('!') -> status()
                    convs.containsKey(key(from)) -> conv(from)!!
                    else -> activeOrStatus()
                }
                add(c, if (prefix.contains('!')) from else null, text, K_NOTICE)
            }
            "ERROR" -> add(status(), null, strip(last()), K_ERROR)
            "CAP", "PONG" -> { }
            else -> {
                val num = cmd.toIntOrNull()
                if (num != null) {
                    val text = strip(params.drop(1).joinToString(" "))
                    if (num in 400..599) {
                        val msg = when (num) {
                            401 -> "${p(1)}: böyle bir kişi ya da oda yok."
                            403 -> "${p(1)}: böyle bir oda yok."
                            404 -> "${p(1)}: bu odaya yazamazsın (izin yok)."
                            471 -> "${p(1)}: oda dolu."
                            473 -> "${p(1)}: oda davetle giriliyor."
                            474 -> "${p(1)}: bu odadan yasaklısın."
                            475 -> "${p(1)}: oda şifreli."
                            477 -> "${p(1)}: bu odaya girmek için kayıtlı rumuz gerekiyor."
                            else -> text
                        }
                        add(activeOrStatus(), null, msg, K_ERROR)
                        if (num == 263 || (listing && num in 400..499)) { listing = false; changed() }
                    } else if (num in listOf(2, 3, 4, 5, 250, 251, 252, 253, 254, 255, 265, 266, 372, 375, 376, 422)) {
                        add(status(), null, text.removePrefix(":"), K_INFO)
                    } else if (num == 263) {
                        listing = false
                        add(activeOrStatus(), null, "Sunucu meşgul, biraz sonra tekrar dene.", K_ERROR)
                    } else if (text.isNotBlank()) {
                        add(status(), null, text, K_INFO)
                    }
                }
            }
        }
    }

    private fun activeOrStatus(): Conv = activeConv?.let { convs[it] } ?: status()

    private fun stripUserhost(n: String) = n.substringBefore('!')

    private const val PREFIXES = "~&@%+"

    fun prefixOf(n: String): String = n.takeWhile { it in PREFIXES }
    fun bare(n: String): String = n.dropWhile { it in PREFIXES }

    private fun rank(n: String): Int {
        val c = n.firstOrNull() ?: return 9
        val i = PREFIXES.indexOf(c)
        return if (i < 0) 9 else i
    }

    private fun sortUsers(c: Conv) {
        c.users.sortWith(compareBy<String> { rank(it) }.thenBy { bare(it).lowercase() })
    }

    // ---------------- Kullanıcı işlemleri ----------------

    fun requestList() {
        if (state != 2) return
        listing = true
        channelList.clear()
        send("LIST")
        changed()
    }

    fun join(ch: String) {
        val name = if (isChannelName(ch)) ch else "#$ch"
        conv(name)!!.joined = true
        send("JOIN $name")
        changed()
    }

    fun part(name: String) {
        val c = conv(name, false) ?: return
        if (c.isChannel && c.joined && state == 2) send("PART $name :Görüşürüz")
        convs.remove(key(name))
        changed()
    }

    fun closeConv(name: String) {
        val c = conv(name, false) ?: return
        if (c.isStatus) return
        if (c.isChannel) part(name) else { convs.remove(key(name)); changed() }
    }

    fun openPrivate(nick: String): Conv {
        val n = bare(nick)
        return conv(n)!!
    }

    /** Kullanıcının yazdığı metni gönderir. "/" ile başlayan komutları da anlar. */
    fun say(target: String, input: String) {
        val text = input.trimEnd()
        if (text.isEmpty()) return
        val c = conv(target)!!
        if (text.startsWith("/") && !text.startsWith("//")) {
            val cmd = text.substring(1).substringBefore(' ').lowercase()
            val arg = text.substringAfter(' ', "").trim()
            when (cmd) {
                "join", "j", "gir" -> if (arg.isNotEmpty()) join(arg.substringBefore(' '))
                "part", "leave", "cik", "çık" -> part(if (arg.isNotEmpty() && isChannelName(arg)) arg.substringBefore(' ') else target)
                "msg", "query", "q" -> {
                    val who = arg.substringBefore(' ')
                    val m = arg.substringAfter(' ', "")
                    if (who.isNotEmpty()) {
                        val pc = conv(who)!!
                        if (m.isNotEmpty()) { send("PRIVMSG $who :$m"); add(pc, myNick, m, K_SELF) }
                    }
                }
                "me" -> {
                    send("PRIVMSG $target :\u0001ACTION $arg\u0001")
                    add(c, myNick, arg, K_ACTION)
                }
                "nick" -> if (arg.isNotEmpty()) send("NICK ${arg.substringBefore(' ')}")
                "quit" -> disconnect()
                "topic" -> if (c.isChannel) send(if (arg.isEmpty()) "TOPIC $target" else "TOPIC $target :$arg")
                "list" -> requestList()
                "raw", "quote" -> if (arg.isNotEmpty()) send(arg)
                else -> send(text.substring(1))
            }
            return
        }
        val body = if (text.startsWith("//")) text.substring(1) else text
        if (c.isStatus) { send(body); return }
        // Uzun mesajları parçala
        for (chunk in body.chunked(400)) {
            send("PRIVMSG ${c.name} :$chunk")
            add(c, myNick, chunk, K_SELF)
        }
    }
}
