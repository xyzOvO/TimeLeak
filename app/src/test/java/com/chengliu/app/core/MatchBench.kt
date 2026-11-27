package com.chengliu.app.core

import org.junit.Test

/**
 * 匹配层微基准（不参与默认单测清单，用 tools/bench/run-bench.ps1 单独跑）。
 *
 * 为什么要有它：跳过广告的耗时几乎全花在「一次扫描里对几百个节点跑一遍规则」，
 * 这段是纯 CPU、纯 Kotlin，可以脱离设备在 JVM 上量。改动匹配层前后各跑一次，
 * 数字摆在那里，比「感觉快了」可靠。
 *
 * 场景按线上订阅的形态构造：一个应用 40 组 × 8 条规则，另有 5 组全局组；
 * 节点 300 个，文案以普通中文为主（绝大多数规则不命中），少量带「跳过」。
 */
class MatchBench {

    private data class Node(val text: String, val desc: String, val id: String, val cls: String)

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun buildSubscription(): String {
        val sb = StringBuilder()
        sb.append("""{"apps":[{"id":"com.example.bench","groups":[""")
        val shapes = listOf(
            """[text*="跳过"]""",
            """[text="跳过"]""",
            """[vid*="skip"]""",
            """[text~="跳过"]""",
            """[text*="广告" && vid*="close"]""",
            """[desc*="关闭"]""",
            """[text^="跳过" && text$="s"]""",
            """[vid="com.example.bench:id/splash_skip"]""",
        )
        val quoted = shapes.map { "\"" + esc(it) + "\"" }
        for (g in 0 until 40) {
            if (g > 0) sb.append(',')
            sb.append("""{"matchTime":9000,"rules":[""")
            for (r in 0 until 8) {
                if (r > 0) sb.append(',')
                sb.append("""{"matches":""").append(quoted[(g + r) % quoted.size]).append('}')
            }
            sb.append("]}")
        }
        sb.append("]}],\"globalGroups\":[")
        for (g in 0 until 5) {
            if (g > 0) sb.append(',')
            sb.append("""{"matchTime":5000,"rules":[""")
            for (r in 0 until 4) {
                if (r > 0) sb.append(',')
                sb.append("""{"matches":""").append(quoted[(g + r) % quoted.size]).append('}')
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun buildNodes(): List<Node> {
        val out = ArrayList<Node>(300)
        // 绝大多数节点是普通正文，规则一个都不命中 —— 这才是真实分布。
        for (i in 0 until 286) {
            out.add(
                Node(
                    text = "第${i}条内容：今天的推荐和昨天一样",
                    desc = "",
                    id = "com.example.bench:id/row_$i",
                    cls = "android.widget.TextView",
                ),
            )
        }
        out.add(Node("跳过", "", "com.example.bench:id/splash_skip", "android.widget.TextView"))
        out.add(Node("跳过 3s", "", "com.example.bench:id/skip", "android.widget.TextView"))
        out.add(Node("", "关闭", "com.example.bench:id/close", "android.widget.ImageView"))
        for (i in 0 until 11) {
            out.add(
                Node(
                    text = "广告介绍$i",
                    desc = "",
                    id = "com.example.bench:id/ad_$i",
                    cls = "android.view.View",
                ),
            )
        }
        return out
    }

    /** 一轮匹配 = 一次扫描里对所有节点做的纯 CPU 判定（不含取树、不含点击）。 */
    private fun oneRound(nodes: List<Node>): Int {
        var hits = 0
        for (n in nodes) {
            if (n.text.isEmpty() && n.desc.isEmpty() && n.id.isEmpty()) continue
            if (SkipEngine.isSafeTarget(n.text, n.desc)) hits += 0
            if (SkipEngine.builtinHit(n.text, n.desc, n.id)) hits++
            if (SkipEngine.scopedHit(
                    "com.example.bench", "com.example.bench.SplashActivity",
                    n.text, n.desc, n.id, 1200L, n.cls,
                ) != null
            ) hits++
        }
        return hits
    }

    /** 单段耗时（预热后取 21 个样本的中位数），用来定位到底哪一段贵。 */
    private fun segment(name: String, rounds: Int, body: () -> Int): Double {
        var sink = 0
        repeat(rounds) { sink += body() }
        val samples = ArrayList<Double>(21)
        for (i in 0 until 21) {
            val t0 = System.nanoTime()
            sink += body()
            samples.add((System.nanoTime() - t0) / 1_000_000.0)
        }
        samples.sort()
        val median = samples[samples.size / 2]
        println("MATCH_BENCH %-8s median=%.3fms sink=%d".format(name, median, sink))
        return median
    }

    @Test
    fun benchMatchingLayer() {
        SkipEngine.builtin = true
        SkipEngine.ensureLoaded(buildSubscription(), "")
        val nodes = buildNodes()
        println(
            "MATCH_BENCH parser groups=${SkipEngine.groupCount()} rules=${SkipEngine.ruleCount()}",
        )

        val safe = segment("safe", 50) {
            var n = 0
            for (x in nodes) if (SkipEngine.isSafeTarget(x.text, x.desc)) n++
            n
        }
        val builtin = segment("builtin", 50) {
            var n = 0
            for (x in nodes) if (SkipEngine.builtinHit(x.text, x.desc, x.id)) n++
            n
        }
        val scoped = segment("scoped", 50) {
            var n = 0
            for (x in nodes) {
                if (SkipEngine.scopedHit(
                        "com.example.bench", "com.example.bench.SplashActivity",
                        x.text, x.desc, x.id, 1200L, x.cls,
                    ) != null
                ) n++
            }
            n
        }
        println(
            "MATCH_BENCH 分段 safe=%.3fms builtin=%.3fms scoped=%.3fms 合计=%.3fms".format(
                safe, builtin, scoped, safe + builtin + scoped,
            ),
        )

        // JIT 预热：前几轮全是解释执行，算进去会把数字夸大好几倍。
        repeat(50) { oneRound(nodes) }

        val samples = ArrayList<Double>(31)
        var lastHits = 0
        for (i in 0 until 31) {
            val t0 = System.nanoTime()
            lastHits = oneRound(nodes)
            samples.add((System.nanoTime() - t0) / 1_000_000.0)
        }
        samples.sort()
        val median = samples[samples.size / 2]
        val p90 = samples[(samples.size * 9) / 10]
        val min = samples.first()

        println("MATCH_BENCH nodes=${nodes.size} hits=$lastHits")
        println("MATCH_BENCH median=%.3fms p90=%.3fms min=%.3fms".format(median, p90, min))
        println(
            "MATCH_BENCH perNode=%.1fus".format(median * 1000.0 / nodes.size),
        )
    }
}
