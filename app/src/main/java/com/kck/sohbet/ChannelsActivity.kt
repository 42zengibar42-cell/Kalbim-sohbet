package com.kck.sohbet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class ChannelsActivity : Activity(), Irc.Listener {

    private val shown = ArrayList<Irc.ChanInfo>()
    private val adapter = Adapter()
    private lateinit var info: TextView
    private lateinit var bar: TopBar
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        bar = TopBar(this, "Oda listesi") { finish() }
        bar.action(smallButton("↻ Yenile") { Irc.requestList(); refresh() })
        root.addView(bar)

        val search = input("🔍  Oda ara…", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter = s?.toString()?.trim() ?: ""; refresh() }
        })
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(12), dp(14), dp(4)) })

        info = text("", 15f, C.DIM).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        root.addView(info)

        val lv = ListView(this).apply {
            divider = null; dividerHeight = 0
            adapter = this@ChannelsActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> enter(shown[pos].name) }
        }
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (!Irc.listLoaded && !Irc.listing) Irc.requestList()
    }

    override fun onResume() {
        super.onResume()
        Irc.addListener(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Irc.removeListener(this)
    }

    override fun onIrcChanged() = refresh()

    private fun enter(name: String) {
        if (Irc.state != 2) return
        val c = Irc.conv(name, false)
        if (c == null || !c.joined || c.users.isEmpty()) Irc.join(name)
        startActivity(Intent(this, ChatActivity::class.java).putExtra("name", name))
        finish()
    }

    private fun refresh() {
        shown.clear()
        val f = filter.lowercase()
        Irc.channelList
            .filter { f.isEmpty() || it.name.lowercase().contains(f) || it.topic.lowercase().contains(f) }
            .sortedByDescending { it.users }
            .let { shown.addAll(it) }
        adapter.notifyDataSetChanged()

        bar.sub(if (Irc.channelList.isNotEmpty()) "${Irc.channelList.size} oda" else "")
        info.visibility = View.VISIBLE
        info.text = when {
            Irc.state != 2 -> "Sunucuya bağlı değilsin."
            Irc.listing && shown.isEmpty() -> "Odalar yükleniyor…"
            Irc.listing -> "Yükleniyor… (${Irc.channelList.size})"
            shown.isEmpty() && filter.isNotEmpty() -> "\"$filter\" bulunamadı.\nYine de girmek için dokun: #${filter.removePrefix("#")}"
            shown.isEmpty() -> "Oda bulunamadı. \"Yenile\"ye dokun."
            else -> { info.visibility = View.GONE; "" }
        }
        info.setOnClickListener {
            if (!Irc.listing && shown.isEmpty() && filter.isNotEmpty())
                enter("#" + filter.removePrefix("#").replace(" ", ""))
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val ch = shown[pos]
            val row = LinearLayout(this@ChannelsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(C.CARD, 12)
                setPadding(dp(14), dp(12), dp(12), dp(12))
            }
            val col = LinearLayout(this@ChannelsActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(ch.name, 17f, C.ACCENT, true).apply { setSingleLine(true) })
            if (ch.topic.isNotBlank()) col.addView(text(ch.topic, 14f, C.DIM).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("👥 ${ch.users}", 14f, Color.WHITE).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = rounded(Color.parseColor("#4A3445"), 14)
            })
            return LinearLayout(this@ChannelsActivity).apply {
                setPadding(dp(14), dp(5), dp(14), dp(5))
                addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
}
