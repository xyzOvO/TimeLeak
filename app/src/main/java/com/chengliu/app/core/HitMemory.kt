package com.chengliu.app.core

/**
 * 「上一次在这一页点中过谁」的极简记忆：包名 → 控件的 viewId。
 *
 * 只记 viewId，不记坐标：坐标会随素材、分辨率、横竖屏漂，而控件 id 通常跟着广告模板不变。
 * [SkipAdService] 的快路径按 id 直取节点，省掉整棵树遍历（真机实测 110ms 左右）；
 * 取到的仍是真节点，照样要过全部判定与点击前的二次验证——
 * 所以这里记错/记旧了，最坏结果只是「没命中，退回完整扫描」。
 */
object HitMemory {
    /** 最多记多少个包名；超出按最近最少使用淘汰。 */
    private const val MAX = 64

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX
    }

    /** 记下一次成功点击的控件 id；不像 viewId 的串直接忽略。 */
    @Synchronized
    fun remember(pkg: String?, viewId: String?) {
        if (pkg.isNullOrEmpty() || viewId.isNullOrEmpty()) return
        if (!viewId.contains(":id/")) return
        map[pkg] = viewId
    }

    @Synchronized
    fun viewIdFor(pkg: String?): String? = if (pkg.isNullOrEmpty()) null else map[pkg]

    /** 这一版页面里没有那个控件（或查得太慢）时忘掉它，下一轮回到完整扫描。 */
    @Synchronized
    fun forget(pkg: String?) {
        if (pkg.isNullOrEmpty()) return
        map.remove(pkg)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}
