package com.chengliu.app.core

import android.content.Context
import android.provider.Settings
import java.util.Locale

/**
 * 系统「私人 DNS」（DoT）。
 *
 * 这一项没法用代码改：`private_dns_mode` 属于 Settings.Global，写它需要
 * WRITE_SECURE_SETTINGS，普通应用拿不到。所以只做三件能做的事：
 *
 * 1. 读准状态，把「现在到底有没有一条绕过过滤的路」讲清楚；
 * 2. 严格模式下把该服务器的地址从接管列表里排除，否则它的 853 会被隧道吞掉。
 *    私人 DNS 没有「失败就退回明文」这一步，被吞掉就等于整机解析停摆 —— 这是本应用
 *    唯一可能把用户网络弄断的地方，必须主动避让，哪怕代价是那几个地址的 53 也放行了；
 * 3. 在自检里做一次真调用，验证系统解析器到底有没有经过本应用。
 */
object PrivateDns {

    enum class Mode { OFF, AUTO, STRICT, UNKNOWN }

    data class State(val mode: Mode, val raw: String, val specifier: String) {
        val strict: Boolean get() = mode == Mode.STRICT && specifier.isNotEmpty()
    }

    fun read(ctx: Context): State = try {
        val resolver = ctx.contentResolver
        val raw = Settings.Global.getString(resolver, "private_dns_mode") ?: "off"
        val spec = Settings.Global.getString(resolver, "private_dns_specifier") ?: ""
        State(parseMode(raw), raw, spec.trim())
    } catch (_: Exception) {
        State(Mode.UNKNOWN, "", "")
    }

    fun parseMode(raw: String): Mode = when (raw.trim().lowercase(Locale.ROOT)) {
        "hostname" -> Mode.STRICT
        "opportunistic" -> Mode.AUTO
        "off" -> Mode.OFF
        else -> Mode.UNKNOWN
    }

    /**
     * 严格模式下这台服务器会用到的地址。只查内置表与字面 IP，**不做网络解析**：
     * 这个方法会在建隧道的主线程上被调用，解析一次就等于凭空多一次卡顿。
     */
    fun addresses(state: State): List<String> {
        if (!state.strict) return emptyList()
        val s = state.specifier.removePrefix("[").removeSuffix("]")
        if (s.isEmpty()) return emptyList()
        if (CaptureRoutes.literal(s) != null) return listOf(s)
        return DohEndpoints.addressesFor(s)
    }

    fun summary(state: State): String = when (state.mode) {
        Mode.OFF -> "已关闭，没有 DoT 旁路。"
        Mode.AUTO ->
            "「自动」模式会尝试对当前网络的 DNS 服务器做 DoT。本应用提供的是虚拟地址、" +
                "没有可验证的证书，系统会退回普通 DNS，查询仍会被正常接管。"
        Mode.STRICT ->
            "已指定「${state.specifier.ifEmpty { "某个服务器" }}」。系统会把它当作唯一解析通道，" +
                "所有查询走 853 端口，本地过滤看不到这些请求。"
        Mode.UNKNOWN -> "读不到系统设置，按「未指定」处理。"
    }

    /** 严格模式下，这些地址必须从接管列表里排除的原因说明；不需要排除时返回 null。 */
    fun exclusionNote(excluded: List<String>): String? {
        if (excluded.isEmpty()) return null
        val addr = excluded.joinToString(" / ")
        return "它的地址（$addr）原本在接管列表里，为避免把 DoT 一起丢弃导致整机解析失败，" +
            "已自动排除。代价是这些地址的明文 53 也一并放行；把私人 DNS 改回「关闭」即可恢复接管。"
    }
}
