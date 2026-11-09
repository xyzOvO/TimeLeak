package com.chengliu.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 「为什么没跳过」的短期记录：**只存内存**，最多 20 条，进程结束即清空。
 *
 * 这是故障诊断的一半：界面不能只显示「运行中」，还得能回答
 * 「刚才那个开屏为什么没点」——是策略禁用、观察模式、证据不足，
 * 还是二次验证发现页面已经变了。
 *
 * 记的是判定结论，控件文案只用于定位，不落盘、不进备份、不上传。
 */
data class Reject(
    val at: Long,
    val pkg: String,
    val appLabel: String,
    val target: String,
    val level: Confidence,
    val kind: AdKind,
    val action: DecisionAction,
    val rule: String,
    val reason: String,
)

object RejectLog {
    private const val MAX = 20

    private val _items = MutableStateFlow<List<Reject>>(emptyList())
    val items: StateFlow<List<Reject>> get() = _items

    @Synchronized
    fun add(r: Reject) {
        // 同一个 App、同一条规则、同一个原因，6 秒内只留一条：
        // 轮询每 700ms 扫一次，不去重会把整页刷成同一条。
        val dup = _items.value.firstOrNull {
            r.at - it.at < 6000 && it.pkg == r.pkg &&
                it.rule == r.rule && it.reason == r.reason
        }
        if (dup != null) return
        _items.value = (listOf(r) + _items.value).take(MAX)
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
    }
}
