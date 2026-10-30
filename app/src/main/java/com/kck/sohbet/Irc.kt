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
    /** Sunucu IP adresini gizlediyse (+x / maske) */
    var ipHidden = false
    var shownHost = ""
    var activeConv: String? = null

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val sendExec = Executors.newSingleThreadExecutor()
    private val namesBuf = HashMap<String, ArrayList<String>>()

    @Volatile private var conn: Conn? = null
    @Volatile private var generation = 0
    private var wantedNick = ""
    private var nickServPass = ""
    private var notifyPending = false

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    // ---------------- Otomatik özel mesaj ----------------

    private val prefs get() = appCtx.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    /** Odaya girene gönderilen açılış mesajı. */
    fun openingMsg(): String {
        val p = prefs
        if (!p.contains("otoMesaj1") && p.contains("otoMesaj")) return p.getString("otoMesaj", "") ?: ""
        return p.getString("otoMesaj1", "") ?: ""
    }

    fun setOpeningMsg(text: String) {
        prefs.edit().putString("otoMesaj1", text.trim()).remove("otoMesaj").apply()
    }

    /** Cevap havuzu: her satır bir cevap. Kişi yazınca buradan rastgele biri seçilir. */
    fun replyPool(): String {
        val p = prefs
        if (p.contains("otoHavuz")) return p.getString("otoHavuz", "") ?: ""
        // Eski sürümdeki 2-5. mesajları havuza taşı
        val old = ArrayList<String>()
        if (!p.contains("otoMesaj2") && p.contains("otoCevap")) old.add(p.getString("otoCevap", "") ?: "")
        for (i in 2..5) old.add(p.getString("otoMesaj$i", "") ?: "")
        return old.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun setReplyPool(text: String) {
        val e = prefs.edit().putString("otoHavuz", text.trim())
        for (i in 2..5) e.remove("otoMesaj$i")
        e.remove("otoCevap").apply()
    }

    /** Açıksa: bana özelden yazan herkese havuzdan cevap ver (oda mesajını almamış olsa bile). */
    fun replyAll(): Boolean = prefs.getBoolean("otoHerkes", false)
    fun setReplyAll(on: Boolean) { prefs.edit().putBoolean("otoHerkes", on).apply() }

    /** Soru-cevap: mesajda soru kelimesi geçerse o cevap gider. */
    const val QA_COUNT = 10

    fun qaQuestion(i: Int): String = prefs.getString("soru${i + 1}", "") ?: ""
    fun qaAnswer(i: Int): String = prefs.getString("cevap${i + 1}", "") ?: ""

    fun setQa(questions: List<String>, answers: List<String>) {
        val e = prefs.edit()
        for (i in 0 until QA_COUNT) {
            e.putString("soru${i + 1}", questions.getOrElse(i) { "" }.trim())
            e.putString("cevap${i + 1}", answers.getOrElse(i) { "" }.trim())
        }
        e.apply()
    }

    /** Türkçe harfleri sadeleştir: "Yaşın kaç" ile "yasin kac" aynı sayılır. */
    private fun norm(s: String): String = s.lowercase(java.util.Locale("tr"))
        .replace('ş', 's').replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace("\u0307", "")

    /** Mesaja uyan soru-cevap (sırası, cevabı). Soru kutusunda virgülle birden fazla kelime yazılabilir. */
    private fun matchQa(text: String, skip: Set<Int>): Pair<Int, String>? {
        val t = norm(text)
        for (i in 0 until QA_COUNT) {
            val ans = qaAnswer(i).trim()
            if (ans.isEmpty() || i in skip) continue
            val words = qaQuestion(i).split(',').map { norm(it).trim() }.filter { it.isNotEmpty() }
            if (words.any { t.contains(it) }) return Pair(i, ans)
        }
        return null
    }

    private fun poolList(): List<String> = replyPool().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Cevap gelmezse gönderilecek 2 takip mesajı (30 sn arayla). */
    const val FOLLOW_COUNT = 2
    const val FOLLOW_DELAY = 30_000L

    fun followMsg(i: Int): String = prefs.getString("otoTakip${i + 1}", "") ?: ""

    fun setFollowMsgs(list: List<String>) {
        val e = prefs.edit()
        for (i in 0 until FOLLOW_COUNT) e.putString("otoTakip${i + 1}", list.getOrElse(i) { "" }.trim())
        e.apply()
    }

    private fun followList(): List<String> = (0 until FOLLOW_COUNT).map { followMsg(it).trim() }.filter { it.isNotEmpty() }


    // ---------------- Engelleme ----------------

    fun blocked(): Set<String> = prefs.getStringSet("engelliler", emptySet())!!

    fun isBlocked(nick: String) = blocked().contains(key(bare(nick)))

    fun setBlocked(nick: String, on: Boolean) {
        val set = HashSet(blocked())
        if (on) set.add(key(bare(nick))) else set.remove(key(bare(nick)))
        prefs.edit().putStringSet("engelliler", set).apply()
        if (on) {
            awaitingReply.remove(key(bare(nick)))
            usedPool.remove(key(bare(nick)))
            autoQueue.removeAll { key(it.nick) == key(bare(nick)) }
            noReply.remove(key(bare(nick)))
            if (state == 2) send("SILENCE +${bare(nick)}!*@*")
        } else if (state == 2) send("SILENCE -${bare(nick)}!*@*")
        changed()
    }

    fun autoOn(ch: String): Boolean =
        prefs.getStringSet("otoOdalar", emptySet())!!.contains(key(ch))

    fun setAutoOn(ch: String, on: Boolean) {
        val set = HashSet(prefs.getStringSet("otoOdalar", emptySet())!!)
        if (on) set.add(key(ch)) else set.remove(key(ch))
        prefs.edit().putStringSet("otoOdalar", set).apply()
    }

    /** Kime ne zaman otomatik mesaj gitti (aynı kişiye günde bir kez). */
    private val autoSent = HashMap<String, Long>()
    /** Açılış mesajı gönderilmiş, cevap yazarsa havuzdan cevap alacak kişiler. */
    private val awaitingReply = HashSet<String>()
    /** Kişiye havuzdan hangi cevaplar gitti (aynı cevap tekrar gitmesin) ve ne zaman başladı. */
    private class PoolUse(val start: Long) { val used = HashSet<String>(); val qaUsed = HashSet<Int>() }
    private val usedPool = HashMap<String, PoolUse>()
    /** type: 0 = açılış, 1 = takip (cevap gelmedi), 2 = cevap sonrası */
    private class AutoItem(val nick: String, val text: String, val type: Int)
    private val autoQueue = ArrayDeque<AutoItem>()
    /** Henüz cevap vermeyen kişiler → gönderilecek sonraki takip mesajının sırası */
    private val noReply = HashMap<String, Int>()
    private var autoBusy = false
    private var lastAutoAt = 0L
    private const val AUTO_GAP = 5000L

    private fun isService(n: String) = n.endsWith("Serv", true) || n.contains('.')

    private fun queueAuto(nick: String, text: String, type: Int) {
        if (autoQueue.size >= 30) return
        autoQueue.addLast(AutoItem(nick, text, type))
        pumpAuto()
    }

    /** Sunucuya yüklenmemek için otomatik mesajları en az 5 saniye arayla gönder. */
    private fun pumpAuto() {
        if (autoBusy || autoQueue.isEmpty()) return
        autoBusy = true
        val wait = maxOf(1500L, lastAutoAt + AUTO_GAP - System.currentTimeMillis())
        main.postDelayed({
            autoBusy = false
            val item = autoQueue.removeFirstOrNull() ?: return@postDelayed
            val k = key(item.nick)
            val stillWaiting = item.type != 1 || noReply.containsKey(k)
            if (state == 2 && stillWaiting && !isBlocked(item.nick)) {
                send("PRIVMSG ${item.nick} :${item.text}")
                lastAutoAt = System.currentTimeMillis()
                val c = conv(item.nick, false)
                if (c != null) add(c, myNick, "🤖 " + item.text, K_SELF)
                // Açılış ya da takip mesajından sonra 30 sn içinde cevap gelmezse sıradaki takip mesajı
                if (item.type != 2 && noReply.containsKey(k)) {
                    val nick = item.nick
                    main.postDelayed({ followUp(nick) }, FOLLOW_DELAY)
                }
            }
            pumpAuto()
        }, wait)
    }

    private fun onOtherJoined(ch: String, nick: String) {
        val text = openingMsg().trim().ifEmpty { return }
        if (!autoOn(ch) || state != 2) return
        if (nick.equals(myNick, true) || isService(nick) || isBlocked(nick)) return
        val k = key(nick)
        val last = autoSent[k] ?: 0L
        if (System.currentTimeMillis() - last < 24 * 3600 * 1000L) return
        autoSent[k] = System.currentTimeMillis()
        awaitingReply.add(k)
        usedPool[k] = PoolUse(System.currentTimeMillis())
        if (followList().isNotEmpty()) noReply[k] = 0
        queueAuto(nick, text, 0)
    }

    /** Otomatik mesaj attığımız kişi cevap yazdıysa. */
    private fun followUp(nick: String) {
        val k = key(nick)
        val step = noReply[k] ?: return
        val list = followList()
        if (state != 2 || step >= list.size || isBlocked(nick)) { noReply.remove(k); return }
        noReply[k] = step + 1
        queueAuto(nick, list[step], 1)
    }

    private fun onPrivateFrom(nick: String, c: Conv, text: String) {
        val k = key(nick)
        // Cevap geldi: takip mesajlarını iptal et
        noReply.remove(k)
        autoQueue.removeAll { key(it.nick) == k && it.type == 1 }
        if (state != 2 || isService(nick) || isBlocked(nick) || nick.equals(myNick, true)) return
        val inFlow = k in awaitingReply
        if (!inFlow && !replyAll()) return
        // Açılış mesajı oda ekranından gittiği için özel sohbette görünsün
        val opening = openingMsg().trim()
        if (inFlow && c.msgs.isEmpty() && opening.isNotEmpty())
            c.msgs.add(Msg(System.currentTimeMillis(), myNick, "🤖 $opening", K_SELF))
        // Aynı cevaba birden fazla mesaj gitmesin: sıradaki mesaj gönderilene kadar bekle
        if (autoQueue.any { key(it.nick) == k }) return
        val now = System.currentTimeMillis()
        var use = usedPool[k]
        if (use == null || now - use.start > 24 * 3600 * 1000L) { use = PoolUse(now); usedPool[k] = use }
        // Önce soru-cevap: sorduğu soruya ayarlanan cevap (her soruya bir kez)
        val qa = matchQa(text, use.qaUsed)
        if (qa != null) {
            use.qaUsed.add(qa.first)
            queueAuto(nick, qa.second, 2)
            return
        }
        // Uyan soru yoksa havuzdan rastgele
        val left = poolList().filter { it !in use.used }
        if (left.isEmpty()) return
        val pick = left.random()
        use.used.add(pick)
        queueAuto(nick, pick, 2)
    }

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

    /** Bağlanmayı denenecek yollar: önce kullanıcının seçtiği, sonra yaygın portlar ve web bağlantısı. */
    private class Way(val port: Int, val ssl: Boolean, val ws: Boolean) {
        override fun toString() = (if (ws) (if (ssl) "web (wss) " else "web (ws) ") else "") + "port $port" + if (ssl && !ws) " SSL" else ""
    }

    private fun waysFor(port: Int, ssl: Boolean, ws: Boolean, auto: Boolean): List<Way> {
        val list = ArrayList<Way>()
        list.add(Way(port, ssl, ws))
        if (auto) {
            for (w in listOf(
                Way(6667, false, false), Way(6697, true, false),
                Way(5570, true, true), Way(5570, false, true), Way(29007, true, true),
                Way(7000, false, false), Way(6668, false, false), Way(6669, false, false)
            )) if (list.none { it.port == w.port && it.ssl == w.ssl && it.ws == w.ws }) list.add(w)
        }
        return list
    }

    private class Attempt {
        val gotData = java.util.concurrent.atomic.AtomicBoolean(false)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var accepted = false
        @Volatile var reason = ""
    }

    fun connect(host: String, port: Int, ssl: Boolean, nick: String, pass: String, ws: Boolean = false, auto: Boolean = true) {
        disconnect(quiet = true)
        val gen = ++generation
        state = 1
        wantedNick = nick
        myNick = nick
        nickServPass = pass
        lastError = ""
        ipHidden = false
        shownHost = ""
        listLoaded = false
        channelList.clear()
        add(status(), null, "$host adresine bağlanılıyor…", K_INFO)
        try {
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, IrcService::class.java))
        } catch (_: Exception) { }

        Thread {
            var lastErr: Exception? = null
            var silentClose = false
            var ok = false
            val ways = waysFor(port, ssl, ws, auto)
            for ((idx, w) in ways.withIndex()) {
                if (gen != generation) return@Thread
                if (idx > 0) main.post { if (gen == generation) add(status(), null, "Olmadı, $w deneniyor…", K_INFO) }
                val a = Attempt()
                val splitter = LineSplitter { bytes, len ->
                    val line = decode(bytes, len)
                    if (line.startsWith("PING")) sendNow("PONG" + line.substring(4))
                    main.post { if (gen == generation) handle(line) }
                }
                val c: Conn
                try {
                    c = Conn.open(host, w.port, w.ssl, w.ws, if (idx == 0) 15000 else 8000,
                        { b, n -> a.gotData.set(true); splitter.feed(b, n) },
                        { reason ->
                            a.reason = reason
                            a.closed.set(true)
                            if (a.accepted) main.post { if (gen == generation) lost(reason) }
                        })
                } catch (e: Exception) {
                    lastErr = e
                    // Sunucu adı yanlışsa diğer portları denemenin anlamı yok
                    if (e is java.net.UnknownHostException) break
                    continue
                }
                if (gen != generation) { c.close(); return@Thread }
                conn = c
                sendNow("NICK $nick")
                sendNow("USER ${safeIdent(nick)} 0 * :Kalbim Sohbet")
                // Bu port gerçekten sohbet sunucusu mu? Cevap gelmesini bekle (en fazla 12 sn)
                val until = System.currentTimeMillis() + 12000
                while (!a.gotData.get() && !a.closed.get() && System.currentTimeMillis() < until && gen == generation) {
                    Thread.sleep(100)
                }
                if (a.gotData.get() && !a.closed.get()) {
                    a.accepted = true
                    ok = true
                    main.post {
                        if (gen == generation) add(status(), null, "Bağlandı: $w", K_INFO)
                        // Çalışan yolu kaydet, bir dahaki sefere direkt onu kullan
                        prefs.edit().putInt("port", w.port).putBoolean("ssl", w.ssl).putBoolean("ws", w.ws).apply()
                    }
                    break
                }
                if (a.gotData.get() && a.closed.get()) {
                    // Sunucu cevap verip kapattı: sebebi sunucu mesajlarında yazar, başka port denemeye gerek yok
                    conn = null
                    main.post { if (gen == generation) lost(a.reason) }
                    return@Thread
                }
                silentClose = silentClose || a.closed.get()
                conn = null
                c.close()
            }
            if (!ok) {
                val e = lastErr
                val msg = when {
                    silentClose -> "Sunucu bağlantıyı kabul etti ama hiçbir şey göndermeden kapattı. Bu sunucu sadece kendi sitesinden girişe izin veriyor olabilir."
                    e is java.net.UnknownHostException -> "Sunucu bulunamadı. Adresi kontrol et."
                    e is java.net.SocketTimeoutException -> "Sunucu cevap vermedi (zaman aşımı)."
                    e is java.net.ConnectException -> "Bağlantı reddedildi. Denenen hiçbir port açık değil."
                    e is javax.net.ssl.SSLException -> "SSL hatası."
                    else -> "Bağlanılamadı: ${e?.message ?: "bilinmeyen hata"}"
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
        closeConn()
        for (c in convs.values) {
            if (c.isChannel) c.users.clear()
        }
        add(status(), null, reason, K_ERROR)
        stopService()
        changed()
    }

    fun disconnect(quiet: Boolean = false) {
        if (state == 0 && conn == null) return
        generation++
        val c = conn
        if (c != null) {
            sendExec.execute {
                try { c.write("QUIT :Görüşürüz\r\n".toByteArray()) } catch (_: Exception) { }
                c.close()
            }
        }
        conn = null
        state = 0
        for (c in convs.values) if (c.isChannel) c.users.clear()
        if (!quiet) add(status(), null, "Bağlantı kesildi.", K_INFO)
        stopService()
        changed()
    }

    private fun closeConn() {
        val c = conn
        conn = null
        if (c != null) sendExec.execute { c.close() }
    }

    private fun stopService() {
        try { appCtx.stopService(Intent(appCtx, IrcService::class.java)) } catch (_: Exception) { }
    }

    private fun sendNow(line: String) {
        try {
            conn?.write((line.replace("\r", "").replace("\n", " ") + "\r\n").toByteArray(Charsets.UTF_8))
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
                // IP adresini diğer kullanıcılardan gizle (sunucu maskesi)
                send("MODE $myNick +x")
                for (b in blocked()) send("SILENCE +$b!*@*")
                if (nickServPass.isNotBlank()) send("PRIVMSG NickServ :IDENTIFY $nickServPass")
                // Daha önce girilen odalara yeniden gir
                for (c in convs.values) if (c.isChannel && c.joined && key(c.name) in wantedChannels) send("JOIN ${c.name}")
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
                if (from.equals(myNick, true) && key(ch) !in wantedChannels) {
                    // Sunucu otomatik bir odaya soktu: kullanıcı kendisi seçmediği için hemen çık
                    send("PART $ch")
                    return
                }
                val c = conv(ch)!!
                if (from.equals(myNick, true)) {
                    c.joined = true
                    c.users.clear()
                    add(c, null, "$ch odasına girdin.", K_INFO)
                } else {
                    if (c.users.none { bare(it).equals(from, true) }) { c.users.add(from); sortUsers(c) }
                    add(c, from, "$from odaya girdi. (özel mesaj için dokun)", K_INFO)
                    onOtherJoined(ch, from)
                }
            }
            "PART" -> {
                val c = conv(p(0), false) ?: return
                if (from.equals(myNick, true)) {
                    c.joined = false; c.users.clear()
                    add(c, null, "Odadan çıktın.", K_INFO)
                } else {
                    c.users.removeAll { bare(it).equals(from, true) }
                    add(c, from, "$from odadan çıktı.", K_INFO)
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
                noReply.remove(key(from)); awaitingReply.remove(key(from)); usedPool.remove(key(from))
                autoQueue.removeAll { key(it.nick) == key(from) }
                for (c in convs.values) {
                    if (c.isChannel && c.users.removeAll { bare(it).equals(from, true) })
                        add(c, from, "$from çıkış yaptı.", K_INFO)
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
                if (t.equals(myNick, true)) {
                    val m = p(1)
                    val plus = m.substringBefore('-').contains('x') && m.startsWith("+")
                    val minus = m.startsWith("-") && m.contains('x')
                    if (plus && !ipHidden) { ipHidden = true; add(status(), null, "🔒 IP adresin gizlendi.", K_INFO) }
                    if (minus) { ipHidden = false; add(status(), null, "IP gizleme kapatıldı, tekrar açılıyor…", K_ERROR); send("MODE $myNick +x") }
                    changed()
                } else if (isChannelName(t)) {
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
                if (prefix.contains('!') && isBlocked(from)) return
                val c = if (isChannelName(target)) conv(target)!! else if (from.contains('.') && !prefix.contains('!')) status() else conv(from)!!
                if (!isChannelName(target) && !c.isStatus) onPrivateFrom(from, c, strip(text))
                add(c, from, strip(text), kind)
            }
            "NOTICE" -> {
                if (prefix.contains('!') && isBlocked(from)) return
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
            "396" -> {
                ipHidden = true
                shownHost = p(1)
                add(status(), null, "🔒 IP adresin gizlendi. Diğerleri sadece şunu görür: $shownHost", K_INFO)
            }
            "ERROR" -> add(status(), null, strip(last()), K_ERROR)
            "CAP", "PONG" -> { }
            else -> {
                val num = cmd.toIntOrNull()
                if (num != null) {
                    val text = strip(params.drop(1).joinToString(" "))
                    if ((num == 421 && p(1).equals("SILENCE", true)) || num == 511) return
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

    /** Kullanıcının kendisinin girmek istediği odalar. Başka odalara otomatik girilmez. */
    private val wantedChannels = HashSet<String>()

    fun join(ch: String) {
        val name = if (isChannelName(ch)) ch else "#$ch"
        wantedChannels.add(key(name))
        conv(name)!!.joined = true
        send("JOIN $name")
        changed()
    }

    fun part(name: String) {
        val c = conv(name, false) ?: return
        wantedChannels.remove(key(name))
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
