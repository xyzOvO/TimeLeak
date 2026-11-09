#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Time Leak 离线规则回归驱动。

不需要 Android SDK、不需要真机：把项目里**真正运行时使用的**纯 Kotlin
决策核心（DecisionModel.kt / DecisionCore.kt）与样本一起用 Gradle 缓存里的
Kotlin 编译器编译成 JVM 字节码，再跑一遍断言。权重、阈值、闸门逻辑全部
来自生产代码，脚本只负责生成样本代码与汇总报告，不复刻任何判定逻辑。

用法： python tools/regression/run.py
产物： tools/regression/report.txt
退出码：0 = 全部通过；1 = 有回归失败；2 = 环境/编译问题
"""
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CORE = os.path.join(ROOT, "app", "src", "main", "java", "com", "chengliu", "app", "core")
SAMPLES = os.path.join(HERE, "samples.json")
REPORT = os.path.join(HERE, "report.txt")

GRADLE_CACHE = os.path.join(
    os.environ.get("GRADLE_USER_HOME", os.path.join(os.path.expanduser("~"), ".gradle")),
    "caches", "modules-2", "files-2.1",
)
JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME", ""),
    r"D:\livetoolchain\jdk\jdk-17.0.21.0.2+1",
]

ENUM_FIELDS = {
    "reason": "HitReason",
    "kind": "AdKind",
    "mode": "PolicyMode",
}
LONG_FIELDS = {"windowAgeMs"}


def kstr(s):
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def klist(values):
    if not values:
        return "emptyList()"
    return "listOf(" + ", ".join(kstr(v) for v in values) + ")"


def facts_expr(d):
    parts = []
    for key, value in d.items():
        if key in ENUM_FIELDS:
            parts.append(f"{key} = {ENUM_FIELDS[key]}.{value}")
        elif isinstance(value, bool):
            parts.append(f"{key} = {str(value).lower()}")
        elif key in LONG_FIELDS:
            parts.append(f"{key} = {int(value)}L")
        elif isinstance(value, int):
            parts.append(f"{key} = {value}")
        else:
            parts.append(f"{key} = {kstr(value)}")
    return "NodeFacts(" + ", ".join(parts) + ")"


def generate_samples(data):
    lines = [
        "// 由 tools/regression/run.py 从 samples.json 自动生成，请勿手改。",
        "package com.chengliu.app.core",
        "",
        "internal data class Case(",
        "    val id: String, val desc: String, val facts: NodeFacts,",
        "    val expectAction: String, val expectLevel: String,",
        "    val scoreMin: Int = 0, val scoreMax: Int = 100,",
        ")",
        "",
        "internal data class TreeCase(",
        "    val id: String, val desc: String, val nodes: List<NodeFacts>,",
        "    val expectClicks: Int, val clickedTexts: List<String>,",
        "    val forbidTexts: List<String>, val expectAllObserve: Boolean = false,",
        ")",
        "",
        "internal val caseList: List<Case> = listOf(",
    ]
    for c in data["cases"]:
        lines.append(
            "    Case(%s, %s, %s, %s, %s, %d, %d)," % (
                kstr(c["id"]), kstr(c["desc"]), facts_expr(c["facts"]),
                kstr(c["expectAction"]), kstr(c["expectLevel"]),
                int(c.get("scoreMin", 0)), int(c.get("scoreMax", 100)),
            )
        )
    lines.append(")")
    lines.append("")
    lines.append("internal val treeList: List<TreeCase> = listOf(")
    for t in data["trees"]:
        nodes = "listOf(" + ", ".join(facts_expr(n) for n in t["nodes"]) + ")"
        lines.append(
            "    TreeCase(%s, %s, %s, %d, %s, %s, %s)," % (
                kstr(t["id"]), kstr(t["desc"]), nodes,
                int(t["expectClicks"]), klist(t.get("clickedTexts", [])),
                klist(t.get("forbidTexts", [])),
                "true" if t.get("expectAllObserve") else "false",
            )
        )
    lines.append(")")
    return "\n".join(lines) + "\n"


def find_jar(pattern):
    hits = glob.glob(os.path.join(GRADLE_CACHE, pattern))
    return sorted(hits)[-1] if hits else None


def java_bin():
    for base in JDK_CANDIDATES:
        if not base:
            continue
        for name in ("java.exe", "java"):
            p = os.path.join(base, "bin", name)
            if os.path.isfile(p):
                return p
    return shutil.which("java")


def main():
    java = java_bin()
    if not java:
        print("找不到 java，请设置 JAVA_HOME", file=sys.stderr)
        return 2
    jars = {
        "compiler": find_jar("org.jetbrains.kotlin/kotlin-compiler-embeddable/*/*/*.jar"),
        "stdlib": find_jar("org.jetbrains.kotlin/kotlin-stdlib/1.9.25/*/*.jar"),
        "script": find_jar("org.jetbrains.kotlin/kotlin-script-runtime/1.9.25/*/*.jar"),
        "reflect": find_jar("org.jetbrains.kotlin/kotlin-reflect/1.9.25/*/*.jar"),
        "daemon": find_jar("org.jetbrains.kotlin/kotlin-daemon-embeddable/1.9.25/*/*.jar"),
        "trove": find_jar("org.jetbrains.intellij.deps/trove4j/*/*/*.jar"),
        # 编译器后端生成 @NotNull 时需要，缺了会抛 NoClassDefFoundError
        "annotations": find_jar("org.jetbrains/annotations/23.0.0/*/*.jar"),
    }
    missing = [k for k in ("compiler", "stdlib") if not jars[k]]
    if missing:
        print("Gradle 缓存缺少：%s（%s）" % (", ".join(missing), GRADLE_CACHE), file=sys.stderr)
        return 2

    with open(SAMPLES, "r", encoding="utf-8") as f:
        data = json.load(f)
    if data.get("schema") != 1:
        print("样本格式版本不兼容", file=sys.stderr)
        return 2

    # 用纯英文临时目录，规避中文路径下 JVM 类加载的历史坑。
    work = tempfile.mkdtemp(prefix="tl_regression_")
    try:
        src = os.path.join(work, "src")
        out = os.path.join(work, "classes")
        os.makedirs(src, exist_ok=True)
        os.makedirs(out, exist_ok=True)
        for name in ("DecisionModel.kt", "DecisionCore.kt"):
            shutil.copyfile(os.path.join(CORE, name), os.path.join(src, name))
        shutil.copyfile(
            os.path.join(HERE, "src", "RegressionMain.kt"),
            os.path.join(src, "RegressionMain.kt"),
        )
        with open(os.path.join(src, "Samples.kt"), "w", encoding="utf-8") as f:
            f.write(generate_samples(data))

        cp = os.pathsep.join(v for v in jars.values() if v)
        sources = [
            os.path.join(src, n)
            for n in ("DecisionModel.kt", "DecisionCore.kt", "Samples.kt", "RegressionMain.kt")
        ]
        compile_cmd = [
            java, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-jvm-target", "17", "-nowarn",
            "-classpath", jars["stdlib"],
            "-d", out,
        ] + sources
        r = subprocess.run(compile_cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if r.returncode != 0:
            print("Kotlin 编译失败：\n" + (r.stdout or "") + (r.stderr or ""), file=sys.stderr)
            return 2
        warnings = [l for l in (r.stdout or "").splitlines() if "error:" in l.lower()]
        if warnings:
            print("编译器报错：\n" + "\n".join(warnings), file=sys.stderr)
            return 2

        report_tmp = os.path.join(work, "report.txt")
        run_cmd = [
            java, "-cp", os.pathsep.join([out, jars["stdlib"]]),
            "com.chengliu.app.core.RegressionMainKt", report_tmp,
        ]
        r = subprocess.run(run_cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        text = ""
        if os.path.isfile(report_tmp):
            with open(report_tmp, "r", encoding="utf-8") as f:
                text = f.read()
        else:
            text = (r.stdout or "") + (r.stderr or "")
        with open(REPORT, "w", encoding="utf-8") as f:
            f.write(text)
        print(text)
        print("报告已写入：" + REPORT)
        return r.returncode
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
