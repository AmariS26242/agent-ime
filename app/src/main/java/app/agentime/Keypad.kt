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

    private fun addKey(row: LinearLayout, label: String, weight: Float, secondary: Boolean = false, onClick: () -> Unit) {
        val btn = Button(context)
        btn.text = label
        btn.isAllCaps = false
        btn.textSize = 16f
        btn.setTextColor(if (secondary) Color.parseColor("#182230") else Color.parseColor("#182230"))
        val bg = GradientDrawable()
        bg.cornerRadius = context.resources.displayMetrics.density * 12f
        bg.setColor(Color.WHITE)
        bg.setStroke((context.resources.displayMetrics.density * 1).toInt(), Color.parseColor("#E4E7EC"))
        btn.background = bg
        val lp = LinearLayout.LayoutParams(0, (context.resources.displayMetrics.density * 48).toInt(), weight)
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
