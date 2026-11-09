package com.chengliu.app.core

import com.chengliu.app.data.ServiceCatalog
import com.chengliu.app.data.ServiceExplainer
import com.chengliu.app.data.ServiceInfo
import com.chengliu.app.data.ServiceKind

/**
 * 一条被拦记录：按「服务」聚起来，而不是按域名逐条堆。
 *
 * 用户想知道的不是「刚才请求了 doubleclick.net 的 hb.3g 子域」，
 * 而是「谷歌广告又来塞广告了，一共 12 次」。
 */
data class BlockHit(
    val key: String,
    val name: String,
    val owner: String,
    val kind: ServiceKind,
    val what: String,
    val why: String,
    val domains: List<String>,
    val count: Long,
    val lastAt: Long,
    val source: RuleSource,
    /** 这份规则是哪来的：随规则文件头与订阅列表实时生成，不写死在代码里。 */
    val origin: String,
    /** 归属说明的公开依据；空表示按形态推断，没有归属资料。 */
    val evidence: String = "",
) {
    val domainText: String
        get() = when (domains.size) {
            0 -> ""
            1 -> domains[0]
            else -> "${domains[0]} 等 ${domains.size} 个域名"
        }
}

/**
 * 拦截明细：**只在内存里**保留最近的一小批归属信息。
 *
 * 这里和「查询过的域名不落盘」并不冲突，但确实把域名留在了内存中，
 * 所以做了三条约束，界面上的说明文案必须和这三条一致：
 *   1) 只在内存，进程结束即消失，不写 SharedPreferences、不写文件；
 *   2) 只保留聚合后的服务名与最多 3 个示例域名，不保留完整查询序列；
 *   3) 用户可以一键清空，也可以在「拦截记录」页关掉这项记录。
 *
 * 说明文字全部由本地的手工说明库 / 域名词根推断生成，不联网、不上传。
 */
object BlockLog {

    /** 最多保留多少个服务条目。超出的按「最久没出现」淘汰。 */
    const val LIMIT = 60

    private const val SAMPLE_DOMAINS = 3
    private const val PUBLISH_GAP_MS = 350L

    @Volatile
    var enabled: Boolean = true

    /**
     * 桶里只存「命中了什么、属于哪一层名单」这些原始事实。
     * origin 是**命中当时**的来源快照：切档位、换订阅后旧记录不能改口说成
     * 一份当时根本没生效的名单。当前生效的是哪批规则，由页面底部实时显示。
     */
    private class Bucket(
        val key: String,
        val queryName: String,
        val rule: String,
        val source: RuleSource,
        val origin: String,
    ) {
        var count: Long = 0
        var lastAt: Long = 0
        val domains = LinkedHashSet<String>()
    }

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()
    private val _hits = kotlinx.coroutines.flow.MutableStateFlow<List<BlockHit>>(emptyList())
    val hits: kotlinx.coroutines.flow.StateFlow<List<BlockHit>> = _hits

    private var lastPublish = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val publishTask = Runnable { publish() }

    /**
     * @param queryName 客户端实际查询的完整域名
     * @param rule      命中的那条规则（通常是父域）
     */
    fun record(queryName: String, rule: String, source: RuleSource) {
        if (!enabled) return
        val info: ServiceInfo? = ServiceCatalog.lookup(queryName) ?: ServiceCatalog.lookup(rule)
        val key = info?.key ?: "rule:$rule"
        val now = System.currentTimeMillis()
        var needSchedule = false
        synchronized(lock) {
            val b = buckets[key] ?: Bucket(
                key = key,
                queryName = queryName,
                rule = rule,
                source = source,
                origin = SourceInfo.originOf(source),
            ).also { buckets[key] = it }
            b.count += 1
            b.lastAt = now
            // 记实际被拦的完整域名，不是命中的父域规则：用户要看的是「刚才谁连了这里」
            if (b.domains.size < SAMPLE_DOMAINS) b.domains.add(queryName)
            if (buckets.size > LIMIT) {
                buckets.entries
                    .minByOrNull { it.value.lastAt }
                    ?.let { buckets.remove(it.key) }
            }
            if (now - lastPublish >= PUBLISH_GAP_MS) {
                lastPublish = now
                publishLocked()
            } else {
                needSchedule = true
            }
        }
        if (needSchedule) {
            main.removeCallbacks(publishTask)
            main.postDelayed(publishTask, PUBLISH_GAP_MS)
        }
    }

    fun clear() {
        synchronized(lock) {
            buckets.clear()
            lastPublish = 0
            _hits.value = emptyList()
        }
    }

    /** 来源变化后重新发布一次：让页面底部的「当前生效的规则」刷新。计数、示例域名、命中时来源都不动。 */
    fun republish() {
        main.removeCallbacks(publishTask)
        main.post(publishTask)
    }

    private fun publish() {
        synchronized(lock) {
            lastPublish = System.currentTimeMillis()
            publishLocked()
        }
    }

    private fun publishLocked() {
        _hits.value = buckets.values.map {
            val d = describe(it.queryName, it.rule)
            BlockHit(
                key = it.key,
                name = d.name,
                owner = d.owner,
                kind = d.kind,
                what = d.what,
                why = d.why,
                domains = it.domains.toList(),
                count = it.count,
                lastAt = it.lastAt,
                source = it.source,
                origin = it.origin,
                evidence = d.evidence,
            )
        }.sortedByDescending { it.lastAt }
    }

    private class Desc(
        val name: String,
        val owner: String,
        val kind: ServiceKind,
        val what: String,
        val why: String,
        val evidence: String,
    )

    /**
     * 有公开依据的说明表优先（人工核实 → 随规则生成的厂商族 / 上游争议标注）；
     * 都没有才按域名词根与形态给一份标明「推断、未核实」的兜底说明。
     */
    private fun describe(queryName: String, rule: String): Desc {
        val info = ServiceCatalog.lookup(queryName) ?: ServiceCatalog.lookup(rule)
        if (info != null) {
            return Desc(info.name, info.owner, info.kind, info.what, info.why, info.evidence)
        }
        val g = ServiceExplainer.guess(queryName, rule)
        return Desc(g.name, g.owner, g.kind, g.what, g.why, "")
    }
}
