package com.chengliu.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 扫描心跳：**每次扫描都留一条摘要，不管有没有命中**。
 *
 * 之前只有「识别到目标并走到判定」才会写记录，于是观察页空着的时候，
 * 用户无法区分三种完全不同的情况：根本没扫、扫了但节点树是空的、
 * 扫到了节点但没有一个符合候选条件。这个类专门补这一段。
 *
 * 只记录结构性信息（包名、节点数、候选数、窗口类型、结论），
 * **不记录任何界面文字**，不落盘、不进备份、不上传。
 */
enum class BeatStage(val label: String) {
    SPLASH("开屏窗"),
    POPUP("弹窗轮"),
    NORMAL("普通页"),
    SKIPPED("未扫描"),
}

enum class BeatResult(val label: String) {
    HIT("命中"),
    NO_TREE("拿不到节点树"),
    EMPTY("节点树为空"),
    NO_CANDIDATE("没有候选"),
    POLICY("策略拦截"),
    THROTTLED("被节流合并"),
    OUTSIDE("未开启弹窗轮"),
    TEXT_OVER("文本窗已过"),
    BUSY("用户正在操作"),
    SELF("系统或本应用"),
    DONE("扫完未命中"),
}

data class ScanBeat(
    val at: Long,
    val pkg: String,
    val appLabel: String,
    val nodes: Int,
    val candidates: Int,
    val stage: BeatStage,
    val result: BeatResult,
    val note: String,
)

object ScanLog {
    private const val MAX = 40

    /** 同一应用同一个结论，这段时间内只保留最新一条，避免轮询刷屏。 */
    private const val SAME_RESULT_MS = 10000L

    /** 去重只看最近这些条：A/B/C 交错触发时，同一个应用仍能合并回原位。 */
    private const val LOOK_BACK = 12

    private val _items = MutableStateFlow<List<ScanBeat>>(emptyList())
    val items: StateFlow<List<ScanBeat>> get() = _items

    @Synchronized
    fun add(b: ScanBeat) {
        // 「被节流合并」只说明这一轮没扫成，信息量最低。10 秒内全局只留一条，
        // 否则连着切几个应用，列表前几屏全是它。
        if (b.result == BeatResult.THROTTLED) {
            val idx = _items.value.indexOfFirst {
                it.result == BeatResult.THROTTLED && b.at - it.at < SAME_RESULT_MS
            }
            if (idx >= 0) {
                _items.value = _items.value.toMutableList().also { it[idx] = b }
                return
            }
        }

        // 事件包名会在多个应用之间来回跳（切窗口那一瞬间尤其明显），
        // 只跟队首比会把 A/B/C 各插一条，列表立刻被刷满。
        // 这里在最近若干条里找同一个应用、同一个结论的记录，有就原地更新。
        val lookBack = _items.value.take(LOOK_BACK)
        val idx = lookBack.indexOfFirst {
            it.pkg == b.pkg && it.result == b.result && b.at - it.at < SAME_RESULT_MS
        }
        if (idx >= 0) {
            _items.value = _items.value.toMutableList().also { it[idx] = b }
            return
        }
        _items.value = (listOf(b) + _items.value).take(MAX)
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
    }
}
