package app.agentime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Builds the compact in-keyboard keypads. */
class Keypad(
    private val context: Context,
    private val onChar: (Char) -> Unit,
    private val onBackspace: () -> Unit,
    private val onSpace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onPinyinDone: () -> Unit,
    private val onPinyinCancel: () -> Unit
) {

    fun buildQwerty(rows: List<LinearLayout>, onPinyin: (String) -> Unit) {
        val r1 = "qwertyuiop".toList()
        val r2 = "asdfghjkl".toList()
        val r3 = "zxcvbnm".toList()
        fillRow(rows[0], r1) { c -> onPinyin(c.toString()) }
        fillRow(rows[1], r2) { c -> onPinyin(c.toString()) }
        val row3 = rows[2]
        row3.removeAllViews()
        // Pinyin commit / cancel shortcuts at edges of the third row
        addKey(row3, "汉", 1f) { onPinyinDone() }
        r3.forEach { c -> addKey(row3, c.toString().uppercase(), 1f) { onPinyin(c.toString()) } }
        addKey(row3, "退格", 1.4f, secondary = true) { onBackspace() }

        val rowBottom = rows[3]
        rowBottom.removeAllViews()
        addKey(rowBottom, "拼音", 1.2f, secondary = true) { onPinyin("zh") }
        addKey(rowBottom, "空格", 2.2f, secondary = true) { onSpace() }
        addKey(rowBottom, "回车", 1.4f, secondary = true) { onEnter() }
        addKey(rowBottom, "取消拼音", 1.6f, secondary = true) { onPinyinCancel() }
    }

    /** Builds a phone-style 3x3 T9 keypad for the interim Pinyin adapter. */
    fun buildNineKey(
        rows: List<LinearLayout>,
        onDigit: (Char) -> Unit,
        onZero: () -> Unit
    ) {
        val keys = listOf(
            listOf("1", "2 ABC", "3 DEF"),
            listOf("4 GHI", "5 JKL", "6 MNO"),
            listOf("7 PQRS", "8 TUV", "9 WXYZ")
        )
        keys.forEachIndexed { rowIndex, labels ->
            rows[rowIndex].removeAllViews()
            labels.forEachIndexed { columnIndex, label ->
                addKey(rows[rowIndex], label, 1f) { onDigit(('1'.code + rowIndex * 3 + columnIndex).toChar()) }
            }
        }
        val rowBottom = rows[3]
        rowBottom.removeAllViews()
        // Keep the bottom actions clear of the navigation bar: 56dp-tall keys
        // plus an 8dp bottom gutter, so the touch target is far larger than
        // the 2-3 glyph label and never depends on a pixel-perfect tap.
        rowBottom.setPadding(dp(4), dp(2), dp(4), dp(8))
        addKey(rowBottom, "26键", 1f, secondary = true, heightDp = 56) { onPinyinCancel() }
        addKey(rowBottom, "0", 1f, secondary = true, heightDp = 56) { onZero() }
        addKey(rowBottom, "回车", 1f, secondary = true, heightDp = 56) { onEnter() }
        addKey(rowBottom, "退格", 1f, secondary = true, heightDp = 56) { onBackspace() }
    }

    fun buildDirect(rows: List<LinearLayout>, onCharDirect: (Char) -> Unit) {
        val r1 = "qwertyuiop".toList()
        val r2 = "asdfghjkl".toList()
        val r3 = "zxcvbnm".toList()
        fillRow(rows[0], r1) { c -> onCharDirect(c) }
        fillRow(rows[1], r2) { c -> onCharDirect(c) }
        val row3 = rows[2]
        row3.removeAllViews()
        r3.forEach { c -> addKey(row3, c.toString().uppercase(), 1f) { onCharDirect(c) } }
        addKey(row3, "退格", 1.4f, secondary = true) { onBackspace() }
        val rowBottom = rows[3]
        rowBottom.removeAllViews()
        addKey(rowBottom, "空格", 2.2f, secondary = true) { onSpace() }
        addKey(rowBottom, "回车", 1.2f, secondary = true) { onEnter() }
    }

    private fun fillRow(row: LinearLayout, chars: List<Char>, fn: (Char) -> Unit) {
        row.removeAllViews()
        chars.forEach { c -> addKey(row, c.toString().uppercase(), 1f) { fn(c) } }
    }

    private fun addKey(
        row: LinearLayout,
        label: String,
        weight: Float,
        secondary: Boolean = false,
        heightDp: Int = 48,
        onClick: () -> Unit
    ) {
        val btn = Button(context)
        btn.text = label
        btn.isAllCaps = false
        // Keep labels readable on narrow screens while reserving a much larger
        // touch target than the glyphs themselves.
        val textSizeSp = when {
            label.length > 3 -> 13f
            label.length == 3 -> 14f
            label.length == 2 -> 15f
            else -> 16f
        }
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        // Safety net for very narrow windows: shrink the glyphs, never the
        // fixed touch height or the surrounding hit area.
        btn.setAutoSizeTextTypeUniformWithConfiguration(
            9,
            textSizeSp.toInt(),
            1,
            android.util.TypedValue.COMPLEX_UNIT_SP
        )
        btn.setTextColor(if (secondary) Color.parseColor("#182230") else Color.parseColor("#182230"))
        btn.minHeight = 0
        btn.minWidth = 0
        btn.minimumHeight = 0
        btn.minimumWidth = 0
        btn.includeFontPadding = false
        btn.maxLines = 1
        btn.ellipsize = null
        btn.setPadding(dp(3), dp(2), dp(3), dp(2))
        val bg = GradientDrawable()
        bg.cornerRadius = context.resources.displayMetrics.density * 12f
        bg.setColor(Color.WHITE)
        bg.setStroke((context.resources.displayMetrics.density * 1).toInt(), Color.parseColor("#E4E7EC"))
        btn.background = bg
        val lp = LinearLayout.LayoutParams(0, dp(heightDp), weight)
        lp.marginStart = (context.resources.displayMetrics.density * 3).toInt()
        lp.marginEnd = (context.resources.displayMetrics.density * 3).toInt()
        lp.topMargin = (context.resources.displayMetrics.density * 3).toInt()
        lp.bottomMargin = (context.resources.displayMetrics.density * 3).toInt()
        btn.layoutParams = lp
        btn.gravity = Gravity.CENTER
        btn.setOnClickListener { onClick() }
        row.addView(btn)
    }

    fun showCandidates(container: LinearLayout, candidates: List<String>, onPick: (String) -> Unit) {
        container.removeAllViews()
        if (candidates.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        candidates.take(6).forEachIndexed { idx, cand ->
            val tv = TextView(context)
            tv.text = cand
            tv.textSize = 18f
            tv.setTextColor(Color.parseColor("#182230"))
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(12), dp(8), dp(12), dp(8))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(12).toFloat()
            bg.setColor(Color.WHITE)
            bg.setStroke(dp(1), Color.parseColor("#E4E7EC"))
            tv.background = bg
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.marginStart = dp(3); lp.marginEnd = dp(3)
            tv.layoutParams = lp
            tv.setOnClickListener { onPick(cand) }
            container.addView(tv)
        }
    }

    private fun dp(v: Int) = (context.resources.displayMetrics.density * v).toInt()
}
