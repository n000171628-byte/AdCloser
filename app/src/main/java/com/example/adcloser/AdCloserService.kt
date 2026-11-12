package com.example.adcloser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdCloserService : AccessibilityService() {

    companion object {
        /** 最近一次畫面上看到的可點擊節點，供 App 內「從最近畫面加入規則」使用 */
        @Volatile var lastSeen: List<Rule> = emptyList()
        @Volatile var instance: AdCloserService? = null
    }

    @Volatile var lastPkg = ""
    @Volatile var lastClass = ""
    private var windowSince = 0L
    private var attempts = 0

    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var lastClickAt = 0L
    private var lastSig = ""
    private var overlay: Overlay? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "floating") syncOverlay()
    }

    override fun onServiceConnected() {
        instance = this
        overlay = Overlay(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        syncOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        overlay?.hide()
        return super.onUnbind(intent)
    }

    private fun syncOverlay() {
        if (Store.floating(this)) overlay?.show() else overlay?.hide()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString().orEmpty()
            val c = e.className?.toString().orEmpty()
            if (p.isNotEmpty() && p != packageName && p != "com.android.systemui") {
                if (p != lastPkg || c != lastClass) {
                    lastPkg = p; lastClass = c
                    windowSince = System.currentTimeMillis(); attempts = 0
                }
            }
        }
        if (!Store.enabled(this)) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        schedule(350)
    }

    override fun onInterrupt() {}

    private fun schedule(ms: Long) {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({ scheduled = false; scan() }, ms)
    }

    fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun scan() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg in Store.blocked(this)) return
        val rules = Store.rules(this).filter { it.pkg.isEmpty() || it.pkg == pkg }

        val seen = ArrayList<Rule>()
        val texts = ArrayList<String>()
        var target: AccessibilityNodeInfo? = null
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }

        while (queue.isNotEmpty() && count < 500) {
            val n = queue.removeFirst(); count++
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty().substringAfter(":id/")
            if (t.isNotEmpty()) texts.add(t)
            if (n.isClickable && n.isVisibleToUser && (t.isNotEmpty() || d.isNotEmpty() || id.isNotEmpty())) {
                val label = t.ifEmpty { d }.ifEmpty { id }
                if (label.length <= 12) seen.add(Rule(label, pkg, byId = t.isEmpty() && d.isEmpty()))
                if (target == null && rules.any { it.x < 0 && matches(it, t, d, id) }) target = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        lastSeen = seen.distinctBy { it.pattern }.take(40)

        val now = System.currentTimeMillis()
        if (now - lastClickAt < 600) return

        target?.let {
            val sig = pkg + "|" + (it.viewIdResourceName ?: it.text ?: it.contentDescription)
            if (sig == lastSig && now - lastClickAt < 3000) return   // 避免同一按鈕連點
            if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClickAt = now; lastSig = sig
            }
            return
        }

        // 座標備案（比例座標，適應各種解析度）
        val ratio = rules.firstOrNull {
            it.x >= 0 && (if (it.cls.isNotEmpty()) (it.cls == lastClass && pkg == lastPkg)
                          else texts.any { s -> s.contains(it.pattern, true) })
        } ?: return
        val wait = ratio.delay * 1000L - (now - windowSince)
        if (wait > 0) { schedule(minOf(wait, 1000L) + 50); return }
        if (attempts >= 3) return
        attempts++
        val (w, h) = realSize()
        tap(ratio.x * w, ratio.y * h)
        lastClickAt = now
        if (attempts < 3) schedule(1500)
    }

    private fun matches(r: Rule, t: String, d: String, id: String): Boolean {
        val p = r.pattern
        if (p.isEmpty()) return false
        if (r.byId) return id.equals(p, true)
        if (p.length <= 2) return t.equals(p, true) || d.equals(p, true)
        if (p.contains('_')) return id.contains(p, true)
        return t.equals(p, true) || d.equals(p, true) ||
            (t.length <= 8 && t.contains(p, true)) || (d.length <= 8 && d.contains(p, true))
    }

    /** 懸浮視窗的「選取關閉鈕」：把使用者點的位置轉成規則。回傳 null 代表無法辨識。 */
    fun describePoint(px: Float, py: Float): Rule? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val (w, h) = realSize()
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var count = 0
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (q.isNotEmpty() && count < 800) {
            val n = q.removeFirst(); count++
            n.getBoundsInScreen(r)
            if (n.isClickable && r.contains(px.toInt(), py.toInt())) {
                val a = r.width().toLong() * r.height()
                if (a < bestArea) { best = n; bestArea = a }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        best?.let { n ->
            if (bestArea < w.toLong() * h / 16) {   // 夠小才當成關閉鈕
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                val id = n.viewIdResourceName.orEmpty().substringAfter(":id/")
                when {
                    id.isNotEmpty() -> return Rule(id, pkg, byId = true)
                    d.isNotEmpty() && d.length <= 12 -> return Rule(d, pkg)
                    t.isNotEmpty() && t.length <= 12 -> return Rule(t, pkg)
                }
            }
        }
        val cls = if (lastPkg == pkg) lastClass else ""
        if (cls.isEmpty()) return null
        return Rule("座標 ${(px / w * 100).toInt()}%,${(py / h * 100).toInt()}%", pkg, px / w, py / h, cls, 5)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        dispatchGesture(g, null, null)
    }
}
