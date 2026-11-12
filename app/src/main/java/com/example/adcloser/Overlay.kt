package com.example.adcloser

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import kotlin.math.abs

/** 無障礙服務專用的懸浮視窗（TYPE_ACCESSIBILITY_OVERLAY，不需額外「顯示在其他應用程式上層」權限） */
class Overlay(private val svc: AdCloserService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dens = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()
    private fun scrW() = svc.resources.displayMetrics.widthPixels
    private fun scrH() = svc.resources.displayMetrics.heightPixels

    private var bubble: View? = null
    private var panel: View? = null
    private var picker: View? = null

    private var bx = 0
    private var by = dp(220)
    private var px = 0
    private var py = dp(160)
    private var pw = dp(230)
    private var ph = dp(200)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun params(w: Int, h: Int, flags: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) =
        WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    fun show() { if (bubble == null && panel == null && picker == null) addBubble() }

    fun hide() { removeBubble(); removePanel(); removePicker() }

    private fun rm(v: View?) { try { if (v != null) wm.removeView(v) } catch (_: Exception) {} }
    private fun removeBubble() { rm(bubble); bubble = null }
    private fun removePanel() { rm(panel); panel = null }
    private fun removePicker() { rm(picker); picker = null }

    private fun drag(touch: View, target: View, p: WindowManager.LayoutParams,
                     onTap: () -> Unit, onDrop: () -> Unit) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        touch.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { p.x = (ox + dx).toInt(); p.y = (oy + dy).toInt(); wm.updateViewLayout(target, p) }
                }
                MotionEvent.ACTION_UP -> if (moved) onDrop() else onTap()
            }
            true
        }
    }

    private fun addBubble() {
        val size = dp(44)
        val t = TextView(svc).apply {
            text = "✕"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC222222.toInt()) }
        }
        val p = params(size, size).apply { x = bx; y = by }
        drag(t, t, p, onTap = {
            by = p.y
            px = if (bx == 0) 0 else scrW() - pw
            py = by
            removeBubble(); addPanel()
        }, onDrop = {
            bx = if (p.x + size / 2 < scrW() / 2) 0 else scrW() - size   // 吸附到左右邊緣
            by = p.y; p.x = bx
            wm.updateViewLayout(t, p)
        })
        wm.addView(t, p); bubble = t
    }

    private fun addPanel() {
        pw = pw.coerceIn(dp(180), scrW()); ph = ph.coerceIn(dp(140), scrH())
        val root = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xEE202020.toInt()) }
        }
        val p = params(pw, ph).apply { x = px; y = py }

        val header = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(svc).apply { text = "廣告關閉（拖曳移動）"; setTextColor(Color.WHITE); textSize = 13f }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val min = TextView(svc).apply {
            text = "—"; setTextColor(Color.WHITE); textSize = 22f; setPadding(dp(14), 0, dp(4), 0)
            setOnClickListener {
                px = p.x; py = p.y
                bx = if (p.x + p.width / 2 < scrW() / 2) 0 else scrW() - dp(44)
                by = p.y
                removePanel(); addBubble()
            }
        }
        header.addView(min)
        root.addView(header)
        drag(title, root, p, onTap = {}, onDrop = {
            px = if (p.x + p.width / 2 < scrW() / 2) 0 else scrW() - p.width   // 吸附到旁邊
            py = p.y; p.x = px
            wm.updateViewLayout(root, p)
        })

        val swt = Switch(svc).apply {
            text = "自動關閉"; setTextColor(Color.WHITE); isChecked = Store.enabled(svc)
            setOnCheckedChangeListener { _, v -> Store.setEnabled(svc, v) }
        }
        root.addView(swt)
        root.addView(Button(svc).apply {
            text = "選取關閉鈕（點廣告的 X）"
            setOnClickListener { startPicker() }
        })
        root.addView(TextView(svc).apply {
            text = "規則會存到 App 內，可在主畫面管理"; setTextColor(0xFFBBBBBB.toInt()); textSize = 11f
        })
        root.addView(View(svc), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val handle = TextView(svc).apply {
            text = "◢ 縮放"; setTextColor(0xFFAAAAAA.toInt()); textSize = 14f; gravity = Gravity.END
            setPadding(dp(8), dp(8), 0, 0)
        }
        var sx = 0f; var sy = 0f; var ow = 0; var oh = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ow = p.width; oh = p.height }
                MotionEvent.ACTION_MOVE -> {
                    val maxW = maxOf(dp(180), scrW() - p.x)
                    val maxH = maxOf(dp(140), scrH() - p.y)
                    p.width = (ow + e.rawX - sx).toInt().coerceIn(dp(180), maxW)
                    p.height = (oh + e.rawY - sy).toInt().coerceIn(dp(140), maxH)
                    pw = p.width; ph = p.height
                    wm.updateViewLayout(root, p)
                }
            }
            true
        }
        root.addView(handle, LinearLayout.LayoutParams(MATCH, WRAP))
        wm.addView(root, p); panel = root
    }

    private fun startPicker() {
        removePanel()
        val f = FrameLayout(svc).apply { setBackgroundColor(0x44000000) }
        val hint = TextView(svc).apply {
            text = "請點一下廣告上的關閉（X）按鈕\n廣告需正在畫面上"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt()); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        f.addView(hint, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(60) })
        val cancel = Button(svc).apply { text = "取消"; setOnClickListener { removePicker(); addPanel() } }
        f.addView(cancel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(80) })
        f.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val x = e.rawX; val y = e.rawY
                removePicker(); finishPick(x, y)
            }
            true
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        wm.addView(f, params(MATCH, MATCH, flags)); picker = f
    }

    private fun finishPick(x: Float, y: Float) {
        val r = svc.describePoint(x, y)
        if (r == null) {
            Toast.makeText(svc, "無法辨識這個廣告視窗，請在廣告顯示時再試一次", Toast.LENGTH_LONG).show()
        } else {
            val list = Store.rules(svc); list.add(r); Store.saveRules(svc, list)
            val note = if (r.pkg in Store.blocked(svc)) "（此 App 在排除名單內，不會動作）" else ""
            Toast.makeText(svc, "已新增規則：${r.pattern} $note", Toast.LENGTH_LONG).show()
        }
        addPanel()
    }
}
