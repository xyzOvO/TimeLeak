package com.chengliu.app.core

import com.chengliu.app.data.ServiceCatalog
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
 */
object BlockLog {

    /** 最多保留多少个服务条目。超出的按「最久没出现」淘汰。 */
    const val LIMIT = 60

    private const val SAMPLE_DOMAINS = 3
    private const val PUBLISH_GAP_MS = 350L

    @Volatile
    var enabled: Boolean = true

    private class Bucket(
        val key: String,
        val name: String,
        val owner: String,
        val kind: ServiceKind,
        val what: String,
        val why: String,
        val source: RuleSource,
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
                name = info?.name ?: rule,
                owner = info?.owner ?: source.label,
                kind = info?.kind ?: ServiceKind.OTHER,
                what = info?.what ?: "不在内置说明库里，无法确定它具体做什么",
                why = info?.why ?: unknownWhy(source),
                source = source,
            ).also { buckets[key] = it }
            b.count += 1
            b.lastAt = now
            if (b.domains.size < SAMPLE_DOMAINS) b.domains.add(rule)
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

    private fun publish() {
        synchronized(lock) {
            lastPublish = System.currentTimeMillis()
            publishLocked()
        }
    }

    private fun publishLocked() {
        _hits.value = buckets.values.map {
            BlockHit(
                key = it.key,
                name = it.name,
                owner = it.owner,
                kind = it.kind,
                what = it.what,
                why = it.why,
                domains = it.domains.toList(),
                count = it.count,
                lastAt = it.lastAt,
                source = it.source,
            )
        }.sortedByDescending { it.lastAt }
    }

    private fun unknownWhy(source: RuleSource): String = when (source) {
        RuleSource.SUBSCRIPTION -> "这条来自你导入的规则集，规则作者把它列为广告或追踪域名。"
        RuleSource.CUSTOM -> "你自己把它加进了拦截名单。"
        RuleSource.BUILTIN -> "内置规则里有它，但没有对应的服务说明。"
        RuleSource.UNKNOWN -> "来源不明，建议核对后再决定是否放行。"
    }
}
