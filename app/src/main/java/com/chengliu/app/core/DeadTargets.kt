package com.chengliu.app.core

/**
 * 「点了没反应」的控件黑名单。
 *
 * 同一个 (包名, viewId) 在有效期内不再点击：一是别把每次会话两次的点击额度
 * 浪费在假按钮上，二是被拉黑之后 [SkipAdService] 会改用「同容器无文字图标」
 * 作为补位候选。
 *
 * 纯逻辑、零 Android 依赖。
 */
object DeadTargets {

    const val MAX = 64

    /** 六小时后自动放行：应用升级换了布局，旧结论不该一直生效。 */
    const val TTL_MS = 6 * 60 * 60 * 1000L

    private val at = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX
    }

    private fun key(pkg: String?, viewId: String?): String? {
        if (pkg.isNullOrEmpty() || viewId.isNullOrEmpty()) return null
        // 无 id 的自绘控件用几何位置当键（geom@l,t,r,b），同样允许拉黑。
        if (!viewId.contains(":id/") && !viewId.startsWith("geom@")) return null
        return "$pkg|$viewId"
    }

    @Synchronized
    fun remember(pkg: String?, viewId: String?, now: Long) {
        val k = key(pkg, viewId) ?: return
        at[k] = now
    }

    @Synchronized
    fun isDead(pkg: String?, viewId: String?, now: Long): Boolean {
        val k = key(pkg, viewId) ?: return false
        val t = at[k] ?: return false
        if (now - t > TTL_MS) {
            at.remove(k)
            return false
        }
        return true
    }

    @Synchronized
    fun clear() {
        at.clear()
    }

    @Synchronized
    fun size(): Int = at.size
}
