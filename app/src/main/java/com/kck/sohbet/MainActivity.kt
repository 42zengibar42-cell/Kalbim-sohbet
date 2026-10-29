package com.kck.sohbet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Irc.Listener {

    private val prefs by lazy { getSharedPreferences("ayarlar", MODE_PRIVATE) }
    private var showingLogin: Boolean? = null
    private var bar: TopBar? = null
    private var list: ListView? = null
    private var hint: TextView? = null
    private val items = ArrayList<Irc.Conv>()
    private val adapter = ConvAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("izinSoruldu", false)
        ) {
            prefs.edit().putBoolean("izinSoruldu", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Irc.activeConv = null
        Irc.addListener(this)
        build()
    }

    override fun onPause() {
        super.onPause()
        Irc.removeListener(this)
    }

    override fun onIrcChanged() = build()

    private fun build() {
        val login = Irc.state == 0 && Irc.convs.values.none { !it.isStatus }
        if (login) {
            if (showingLogin != true) showLogin()
        } else {
            if (showingLogin != false) showHome()
            refreshHome()
        }
    }

    // ---------------- Giriş ekranı ----------------

    private fun showLogin() {
        showingLogin = true
        bar = null; list = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.addView(TopBar(this, "Kalbim Sohbet", null))

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(30))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        box.addView(text("Hoş geldin! 💗", 24f, C.TEXT, true))
        box.addView(text("Sohbete katılmak için bir rumuz (takma ad) seç.", 15f, C.DIM).apply {
            setPadding(0, dp(6), 0, dp(10))
        })

        if (Irc.lastError.isNotEmpty()) {
            box.addView(text(Irc.lastError, 15f, C.ERROR).apply {
                background = rounded(Color.parseColor("#3A1E24"), 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })
        }

        box.addView(label("Rumuz"))
        val nick = input("ör. GülBahçesi", prefs.getString("nick", "") ?: "")
        box.addView(nick)

        box.addView(label("Rumuz şifresi (kayıtlıysa, boş bırakılabilir)"))
        val pass = input("", prefs.getString("pass", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        box.addView(pass)

        // Gelişmiş ayarlar
        val adv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val toggle = text("⚙  Sunucu ayarları", 15f, C.ACCENT).apply {
            setPadding(0, dp(18), 0, dp(4))
            setOnClickListener { adv.visibility = if (adv.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        box.addView(toggle)
        adv.addView(label("Sunucu"))
        val host = input("irc.kalbim.net", prefs.getString("host", "irc.kalbim.net") ?: "irc.kalbim.net",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        adv.addView(host)
        adv.addView(label("Port"))
        val port = input("6667", prefs.getInt("port", 6667).toString(), InputType.TYPE_CLASS_NUMBER)
        adv.addView(port)
        val ssl = CheckBox(this).apply {
            text = "SSL (güvenli bağlantı, genelde port 6697)"
            setTextColor(C.TEXT)
            isChecked = prefs.getBoolean("ssl", false)
            setPadding(dp(4), dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, on ->
                val p = port.text.toString()
                if (on && p == "6667") port.setText("6697")
                if (!on && p == "6697") port.setText("6667")
            }
        }
        adv.addView(ssl)
        box.addView(adv)

        val go = button("Bağlan") {
            val n = nick.text.toString().trim().replace(" ", "_")
            if (n.isEmpty()) { nick.error = "Rumuz yaz"; return@button }
            if (n.length > 30 || !n.first().isLetter()) { nick.error = "Rumuz harfle başlamalı, en fazla 30 karakter"; return@button }
            val h = host.text.toString().trim().ifEmpty { "irc.kalbim.net" }
            val pt = port.text.toString().toIntOrNull() ?: 6667
            prefs.edit()
                .putString("nick", n).putString("pass", pass.text.toString())
                .putString("host", h).putInt("port", pt).putBoolean("ssl", ssl.isChecked)
                .apply()
            Irc.connect(h, pt, ssl.isChecked, n, pass.text.toString())
            build()
        }
        box.addView(go, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })

        setContentView(root)
    }

    // ---------------- Ana ekran (sohbetlerim) ----------------

    private fun showHome() {
        showingLogin = false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val b = TopBar(this, "Kalbim Sohbet", null)
        b.action(smallButton("⏻") { confirmDisconnect() })
        bar = b
        root.addView(b)

        val rooms = button("🔍  Oda listesi") {
            startActivity(Intent(this, ChannelsActivity::class.java))
        }
        root.addView(rooms, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(14), dp(14), dp(14), dp(6))
        })

        hint = text("", 15f, C.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(30), dp(24), dp(10))
        }
        root.addView(hint)

        val lv = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> openConv(items[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> convMenu(items[pos]); true }
        }
        list = lv
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun refreshHome() {
        val b = bar ?: return
        b.sub(
            when (Irc.state) {
                2 -> "Bağlı • ${Irc.myNick}"
                1 -> "Bağlanıyor…"
                else -> "Bağlı değil"
            }
        )
        b.actions.removeAllViews()
        if (Irc.state == 0) {
            b.action(smallButton("Yeniden bağlan") { reconnect() })
            b.action(smallButton("✕") { Irc.convs.clear(); build() })
        } else {
            b.action(smallButton("⏻") { confirmDisconnect() })
        }

        items.clear()
        val all = Irc.convs.values.toList()
        items.addAll(all.filter { it.isChannel })
        items.addAll(all.filter { !it.isChannel && !it.isStatus })
        all.firstOrNull { it.isStatus }?.let { items.add(it) }
        adapter.notifyDataSetChanged()

        hint?.let {
            val noChats = items.none { c -> !c.isStatus }
            it.visibility = if (noChats) View.VISIBLE else View.GONE
            it.text = when {
                Irc.state == 1 -> "Sunucuya bağlanılıyor, lütfen bekle…"
                Irc.state == 2 -> "Henüz bir odaya girmedin.\n\"Oda listesi\"ne dokunup bir oda seç."
                else -> Irc.lastError
            }
        }
    }

    private fun reconnect() {
        val h = prefs.getString("host", "irc.kalbim.net") ?: "irc.kalbim.net"
        Irc.connect(h, prefs.getInt("port", 6667), prefs.getBoolean("ssl", false),
            prefs.getString("nick", "Misafir") ?: "Misafir", prefs.getString("pass", "") ?: "")
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setMessage("Sohbetten çıkmak istiyor musun?")
            .setPositiveButton("Çık") { _, _ ->
                Irc.disconnect()
                Irc.convs.clear()
                Irc.lastError = ""
                build()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun openConv(c: Irc.Conv) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra("name", c.name))
    }

    private fun convMenu(c: Irc.Conv) {
        if (c.isStatus) return
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(arrayOf(if (c.isChannel) "Odadan çık" else "Sohbeti kapat")) { _, _ -> Irc.closeConv(c.name) }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && Irc.state == 0) Irc.convs.clear()
    }

    private inner class ConvAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val c = items[pos]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(C.CARD, 12)
                setPadding(dp(14), dp(12), dp(12), dp(12))
            }
            val icon = text(
                when { c.isStatus -> "ℹ"; c.isChannel -> "#"; else -> "👤" }, 20f,
                if (c.isChannel) C.ACCENT else C.TEXT, true
            ).apply { gravity = Gravity.CENTER; minWidth = dp(34) }
            row.addView(icon)
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
            }
            val title = when {
                c.isStatus -> "Sunucu mesajları"
                c.isChannel -> c.name.removePrefix("#")
                else -> "${c.name} (özel)"
            }
            col.addView(text(title, 17f, C.TEXT, c.unread > 0).apply { setSingleLine(true) })
            val last = c.msgs.lastOrNull()
            val sub = when {
                last == null -> if (c.isChannel) "${c.users.size} kişi" else ""
                last.nick != null -> "${last.nick}: ${last.text}"
                else -> last.text
            }
            if (sub.isNotEmpty()) col.addView(text(sub, 14f, C.DIM).apply {
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (c.unread > 0) {
                row.addView(text(if (c.unread > 99) "99+" else c.unread.toString(), 13f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(26)
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    background = rounded(if (c.mention) C.PRIMARY else Color.parseColor("#6A5563"), 14)
                })
            }
            val wrap = LinearLayout(this@MainActivity).apply {
                setPadding(dp(14), dp(5), dp(14), dp(5))
                addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            return wrap
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED)
            Toast.makeText(this, "Bildirim izni olmadan da çalışır.", Toast.LENGTH_SHORT).show()
    }
}
