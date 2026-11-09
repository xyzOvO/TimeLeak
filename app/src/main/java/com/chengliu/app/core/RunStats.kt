package com.chengliu.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行统计：按自然日分桶，跨天自动归零。
 *
 * 口径说明（界面上也会写清楚，避免把「次数」读成「服务数」）：
 *  - DNS 查询 / 拦截次数沿用 [Counters]，由 VPN 服务埋点；
 *  - 「被放行的域名数量」= 今日因**放行规则**放行的去重域名数，不是全部查询域名数；
 *  - 「过滤规则命中来源」= 今日每次拦截按其命中名单累计的次数分布。
 *
 * 落盘的只有数字、放行域名和不含界面文案的摘要；界面按钮文字只进内存观察记录。
 */
object RunStats {

    /** 最近一次跳过：故意不保存按钮文本，只留可审计的规则与判定。 */
    data class LastSkip(
        val at: Long,
        val pkg: String,
        val rule: String,
        val level: String,
        val kind: String,
        val action: String,
        val score: Int,
    )

    data class Snapshot(
        val date: String = "",
        val queries: Long = 0L,
        val blocked: Long = 0L,
        val bypass: Long = 0L,
        val allowedDomains: Int = 0,
        val allowedCapped: Boolean = false,
        val skipClicks: Int = 0,
        val observed: Int = 0,
        val sources: Map<String, Long> = emptyMap(),
        val lastSkip: LastSkip? = null,
    )

    private const val SP_NAME = "chengliu_run_stats"
    private const val MAX_DOMAINS = 5000

    @Volatile
    private var sp: SharedPreferences? = null

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> get() = _state

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        ensureDay()
        publish()
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun ensureDay() {
        val p = sp ?: return
        val d = today()
        if (p.getString("day", "") != d) {
            p.edit()
                .putString("day", d)
                .remove("allowed_set")
                .putString("sources", "{}")
                .putInt("skip", 0)
                .putInt("observed", 0)
                .remove("last_skip")
                .apply()
        }
    }

    private fun publish() {
        val p = sp
        if (p == null) {
            _state.value = Snapshot()
            return
        }
        val q = Counters.today.value
        val set = p.getStringSet("allowed_set", emptySet()) ?: emptySet()
        val sources = LinkedHashMap<String, Long>()
        runCatching {
            val o = JSONObject(p.getString("sources", "{}") ?: "{}")
            o.keys().forEach { k -> sources[k] = o.optLong(k, 0L) }
        }
        val last = runCatching {
            val raw = p.getString("last_skip", "") ?: ""
            if (raw.isBlank()) null else {
                val o = JSONObject(raw)
                LastSkip(
                    o.optLong("at", 0L), o.optString("pkg", ""), o.optString("rule", ""),
                    o.optString("level", ""), o.optString("kind", ""), o.optString("action", ""),
                    o.optInt("score", 0),
                )
            }
        }.getOrNull()
        _state.value = Snapshot(
            date = p.getString("day", today()) ?: today(),
            queries = q.first,
            blocked = q.second,
            bypass = Counters.bypass.value,
            allowedDomains = set.size,
            allowedCapped = set.size >= MAX_DOMAINS,
            skipClicks = p.getInt("skip", 0),
            observed = p.getInt("observed", 0),
            sources = sources,
            lastSkip = last,
        )
    }

    /** VPN 放行了一个命中放行规则的域名。 */
    @Synchronized
    fun onAllowed(domain: String) {
        val p = sp ?: return
        ensureDay()
        if (domain.isBlank()) return
        val set = (p.getStringSet("allowed_set", emptySet()) ?: emptySet()).toHashSet()
        if (set.size >= MAX_DOMAINS) {
            publish()
            return
        }
        if (set.add(domain.lowercase())) {
            p.edit().putStringSet("allowed_set", set).apply()
        }
        publish()
    }

    /** 拦截命中：按来源累计，用于「过滤规则命中来源」。 */
    @Synchronized
    fun onBlocked(source: String) {
        val p = sp ?: return
        ensureDay()
        val key = source.ifBlank { "未标注来源" }
        val m = LinkedHashMap<String, Long>()
        runCatching {
            val o = JSONObject(p.getString("sources", "{}") ?: "{}")
            o.keys().forEach { k -> m[k] = o.optLong(k, 0L) }
        }
        m[key] = (m[key] ?: 0L) + 1L
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString("sources", o.toString()).apply()
        publish()
    }

    /** 一次跳过动作（含观察模式记录，用 action 区分）。 */
    @Synchronized
    fun onSkip(
        pkg: String,
        rule: String,
        level: Confidence,
        kind: AdKind,
        action: DecisionAction,
        score: Int,
    ) {
        val p = sp ?: return
        ensureDay()
        val e = p.edit()
        if (action == DecisionAction.CLICK) e.putInt("skip", p.getInt("skip", 0) + 1)
        if (action == DecisionAction.OBSERVE) e.putInt("observed", p.getInt("observed", 0) + 1)
        val o = JSONObject()
        o.put("at", System.currentTimeMillis())
        o.put("pkg", pkg)
        o.put("rule", rule)
        o.put("level", level.label)
        o.put("kind", kind.label)
        o.put("action", action.label)
        o.put("score", score)
        e.putString("last_skip", o.toString())
        e.apply()
        publish()
    }

    /** Counters 变化时由界面调用，刷新概览里的查询 / 拦截数字。 */
    fun refresh() = publish()

    /** 只清运行数据，不动配置、名单、订阅与策略。 */
    @Synchronized
    fun clear() {
        val p = sp ?: return
        p.edit()
            .putString("day", today())
            .remove("allowed_set")
            .putString("sources", "{}")
            .putInt("skip", 0)
            .putInt("observed", 0)
            .remove("last_skip")
            .apply()
        Counters.clear()
        publish()
    }
}
