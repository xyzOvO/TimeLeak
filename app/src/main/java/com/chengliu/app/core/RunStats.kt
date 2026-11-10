package com.chengliu.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 运行统计：按自然日分桶，跨天自动归零。
 *
 * 口径说明（界面上也会写清楚，避免把「次数」读成「服务数」）：
 *  - DNS 查询 / 拦截次数沿用 [Counters]，由 VPN 服务埋点；
 *  - 「被放行的域名数量」= 今日因**放行规则**放行的去重域名数，不是全部查询域名数；
 *  - 「过滤规则命中来源」= 今日每次拦截按其命中名单累计的次数分布。
 *
 * 落盘的只有数字、放行域名和不含界面文案的摘要；界面按钮文字只进内存观察记录。
 *
 * 旧实现每次拦截都要把整个 sources JSON 从 SP 读出来解析、改一个数、再序列化写回，
 * 外加一次全量 publish() 重读；124 万次拦截就是 124 万次这样的 JSON 风暴。
 * 现改为内存里累加，每 5 秒批量落盘一次；StateFlow 仍实时更新。
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

    // 内存态：所有读写都先动这里，落盘交给定时器，避免每条包都碰 SP。
    @Volatile
    private var memDay: String = ""
    private val sourcesMem = LinkedHashMap<String, Long>()
    private val allowedMem = LinkedHashSet<String>()
    private var skipMem = 0
    private var observedMem = 0
    private var lastSkipMem: LastSkip? = null

    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        val t = Thread(r, "chengliu-runstats-flush")
        t.isDaemon = true
        t
    }

    @Synchronized
    fun init(ctx: Context) {
        // 多处会调用 init；只初始化一次，避免重复起刷盘线程、也避免把内存里已累加的数字覆盖掉。
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        loadFromSp()
        // 同上：固定延迟，避免进程被缓存后“补跑”大量落盘任务。
        flusher.scheduleWithFixedDelay({ flush() }, 5, 5, TimeUnit.SECONDS)
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    @Synchronized
    private fun loadFromSp() {
        val p = sp ?: return
        val d = today()
        if (p.getString("day", "") != d) {
            // 跨天：内存清零，但把旧的一天先落盘（其实旧值没变，这里只是确保 day 标记正确）
            memDay = d
            sourcesMem.clear()
            allowedMem.clear()
            skipMem = 0
            observedMem = 0
            lastSkipMem = null
            return
        }
        memDay = d
        sourcesMem.clear()
        runCatching {
            val o = JSONObject(p.getString("sources", "{}") ?: "{}")
            o.keys().forEach { k -> sourcesMem[k] = o.optLong(k, 0L) }
        }
        allowedMem.clear()
        allowedMem.addAll(p.getStringSet("allowed_set", emptySet()) ?: emptySet())
        skipMem = p.getInt("skip", 0)
        observedMem = p.getInt("observed", 0)
        lastSkipMem = runCatching {
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
    }

    /** 跨天检测：与 Counters 同理，只在偶发边界加锁重置，并把旧值落盘一次。 */
    @Synchronized
    private fun ensureDay() {
        val d = today()
        if (d == memDay) return
        flushLocked()
        memDay = d
        sourcesMem.clear()
        allowedMem.clear()
        skipMem = 0
        observedMem = 0
        lastSkipMem = null
    }

    /** 构建当前快照：只读内存 + Counters，不再读整个 SP。 */
    @Synchronized
    private fun publish() {
        val q = Counters.today.value
        _state.value = Snapshot(
            date = memDay.ifEmpty { today() },
            queries = q.first,
            blocked = q.second,
            bypass = Counters.bypass.value,
            allowedDomains = allowedMem.size,
            allowedCapped = allowedMem.size >= MAX_DOMAINS,
            skipClicks = skipMem,
            observed = observedMem,
            sources = LinkedHashMap(sourcesMem),
            lastSkip = lastSkipMem,
        )
    }

    /** VPN 放行了一个命中放行规则的域名。 */
    @Synchronized
    fun onAllowed(domain: String) {
        ensureDay()
        if (domain.isBlank()) return
        if (allowedMem.size >= MAX_DOMAINS) {
            publish()
            return
        }
        if (allowedMem.add(domain.lowercase())) publish()
    }

    /** 拦截命中：按来源累计，用于「过滤规则命中来源」。只动内存，不碰 SP。 */
    @Synchronized
    fun onBlocked(source: String) {
        ensureDay()
        val key = source.ifBlank { "未标注来源" }
        sourcesMem[key] = (sourcesMem[key] ?: 0L) + 1L
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
        ensureDay()
        if (action == DecisionAction.CLICK) skipMem++
        if (action == DecisionAction.OBSERVE) observedMem++
        lastSkipMem = LastSkip(
            System.currentTimeMillis(), pkg, rule, level.label, kind.label, action.label, score,
        )
        publish()
    }

    /** Counters 变化时由界面调用，刷新概览里的查询 / 拦截数字。 */
    fun refresh() = publish()

    @Synchronized
    private fun flushLocked() {
        val p = sp ?: return
        val e = p.edit()
        e.putString("day", memDay.ifEmpty { today() })
        val o = JSONObject()
        sourcesMem.forEach { (k, v) -> o.put(k, v) }
        e.putString("sources", o.toString())
        e.putStringSet("allowed_set", allowedMem)
        e.putInt("skip", skipMem)
        e.putInt("observed", observedMem)
        lastSkipMem?.let {
            val j = JSONObject()
            j.put("at", it.at); j.put("pkg", it.pkg); j.put("rule", it.rule)
            j.put("level", it.level); j.put("kind", it.kind); j.put("action", it.action)
            j.put("score", it.score)
            e.putString("last_skip", j.toString())
        } ?: e.remove("last_skip")
        e.apply()
    }

    /** 定时器调用：把内存态批量写回 SP。 */
    fun flush() = flushLocked()

    /** 只清运行数据，不动配置、名单、订阅与策略。 */
    @Synchronized
    fun clear() {
        memDay = today()
        sourcesMem.clear()
        allowedMem.clear()
        skipMem = 0
        observedMem = 0
        lastSkipMem = null
        sp?.edit()
            ?.putString("day", today())
            ?.remove("allowed_set")
            ?.putString("sources", "{}")
            ?.putInt("skip", 0)
            ?.putInt("observed", 0)
            ?.remove("last_skip")
            ?.apply()
        Counters.clear()
        publish()
    }
}
