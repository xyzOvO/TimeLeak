package com.chengliu.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/** 进程内共享的运行状态；服务与界面各取同一份。 */
object VpnState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> get() = _running

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    /**
     * 部分机型会忽略「只路由 DNS 地址」的设置，把全部流量塞进 tun。
     * 那样非 DNS 包无处转发就会断网，必须让用户看见，而不是一脸懵。
     */
    private val _foreign = MutableStateFlow(false)
    val foreign: StateFlow<Boolean> get() = _foreign
    private val foreignCount = AtomicInteger(0)

    fun setRunning(v: Boolean) {
        _running.value = v
    }

    fun setError(msg: String?) {
        _error.value = msg
    }

    fun resetForeign() {
        foreignCount.set(0)
        _foreign.value = false
    }

    fun markForeign() {
        if (foreignCount.incrementAndGet() >= 20) _foreign.value = true
    }
}
