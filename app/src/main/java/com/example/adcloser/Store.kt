package com.example.adcloser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 規則類型：
 *  - x < 0  : 比對節點的文字 / 描述 / viewId（byId=true 時只比對 viewId），符合就點擊該按鈕
 *  - x >= 0 : 座標備案，(x,y) 為螢幕比例 0~1，自動適應解析度。
 *             cls 不為空：該 App 的視窗類別等於 cls 時觸發（圖示化新增產生）
 *             cls 為空  ：視窗內出現 pattern 文字時觸發
 *             delay     ：視窗出現後等待幾秒才點，避免廣告倒數未結束時點到廣告本體
 * pkg 空字串 = 所有 App
 */
data class Rule(
    val pattern: String,
    val pkg: String = "",
    val x: Float = -1f,
    val y: Float = -1f,
    val cls: String = "",
    val delay: Int = 5,
    val byId: Boolean = false
)

object Store {
    private const val P = "ad_closer"
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()

    fun floating(c: Context) = prefs(c).getBoolean("floating", false)
    fun setFloating(c: Context, v: Boolean) = prefs(c).edit().putBoolean("floating", v).apply()

    // 預設排除：系統介面、設定、輸入法、本 App、LINE
    private val defaultBlocked = setOf(
        "com.android.systemui", "com.android.settings", "com.android.launcher3",
        "com.google.android.inputmethod.latin", "com.example.adcloser", "jp.naver.line.android"
    )

    fun blocked(c: Context): Set<String> = prefs(c).getStringSet("blocked", null) ?: defaultBlocked
    fun setBlocked(c: Context, s: Set<String>) = prefs(c).edit().putStringSet("blocked", s).apply()

    private val defaultRules = listOf(
        "跳過", "略過", "跳过", "關閉廣告", "关闭广告", "Skip", "Skip Ad", "Skip ads",
        "Close", "Dismiss", "✕", "×", "X",
        "iv_close", "btn_close", "close_btn", "ad_close", "skip_btn", "tv_skip", "dismiss"
    ).map { Rule(it) }

    fun rules(c: Context): MutableList<Rule> {
        val s = prefs(c).getString("rules", null) ?: return defaultRules.toMutableList()
        val arr = JSONArray(s)
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Rule(
                o.getString("p"), o.optString("k"),
                o.optDouble("x", -1.0).toFloat(), o.optDouble("y", -1.0).toFloat(),
                o.optString("c"), o.optInt("w", 5), o.optBoolean("i", false)
            )
        }
    }

    fun saveRules(c: Context, list: List<Rule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("p", it.pattern).put("k", it.pkg)
                .put("x", it.x.toDouble()).put("y", it.y.toDouble())
                .put("c", it.cls).put("w", it.delay).put("i", it.byId))
        }
        prefs(c).edit().putString("rules", arr.toString()).apply()
    }
}
