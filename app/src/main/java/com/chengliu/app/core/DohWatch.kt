package com.chengliu.app.core

/**
 * 本进程里见过的「疑似加密 DNS 域名」。
 *
 * 应用自带 DoH 时，它必须先把自己的 DoH 服务器解析成一个 IP —— 这一步通常走系统 DNS，
 * 于是我们会看到 `dns.xxx.com` 这类查询；而真正的 TLS 连接去的是表外地址，隧道根本看不见。
 * 既然看不见连接，就至少把这个域名记下来，让用户知道「有东西在用加密 DNS」，
 * 并且可以一键把它的地址纳入接管（纳管后 443/853 会被丢弃，应用要么退回系统 DNS 被过滤，
 * 要么直接失败 —— 这一点必须在界面上说清楚，不能假装没有代价）。
 *
 * 只在内存里保留最近 32 个名字，不落盘、不带任何查询内容。
 */
object DohWatch {

    data class Hit(val name: String, val count: Int)

    private const val MAX = 32
    private val map = LinkedHashMap<String, Int>(16)
    @Volatile
    private var total = 0

    fun observe(name: String) {
        val key = name.trim().trimEnd('.').lowercase()
        if (key.isEmpty()) return
        synchronized(map) {
            val old = map[key]
            if (old != null) {
                map[key] = old + 1
            } else {
                if (map.size >= MAX) {
                    val first = map.keys.firstOrNull() ?: return
                    map.remove(first)
                }
                map[key] = 1
            }
            total++
        }
    }

    fun snapshot(): List<Hit> = synchronized(map) {
        map.entries.sortedByDescending { it.value }.map { Hit(it.key, it.value) }
    }

    fun clear() {
        synchronized(map) {
            map.clear()
            total = 0
        }
    }

    /** 接管成功后把这一条移出列表，不必把所有记录一起清空。 */
    fun forget(name: String) {
        val key = name.trim().trimEnd('.').lowercase()
        synchronized(map) { map.remove(key) }
    }

    val size: Int get() = synchronized(map) { map.size }

    val seen: Int get() = total
}
