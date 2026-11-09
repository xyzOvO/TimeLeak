package com.chengliu.app.data

import android.content.Context
import com.chengliu.app.core.RuleParser
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置规则的三档强度。
 *
 * 三份文件都打进 APK，开箱即用，不需要用户自己去导订阅：
 * 弱档是整域广告平台，零误伤；中档加上统计 / 埋点 / 归因 / 推送 SDK 的子域；
 * 强档是 anti-AD 的全量精确子域表,拦得最干净，个别小众站点可能被牵连。
 *
 * 档位只影响「内置规则」这一层，用户自己加的拦截与订阅照常生效。
 */
enum class RuleTier(
    val key: String,
    val label: String,
    val asset: String,
    val desc: String,
) {
    WEAK("weak", "弱档", "rules/weak.txt", "只含整域广告平台，零误伤"),
    MEDIUM("medium", "中档", "rules/medium.txt", "弱档 + 统计、埋点、归因、推送 SDK 子域"),
    STRONG("strong", "强档", "rules/strong.txt", "全量精确子域表，拦得最干净");

    companion object {
        fun of(key: String?): RuleTier = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/**
 * assets 里的规则是文本，读一次、解析一次就够。
 *
 * 强档 9.6 万条，解析要几百毫秒，绝不能放在建隧道的主线程上——
 * 前台服务只有约 5 秒窗口，卡住就是整机没网。所以这里单独开线程加载，
 * 加载完成后回调让服务重建一次 DNS 处理器；在那之前先按空表跑，
 * 隧道照常工作，只是暂时不拦。
 */
object RuleStore {
    private val lock = Any()

    @Volatile
    private var loadedTier: String? = null

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var allowed: Set<String> = emptySet()

    @Volatile
    private var pending: String? = null

    /** 文件头 `!Rules:` 里的条数，只读前几行，给界面显示用。 */
    private val counts = ConcurrentHashMap<String, Int>()

    fun isLoaded(tier: String): Boolean = loadedTier == tier

    fun blockedOf(tier: String): Set<String> = if (loadedTier == tier) blocked else emptySet()

    fun allowedOf(tier: String): Set<String> = if (loadedTier == tier) allowed else emptySet()

    fun metaCount(ctx: Context, tier: String): Int? {
        counts[tier]?.let { return it }
        val n = runCatching {
            ctx.assets.open("rules/$tier.txt").bufferedReader().use { r ->
                var hit: Int? = null
                var i = 0
                while (i < 8 && hit == null) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("!Rules:")) {
                        hit = line.removePrefix("!Rules:").trim().toIntOrNull()
                    }
                    i++
                }
                hit
            }
        }.getOrNull() ?: return null
        counts[tier] = n
        return n
    }

    /**
     * 后台加载某一档。同一档位同时只会有一个任务；加载完成后回调所在线程
     * 是工作线程，调用方自己切回主线程。
     */
    fun load(ctx: Context, tier: String, onDone: (String) -> Unit) {
        synchronized(lock) {
            if (loadedTier == tier) {
                onDone(tier)
                return
            }
            if (pending == tier) return
            pending = tier
        }
        val app = ctx.applicationContext
        Thread({
            val text = runCatching {
                app.assets.open("rules/$tier.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("")
            val parsed = RuleParser.parseBoth(text)
            // 文件缺失或读坏时不至于一条不拦：退回手工整理的那批核心域名。
            val b = if (parsed.blocked.isEmpty()) {
                BuiltinRules.DOMAINS.toHashSet()
            } else {
                parsed.blocked.toHashSet()
            }
            val a = parsed.allowed.toHashSet()
            synchronized(lock) {
                blocked = b
                allowed = a
                loadedTier = tier
                if (pending == tier) pending = null
            }
            counts[tier] = b.size
            onDone(tier)
        }, "chengliu-rules-$tier").start()
    }
}
