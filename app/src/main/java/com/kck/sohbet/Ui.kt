package com.kck.sohbet

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object C {
    val BG = Color.parseColor("#1A1219")
    val CARD = Color.parseColor("#2A1D28")
    val BAR = Color.parseColor("#7A0F38")
    val PRIMARY = Color.parseColor("#D81B60")
    val ACCENT = Color.parseColor("#FF7EAA")
    val TEXT = Color.parseColor("#F5ECF1")
    val DIM = Color.parseColor("#A8949F")
    val INFO = Color.parseColor("#8FA8B8")
    val ERROR = Color.parseColor("#FF6B6B")
    val NOTICE = Color.parseColor("#E8BE5C")
    val ME = Color.parseColor("#7EE0A8")

    private val NICK_COLORS = intArrayOf(
        Color.parseColor("#FF8A80"), Color.parseColor("#FFB74D"), Color.parseColor("#FFF176"),
        Color.parseColor("#AED581"), Color.parseColor("#4DD0E1"), Color.parseColor("#64B5F6"),
        Color.parseColor("#B39DDB"), Color.parseColor("#F48FB1"), Color.parseColor("#80CBC4"),
        Color.parseColor("#BCAAA4")
    )

    fun nick(n: String): Int {
        var h = 0
        for (ch in n.lowercase()) h = h * 31 + ch.code
        return NICK_COLORS[Math.floorMod(h, NICK_COLORS.size)]
    }
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
).toInt()

fun Context.text(s: CharSequence, size: Float = 16f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.rounded(color: Int, radiusDp: Int = 12, stroke: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

fun Context.button(label: String, color: Int = C.PRIMARY, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = rounded(color, 10)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = 0; minimumHeight = 0
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

fun Context.smallButton(label: String, onClick: () -> Unit): TextView =
    text(label, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(Color.parseColor("#33FFFFFF"), 18)
        setOnClickListener { onClick() }
    }

fun Context.input(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        textSize = 17f
        setTextColor(C.TEXT)
        setHintTextColor(C.DIM)
        background = rounded(C.CARD, 10, Color.parseColor("#4A3445"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setSingleLine((type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0)
    }

fun Context.label(s: String): TextView = text(s, 14f, C.DIM).apply {
    setPadding(dp(2), dp(14), 0, dp(6))
}

/** Üst çubuk: geri tuşu (isteğe bağlı), başlık, alt başlık, sağda düğmeler. */
class TopBar(ctx: Context, title: String, back: (() -> Unit)?) : LinearLayout(ctx) {
    val titleView: TextView
    val subView: TextView
    val actions: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(C.BAR)
        setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        if (back != null) {
            addView(ctx.text("‹", 30f, Color.WHITE, true).apply {
                setPadding(ctx.dp(10), 0, ctx.dp(14), ctx.dp(4))
                setOnClickListener { back() }
            })
        } else {
            addView(View(ctx), LayoutParams(ctx.dp(8), 1))
        }
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        titleView = ctx.text(title, 19f, Color.WHITE, true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
        subView = ctx.text("", 13f, Color.parseColor("#F3C6D6")).apply {
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END; visibility = GONE
        }
        col.addView(titleView); col.addView(subView)
        addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addView(actions)
    }

    fun sub(s: String) {
        subView.text = s
        subView.visibility = if (s.isEmpty()) GONE else VISIBLE
    }

    fun action(v: View) {
        actions.addView(v, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(6)
        })
    }
}
