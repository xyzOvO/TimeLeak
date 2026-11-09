package com.chengliu.app.core

/**
 * 扩展位：当前版本只实现「只接管 DNS」。
 *
 * 若将来要做按应用断网 / 全流量防火墙，只需：
 *   1) 让 Builder 改成默认路由并建立完整 IP 转发通道；
 *   2) 新增一个 UidAwareDnsHandler 实现本接口，在 intercept 里按调用方 UID 决策。
 * 规则引擎、上游解析器、界面与统计都不用改。
 */
interface DnsHandler {
    /** 返回非空 = 本地直接应答；返回 null = 需要转发给上游。 */
    fun intercept(query: DnsWire.Query, raw: ByteArray): ByteArray?
}

class BlocklistDnsHandler(
    private val engine: RuleEngine,
    /** 参数依次是：实际查询的域名、命中的规则、规则来源。 */
    private val onBlocked: (name: String, rule: String, source: RuleSource) -> Unit,
) : DnsHandler {
    override fun intercept(query: DnsWire.Query, raw: ByteArray): ByteArray? {
        val verdict = engine.decide(query.name) ?: return null
        onBlocked(query.name, verdict.rule, verdict.source)
        return DnsWire.nxDomain(raw, query)
    }
}
