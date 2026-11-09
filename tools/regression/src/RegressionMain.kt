package com.chengliu.app.core

import java.io.File

/**
 * 离线规则回归入口：不需要 Android、不需要手机。
 * 由 tools/regression/run.py 用 K2JVMCompiler 编译 DecisionModel.kt +
 * DecisionCore.kt + 生成的 Samples.kt + 本文件后，以纯 JVM 运行。
 *
 * 检查六件事：是否正确命中、是否误命中正常按钮、是否重复点击、
 * 页面切换后是否继续操作、倒计时结束后是否正常点击、是否影响其他应用。
 */
fun main(args: Array<String>) {
    val out = StringBuilder()
    var pass = 0
    var fail = 0

    out.appendLine("Time Leak 规则回归报告")
    out.appendLine("样本：单点 ${caseList.size} 例 / 整树 ${treeList.size} 例")
    out.appendLine("决策核心：DecisionCore.decide（与 App 运行时同一份代码）")
    out.appendLine("=".repeat(64))

    out.appendLine("")
    out.appendLine("一、单点判定")
    for (c in caseList) {
        val d = DecisionCore.decide(c.facts)
        val okAction = d.action.name == c.expectAction
        val okLevel = d.level.name == c.expectLevel
        val okScore = d.score in c.scoreMin..c.scoreMax
        val ok = okAction && okLevel && okScore
        if (ok) pass++ else fail++
        out.appendLine("${if (ok) "PASS" else "FAIL"}  ${c.id}  ${c.desc}")
        out.appendLine("      动作=${d.action.name}(期望 ${c.expectAction}) " +
            "置信=${d.level.name}(期望 ${c.expectLevel}) 分数=${d.score} " +
            "类型=${d.kind} 规则=${d.rule}")
        if (!ok) out.appendLine("      理由：${d.reasons.joinToString("；")}")
    }

    out.appendLine("")
    out.appendLine("二、整树扫描")
    for (t in treeList) {
        val results = t.nodes.map { it to DecisionCore.decide(it) }
        val clicks = results.filter { it.second.action == DecisionAction.CLICK }
        val clickedTexts = clicks.map { it.first.label }
        val forbidHit = results.filter { (n, d) ->
            n.label in t.forbidTexts && d.action == DecisionAction.CLICK
        }
        val allObserve = t.expectAllObserve &&
            results.all { it.second.action == DecisionAction.OBSERVE }
        val okCount = clicks.size == t.expectClicks
        val okTexts = t.clickedTexts.isEmpty() ||
            t.clickedTexts.all { it in clickedTexts }
        val okForbid = forbidHit.isEmpty()
        val okObserve = !t.expectAllObserve || allObserve
        val ok = okCount && okTexts && okForbid && okObserve
        if (ok) pass++ else fail++
        out.appendLine("${if (ok) "PASS" else "FAIL"}  ${t.id}  ${t.desc}")
        out.appendLine("      点击数=${clicks.size}(期望 ${t.expectClicks}) " +
            "命中文本=$clickedTexts 全观察=$allObserve")
        results.forEach { (n, d) ->
            out.appendLine("      - ${n.label.ifEmpty { "(无文案)" }} -> " +
                "${d.action.name}/${d.level.name}/${d.score} ${d.rule}")
        }
        if (!ok) {
            out.appendLine("      误命中：${forbidHit.map { it.first.label }}")
        }
    }

    out.appendLine("")
    out.appendLine("=".repeat(64))
    out.appendLine("合计：PASS $pass / FAIL $fail")
    out.appendLine(if (fail == 0) "结论：全部通过" else "结论：存在回归失败，请勿发布")

    val text = out.toString()
    print(text)
    if (args.isNotEmpty()) runCatching { File(args[0]).writeText(text, Charsets.UTF_8) }
    if (fail > 0) kotlin.system.exitProcess(1)
}
