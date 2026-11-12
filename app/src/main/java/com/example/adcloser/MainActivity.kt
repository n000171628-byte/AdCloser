package com.example.adcloser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(Switch(this).apply {
            text = "啟用自動關閉"
            isChecked = Store.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Store.setEnabled(this@MainActivity, v) }
        })
        root.addView(Switch(this).apply {
            text = "顯示懸浮視窗（需先啟用無障礙服務）"
            isChecked = Store.floating(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Store.setFloating(this@MainActivity, v) }
        })
        root.addView(btn("開啟無障礙服務設定") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(btn("新增規則") { addRuleDialog(Rule("")) })
        root.addView(btn("從最近畫面加入規則") { fromRecent() })
        root.addView(btn("編輯排除的 App（套件名稱）") { blockedDialog() })
        root.addView(TextView(this).apply { text = "\n規則（長按刪除）"; textSize = 16f })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

    private fun refresh() {
        list.removeAllViews()
        val rules = Store.rules(this)
        rules.forEachIndexed { i, r ->
            list.addView(TextView(this).apply {
                val where = if (r.pkg.isEmpty()) "全部 App" else r.pkg
                val kind = when {
                    r.x >= 0 -> "座標(${"%.2f".format(r.x)}, ${"%.2f".format(r.y)}) 等${r.delay}秒"
                    r.byId -> "viewId"
                    else -> "按鈕"
                }
                text = "• ${r.pattern}  [$kind · $where]"
                setPadding(0, 12, 0, 12)
                setOnLongClickListener {
                    rules.removeAt(i); Store.saveRules(this@MainActivity, rules); refresh(); true
                }
            })
        }
    }

    private fun field(hint: String, num: Boolean = false) = EditText(this).apply {
        this.hint = hint
        if (num) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun addRuleDialog(init: Rule) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val p = field("文字 / 描述 / viewId 關鍵字（含 _ 視為 viewId）").apply { setText(init.pattern) }
        val k = field("限定 App 套件名稱（留空 = 全部）").apply { setText(init.pkg) }
        val x = field("X 比例 0~1（選填，座標備案；此時關鍵字為觸發文字）", true)
        val y = field("Y 比例 0~1（選填）", true)
        listOf(p, k, x, y).forEach { box.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        AlertDialog.Builder(this).setTitle("新增規則").setView(box)
            .setPositiveButton("儲存") { _, _ ->
                val xv = x.text.toString().toFloatOrNull(); val yv = y.text.toString().toFloatOrNull()
                val pat = p.text.toString().trim()
                if (pat.isNotEmpty()) {
                    val rules = Store.rules(this)
                    val ratio = xv != null && yv != null
                    rules.add(Rule(pat, k.text.toString().trim(),
                        if (ratio) xv!! else -1f, if (ratio) yv!! else -1f,
                        byId = init.byId && pat == init.pattern))
                    Store.saveRules(this, rules); refresh()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun fromRecent() {
        val seen = AdCloserService.lastSeen
        if (seen.isEmpty()) { Toast.makeText(this, "尚無資料：請先到廣告畫面再回來", Toast.LENGTH_LONG).show(); return }
        val names = seen.map { "${it.pattern}  (${it.pkg})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("選擇要加入的按鈕")
            .setItems(names) { _, i -> addRuleDialog(seen[i]) }.show()
    }

    private fun blockedDialog() {
        val e = EditText(this).apply { setText(Store.blocked(this@MainActivity).joinToString("\n")) }
        AlertDialog.Builder(this).setTitle("排除的 App（一行一個）").setView(e)
            .setPositiveButton("儲存") { _, _ ->
                Store.setBlocked(this, e.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet())
            }.setNegativeButton("取消", null).show()
    }
}
