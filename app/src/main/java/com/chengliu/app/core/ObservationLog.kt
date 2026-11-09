package com.chengliu.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 安全观察模式的记录：**只存内存**，进程结束即清空。
 *
 * 这里会出现界面上的按钮文字和控件 id，所以刻意不落盘、不进备份、
 * 不进任何网络请求。界面会写明「仅本次运行可见」。
 */
data class Observation(
    val at: Long,
    val pkg: String,
    val appLabel: String,
    val text: String,
    val desc: String,
    val id: String,
    val clickable: Boolean,
    val rule: String,
    val score: Int,
    val level: Confidence,
    val kind: AdKind,
    val action: DecisionAction,
    val reasons: List<String>,
)

object ObservationLog {
    private const val MAX = 30

    private val _items = MutableStateFlow<List<Observation>>(emptyList())
    val items: StateFlow<List<Observation>> get() = _items

    @Synchronized
    fun add(o: Observation) {
        val now = o.at
        // 同一个目标的倒计时文本每秒都在变，按 规则+包名 在 4 秒内去重，
        // 否则观察页会被同一个按钮刷满。
        val duplicate = _items.value.firstOrNull {
            now - it.at < 4000 && it.pkg == o.pkg && it.rule == o.rule
        }
        if (duplicate != null) {
            _items.value = _items.value.map { if (it === duplicate) o else it }
            return
        }
        _items.value = (listOf(o) + _items.value).take(MAX)
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
    }
}
