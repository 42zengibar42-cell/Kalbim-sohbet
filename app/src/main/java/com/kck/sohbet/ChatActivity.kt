package com.kck.sohbet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : Activity(), Irc.Listener {

    private lateinit var conv: Irc.Conv
    private lateinit var bar: TopBar
    private lateinit var list: ListView
    private lateinit var edit: EditText
    private var usersBtn: TextView? = null
    private val adapter = MsgAdapter()
    private var count = 0
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: Irc.STATUS
        conv = Irc.conv(name)!!
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        conv = Irc.conv(intent.getStringExtra("name") ?: Irc.STATUS)!!
        build()
        onResume()
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val title = when {
            conv.isStatus -> "Sunucu mesajları"
            conv.isChannel -> conv.name
            else -> conv.name
        }
        bar = TopBar(this, title) { finish() }
        usersBtn = null
        if (conv.isChannel) {
            bar.action(smallButton("🤖") { autoDialog() })
            usersBtn = smallButton("👥") { showUsers() }
            bar.action(usersBtn!!)
        }
        if (!conv.isChannel && !conv.isStatus) bar.action(smallButton("🚫") { confirmBlock(conv.name) })
        if (!conv.isStatus) bar.action(smallButton("✕") { confirmClose() })
        root.addView(bar)

        list = ListView(this).apply {
            divider = null; dividerHeight = 0
            isStackFromBottom = true
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
            adapter = this@ChatActivity.adapter
            setPadding(dp(8), dp(6), dp(8), dp(6))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> msgMenu(conv.msgs.getOrNull(pos)) }
            setOnItemLongClickListener { _, _, pos, _ -> msgLongPress(conv.msgs.getOrNull(pos)); true }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#22181F"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        edit = input(
            if (conv.isStatus) "Komut yaz (ör. /join #oda)" else "Mesaj yaz…", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        ).apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                    sendText(); true
                } else false
            }
        }
        bottom.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        val send = text("➤", 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(C.PRIMARY, 24)
            setOnClickListener { sendText() }
        }
        bottom.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        root.addView(bottom)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Irc.activeConv = Irc.key(conv.name)
        Irc.addListener(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (Irc.activeConv == Irc.key(conv.name)) Irc.activeConv = null
        Irc.removeListener(this)
    }

    override fun onIrcChanged() = refresh()

    private fun refresh() {
        // Sohbet kapatıldıysa (ör. başka ekrandan) bu ekranı kapat
        if (Irc.convs.values.none { it === conv }) {
            if (Irc.state == 0 || !conv.isStatus) { finish(); return }
        }
        conv.unread = 0
        conv.mention = false
        bar.titleView.text = when {
            conv.isStatus -> "Sunucu mesajları"
            else -> conv.name
        }
        bar.sub(
            when {
                Irc.state == 1 -> "Bağlanıyor…"
                Irc.state == 0 -> "Bağlantı yok"
                conv.isStatus -> Irc.myNick
                conv.isChannel && !conv.joined -> "Odada değilsin"
                conv.isChannel -> (if (Irc.autoOn(conv.name)) "🤖 Oto mesaj açık • " else "") + conv.topic.ifEmpty { "${conv.users.size} kişi" }
                else -> "Özel sohbet"
            }
        )
        usersBtn?.text = "👥 ${conv.users.size}"
        if (conv.msgs.size != count) {
            count = conv.msgs.size
            adapter.notifyDataSetChanged()
        }
    }

    private fun sendText() {
        val t = edit.text.toString()
        if (t.isBlank()) return
        if (Irc.state != 2) {
            edit.error = "Bağlı değilsin"
            return
        }
        if (conv.isChannel && !conv.joined && !t.startsWith("/")) Irc.join(conv.name)
        Irc.say(conv.name, t)
        edit.setText("")
        list.post { list.setSelection(adapter.count - 1) }
    }

    private fun confirmClose() {
        AlertDialog.Builder(this)
            .setMessage(if (conv.isChannel) "${conv.name} odasından çıkılsın mı?" else "Bu özel sohbet kapatılsın mı?")
            .setPositiveButton(if (conv.isChannel) "Çık" else "Kapat") { _, _ ->
                Irc.closeConv(conv.name); finish()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun autoDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val on = android.widget.CheckBox(this).apply {
            text = "Bu odaya girenlere otomatik özel mesaj gönder"
            isChecked = Irc.autoOn(conv.name)
        }
        box.addView(on)
        val ml = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        fun section(title: String, desc: String) {
            box.addView(text(title, 16f, C.ACCENT, true).apply { setPadding(0, dp(18), 0, 0) })
            box.addView(text(desc, 13f, C.DIM).apply { setPadding(0, dp(2), 0, 0) })
        }
        fun field(label: String, hint: String, value: String): android.widget.EditText {
            box.addView(text(label, 14f, C.TEXT).apply { setPadding(0, dp(10), 0, dp(4)) })
            val e = input(hint, value, ml)
            e.maxLines = 4
            box.addView(e)
            return e
        }

        section("Bölüm 1 • Açılış", "Odaya giren kişiye özelden gönderilir.")
        val first = field("1. mesaj", "ör. Merhaba, hoş geldin 💗", Irc.openingMsg())

        section("Bölüm 2 • Cevap gelmezse", "Kişi cevap yazmazsa 30 saniye arayla gönderilir. Cevap yazarsa durur.")
        val f1 = field("2. mesaj – 30 sn içinde cevap gelmezse", "ör. Orada mısın?", Irc.followMsg(0))
        val f2 = field("3. mesaj – 30 sn daha cevap gelmezse", "ör. Müsait olunca yaz 🙂", Irc.followMsg(1))

        section("Bölüm 3 • Cevap havuzu", "Kişi her cevap yazdığında bu listeden rastgele bir cevap seçilip gönderilir. Her satıra bir cevap yaz. Aynı kişiye aynı cevap iki kez gitmez; liste bitince otomatik cevap durur.")
        box.addView(text("Cevaplar (her satıra bir tane)", 14f, C.TEXT).apply { setPadding(0, dp(10), 0, dp(4)) })
        val pool = input("ör.\nNasılsın?\nNerelisin?\nKaç yaşındasın?\nNe iş yapıyorsun?", Irc.replyPool(), ml)
        pool.minLines = 5
        pool.maxLines = 12
        pool.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        box.addView(pool)
        val count = text("", 13f, C.DIM).apply { setPadding(0, dp(4), 0, 0) }
        fun updateCount() {
            val n = pool.text.toString().split('\n').count { it.isNotBlank() }
            count.text = "Havuzda $n cevap var"
        }
        updateCount()
        pool.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateCount()
        })
        box.addView(count)
        val all = android.widget.CheckBox(this).apply {
            text = "Bana özelden yazan herkese de havuzdan cevap ver"
            isChecked = Irc.replyAll()
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(all)

        section("Bölüm 4 • Soru – cevap", "Kişinin mesajında soru kelimesi geçerse senin yazdığın cevap gider. Birden fazla kelime için virgül koy (ör. yaş, kaç yaşında). Uyan soru yoksa havuzdan cevap gider.")
        val qs = ArrayList<android.widget.EditText>()
        val ans = ArrayList<android.widget.EditText>()
        for (i in 0 until Irc.QA_COUNT) {
            box.addView(text("${i + 1}. soru", 14f, C.ACCENT, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            val q = input("Soru kelimesi", Irc.qaQuestion(i))
            box.addView(q); qs.add(q)
            val an = input("Cevabı", Irc.qaAnswer(i), ml)
            an.maxLines = 4
            box.addView(an.apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            }); ans.add(an)
        }

        box.addView(text("Boş bıraktığın kutular atlanır. Aynı kişiye günde en fazla bir kez başlar; mesajlar en az 5 saniye arayla gider. Engellediğin ya da sunucudan çıkan kişiye gitmez.", 13f, C.DIM).apply {
            setPadding(0, dp(16), 0, 0)
        })
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("🤖 Otomatik mesaj • ${conv.name}")
            .setView(scroll)
            .setPositiveButton("Kaydet") { _, _ ->
                fun clean(e: android.widget.EditText) = e.text.toString().replace("\n", " ").trim()
                val poolText = pool.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                Irc.setOpeningMsg(clean(first))
                Irc.setReplyPool(poolText)
                Irc.setReplyAll(all.isChecked)
                Irc.setQa(qs.map { clean(it) }, ans.map { clean(it) })
                Irc.setFollowMsgs(listOf(clean(f1), clean(f2)))
                val enable = on.isChecked && (clean(first).isNotEmpty() || poolText.isNotEmpty() || ans.any { clean(it).isNotEmpty() })
                Irc.setAutoOn(conv.name, enable)
                android.widget.Toast.makeText(this,
                    if (enable) "Otomatik mesaj açık" else "Otomatik mesaj kapalı",
                    android.widget.Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showUsers() {
        val users = conv.users.toList()
        if (users.isEmpty()) return
        fun label(u: String): String {
            val p = Irc.prefixOf(u)
            val badge = when {
                p.startsWith("~") -> "👑 "
                p.startsWith("&") -> "⭐ "
                p.startsWith("@") -> "🛡 "
                p.startsWith("%") -> "🔰 "
                p.startsWith("+") -> "🎤 "
                else -> ""
            }
            val f = Irc.flag(Irc.countryOf(u))
            return "     " + badge + Irc.bare(u) + (if (f.isNotEmpty()) "  $f" else "") + if (Irc.isBlocked(u)) "  🚫" else ""
        }
        // Gruplar: önce yurt dışı, sonra Almanya (.de), sonra adresi belli olmayanlar
        val groups = listOf(
            1 to "🌍 Yurt dışından",
            0 to "🇩🇪 Almanya (.de)",
            2 to "❔ Belli değil"
        )
        val labels = ArrayList<String>()
        val nicks = ArrayList<String?>()
        for ((g, title) in groups) {
            val list = users.filter { Irc.originOf(it) == g }
            if (list.isEmpty()) continue
            labels.add("$title • ${list.size} kişi"); nicks.add(null)
            for (u in list) { labels.add(label(u)); nicks.add(Irc.bare(u)) }
        }
        AlertDialog.Builder(this)
            .setTitle("${conv.name} • ${users.size} kişi")
            .setItems(labels.toTypedArray()) { _, i ->
                val n = nicks[i]
                if (n != null) openPrivate(n) else showUsers()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    /** Kişinin adına dokununca direkt özel sohbet açılır. */
    private fun msgMenu(m: Irc.Msg?) {
        val n = m?.nick ?: return
        if (n.equals(Irc.myNick, true) || conv.isStatus) return
        if (conv.isChannel) openPrivate(n) else nickMenu(n)
    }

    private fun msgLongPress(m: Irc.Msg?) {
        val n = m?.nick ?: return
        if (n.equals(Irc.myNick, true) || conv.isStatus) return
        nickMenu(n)
    }

    private fun openPrivate(nick: String) {
        if (nick.equals(Irc.myNick, true)) return
        if (Irc.isBlocked(nick)) { nickMenu(nick); return }
        val pc = Irc.openPrivate(nick)
        startActivity(Intent(this, ChatActivity::class.java).putExtra("name", pc.name))
    }

    private fun nickMenu(nick: String) {
        if (nick.equals(Irc.myNick, true)) return
        val blocked = Irc.isBlocked(nick)
        val opts = ArrayList<String>()
        if (conv.isChannel && !blocked) opts.add("💬 Özel mesaj gönder")
        if (!blocked) opts.add("@ Adını yaz")
        opts.add(if (blocked) "✅ Engeli kaldır" else "🚫 Engelle")
        AlertDialog.Builder(this)
            .setTitle(nick)
            .setItems(opts.toTypedArray()) { _, i ->
                val o = opts[i]
                when {
                    o.startsWith("💬") -> {
                        val pc = Irc.openPrivate(nick)
                        startActivity(Intent(this, ChatActivity::class.java).putExtra("name", pc.name))
                    }
                    o.startsWith("🚫") -> confirmBlock(nick)
                    o.startsWith("✅") -> {
                        Irc.setBlocked(nick, false)
                        android.widget.Toast.makeText(this, "$nick engeli kaldırıldı", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val cur = edit.text.toString()
                        edit.setText(if (cur.isEmpty()) "$nick: " else "$cur $nick ")
                        edit.setSelection(edit.text.length)
                        edit.requestFocus()
                    }
                }
            }
            .show()
    }

    private fun confirmBlock(nick: String) {
        AlertDialog.Builder(this)
            .setMessage("$nick engellensin mi?\nMesajlarını artık görmezsin, sana özelden yazamaz.")
            .setPositiveButton("Engelle") { _, _ ->
                Irc.setBlocked(nick, true)
                android.widget.Toast.makeText(this, "$nick engellendi", android.widget.Toast.LENGTH_SHORT).show()
                if (!conv.isChannel && !conv.isStatus && Irc.key(conv.name) == Irc.key(nick)) {
                    Irc.closeConv(conv.name); finish()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private inner class MsgAdapter : BaseAdapter() {
        override fun getCount() = conv.msgs.size
        override fun getItem(p: Int) = conv.msgs[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@ChatActivity).apply {
                textSize = 16f
                setTextColor(C.TEXT)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setLineSpacing(0f, 1.1f)
            }
            val m = conv.msgs[pos]
            val sb = SpannableStringBuilder()
            fun span(s: String, color: Int, bold: Boolean = false) {
                val st = sb.length
                sb.append(s)
                sb.setSpan(ForegroundColorSpan(color), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            span(timeFmt.format(Date(m.time)) + "  ", C.DIM)
            when (m.kind) {
                Irc.K_INFO -> span("• " + m.text, C.INFO)
                Irc.K_ERROR -> span("⚠ " + m.text, C.ERROR)
                Irc.K_ACTION -> span("✱ ${m.nick} ${m.text}", if (m.nick == Irc.myNick) C.ME else C.nick(m.nick ?: ""), false)
                Irc.K_NOTICE -> {
                    if (m.nick != null) span("-${m.nick}- ", C.NOTICE, true)
                    span(m.text, C.NOTICE)
                }
                else -> {
                    val mine = m.kind == Irc.K_SELF
                    span((m.nick ?: "") + ": ", if (mine) C.ME else C.nick(m.nick ?: ""), true)
                    val hi = !mine && Irc.myNick.isNotEmpty() && m.text.contains(Irc.myNick, true)
                    span(m.text, if (hi) C.ACCENT else C.TEXT, hi)
                }
            }
            tv.text = sb
            return tv
        }
    }
}
