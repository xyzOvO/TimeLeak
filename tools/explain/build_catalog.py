#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 App 内的域名说明表，并导出「还认不出来」的域名家族清单。

用法：
    python tools/explain/build_catalog.py            # 尝试更新上游争议清单后生成
    python tools/explain/build_catalog.py --offline # 只用本地缓存，完全离线

为什么要这一步：
    规则文件（anti-AD）只给域名，不给「这域名是谁、干嘛的」。说明不能写死在
    App 代码里，否则换一版规则就过期。这里把三份东西合成一张随包发布的表：
      1) tools/explain/seed.tsv —— 人工核实过归属的条目（有公开依据）
      2) anti-AD discretion/{pcdn,dns,anv}.txt —— 上游自己标注的争议域名
      3) 规则文件的 !Source 版本头 —— 写进生成文件，便于核对同步到哪一版
    识别工作全部发生在开发/更新环节：App 运行时只查本地表，不联网、
    不上传任何查询过的域名。

每次更新规则或订阅后：跑本脚本 → 看 unknown_families.txt → 核实后补 seed.tsv
→ 再跑一次 → 构建。这样说明永远跟着当前那版规则走。
"""
import argparse
import pathlib
import re
import sys
import urllib.request
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[2]
RULE_DIR = ROOT / "app/src/main/assets/rules"
SEED = ROOT / "tools/explain/seed.tsv"
UPSTREAM = ROOT / "tools/explain/upstream"
OUT_KT = ROOT / "app/src/main/java/com/chengliu/app/data/GeneratedCatalog.kt"
OUT_UNKNOWN = ROOT / "tools/explain/unknown_families.txt"

KINDS = {"AD", "TRACK", "ATTRIB", "TELEMETRY", "PCDN", "NONAD", "CDN", "FAMILY", "OTHER"}
UPSTREAM_URL = "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/discretion/{}.txt"
# 未覆盖清单只列高频家族；长尾各只有 1~2 条规则，列出来没有可操作性
FAMILY_LIMIT = 800

# 上游三类争议清单：统一说明，但绝不冒充「广告」
DISPUTED = {
    "pcdn": dict(
        kind="PCDN", name="PCDN / P2P 分发域名", owner="见 anti-AD 争议清单",
        what="App 在后台用你的网络带宽，给其他用户分发视频或文件",
        why="这条不是广告。拦它是为了省下被占用的上行流量和电量；放行后部分 App 的加载会更快，代价是继续被当节点用",
        evidence="anti-AD discretion/pcdn.txt（上游明确标注为争议域名）",
    ),
    "dns": dict(
        kind="NONAD", name="App 内置加密 DNS", owner="见 anti-AD 争议清单",
        what="App 不走系统 DNS，自己解析域名（HTTPDNS / DoH / DoT）",
        why="这条不是广告。拦它是为了防止 App 绕过 DNS 广告过滤；但你自己设的私人 DNS 或某些调度功能也可能受影响",
        evidence="anti-AD discretion/dns.txt（上游明确标注为争议域名）",
    ),
    "anv": dict(
        kind="NONAD", name="运营商一键登录", owner="见 anti-AD 争议清单",
        what="App 向运营商查询本机手机号，用来免密登录",
        why="这条不是广告。拦它是防止 App 不经同意取走手机号；放行后「本机号码一键登录」才能用",
        evidence="anti-AD discretion/anv.txt（上游明确标注为争议域名）",
    ),
}

# 常见二级公共后缀，用于把规则域名粗略聚到「注册域家族」
SECOND_LEVEL = {
    "co.uk", "org.uk", "ac.uk", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
    "com.au", "net.au", "edu.au", "co.jp", "ne.jp", "or.jp", "co.kr", "com.br", "com.tr",
    "co.nz", "com.tw", "com.sg", "com.hk", "co.in", "com.mx", "com.za", "co.il", "com.ar",
    "com.pl", "co.id", "com.vn", "com.ph", "com.my", "com.ru", "co.th", "com.es", "com.pt",
    "com.ua", "com.gr", "com.cz", "com.ro", "com.hu", "com.se", "com.no", "com.dk", "com.fi",
    "com.ch", "com.at", "com.be", "com.nl", "com.it", "com.fr", "com.de", "web.id", "or.id",
    "ac.id", "go.id", "com.eg", "com.pk", "com.bd", "co.ke", "com.ng", "com.gh",
}


def log(*a):
    print(*a, file=sys.stdout)


def norm_domain(s):
    """
    把 ||foo.com^ / ||foo.com^$dnstype=A / 纯 foo.com 统一成 foo.com。
    上游清单若哪天改用 ABP 写法，这里也能照常解析；无效行返回空串。
    """
    d = s.strip().lower()
    if d.startswith("@@"):  # 放行规则不参与说明
        return ""
    d = d.lstrip("|")
    if "$" in d:
        d = d.split("$", 1)[0]
    d = d.rstrip("^").strip()
    if not d or "." not in d or any(c in d for c in "*? /\\\t"):
        return ""
    return d


def read_rules():
    """读三档规则，返回 (拦截域名集合, 规则版本字符串)。放行规则不参与说明。"""
    domains, sources = set(), set()
    for tier in ("weak", "medium", "strong"):
        p = RULE_DIR / f"{tier}.txt"
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8-sig").splitlines():
            l = line.strip()
            if not l:
                continue
            if l.startswith("!Source:"):
                sources.add(l.split(":", 1)[1].strip())
                continue
            if l[0] in "!#" or l.startswith("@@"):
                continue
            d = norm_domain(l)
            if d:
                domains.add(d)
    # 取最长的时间戳当版本号，形如 anti-AD 20261105074937
    stamp = ""
    for s in sources:
        m = re.search(r"\d{14}", s)
        if m and m.group() > stamp:
            stamp = m.group()
    return domains, stamp


def read_seed():
    """人工核实过的条目。每行 7 列，Tab 分隔。"""
    out = []
    for raw in SEED.read_text(encoding="utf-8-sig").splitlines():
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) != 7:
            raise SystemExit(f"seed.tsv 必须正好 7 列（当前 {len(cols)}）：{line[:80]}")
        suffix, name, owner, kind, what, why, evidence = [c.strip() for c in cols]
        if kind not in KINDS:
            raise SystemExit(f"seed.tsv 未知分类 {kind}：{suffix}")
        norm = norm_domain(suffix)
        if not norm or norm != suffix.lower():
            raise SystemExit(f"seed.tsv 后缀必须是纯域名（不要 || ^ $）：{suffix}")
        if not all((name, owner, what, why, evidence)):
            raise SystemExit(f"seed.tsv 有空字段，界面会显示不出依据：{suffix}")
        out.append(dict(suffix=norm, name=name, owner=owner, kind=kind,
                        what=what, why=why, evidence=evidence, group="seed"))
    return out


def read_upstream(offline):
    """上游争议域名。默认尝试拉最新版；失败或 --offline 时回退本地缓存。"""
    out, fresh, failed = [], [], []
    for tag, meta in DISPUTED.items():
        path = UPSTREAM / f"{tag}.txt"
        text = None
        if not offline:
            try:
                with urllib.request.urlopen(UPSTREAM_URL.format(tag), timeout=8) as r:
                    text = r.read().decode("utf-8")
                path.write_text(text, encoding="utf-8", newline="\n")
                fresh.append(tag)
            except Exception as e:
                failed.append(f"{tag}({type(e).__name__})")
                text = None
        if text is None and path.exists():
            text = path.read_text(encoding="utf-8-sig")
        if text is None:
            log(f"  ! 上游 {tag}.txt 拿不到且无缓存，跳过")
            continue
        for line in text.splitlines():
            l = line.strip()
            if not l or l.startswith("#"):
                continue
            d = norm_domain(l)
            if d:
                out.append(dict(suffix=d, group=tag, **meta))
    return out, fresh, failed


def registered(domain):
    p = domain.split(".")
    if len(p) <= 2:
        return domain
    if ".".join(p[-2:]) in SECOND_LEVEL and len(p) >= 3:
        return ".".join(p[-3:])
    return ".".join(p[-2:])


def covered(domain, suffixes):
    """最长后缀匹配：只要某个已登记后缀是它的父域，就算覆盖。"""
    cursor = domain
    while cursor:
        if cursor in suffixes:
            return True
        if "." not in cursor:
            return False
        cursor = cursor.split(".", 1)[1]
    return False


def kt(s):
    return (s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
             .replace("\n", "\\n").replace("\r", ""))


def generate(entries, stamp, domains):
    lines = [
        "package com.chengliu.app.data",
        "",
        "// 本文件由 tools/explain/build_catalog.py 生成，请勿手工修改。",
        f"// 规则版本：{stamp or '未知'}",
        f"// 条目：{len(entries)}（人工核实 "
        f"{sum(1 for e in entries if e['group'] == 'seed')} + 上游争议标注 "
        f"{sum(1 for e in entries if e['group'] != 'seed')}）",
        "// 说明只写有公开依据的归属；查不到的一律交给 ServiceExplainer 按形态说明，不编造。",
        "",
        "object GeneratedCatalog {",
        f'    const val RULE_SOURCE = "{kt(stamp)}"',
        "",
        "    val ALL: List<ServiceInfo> = listOf(",
    ]
    for i, e in enumerate(entries):
        lines += [
            "        ServiceInfo(",
            f'            key = "gen-{i}-{kt(e["suffix"])}",',
            f'            name = "{kt(e["name"])}",',
            f'            owner = "{kt(e["owner"])}",',
            f'            kind = ServiceKind.{e["kind"]},',
            f'            what = "{kt(e["what"])}",',
            f'            why = "{kt(e["why"])}",',
            f'            suffixes = listOf("{kt(e["suffix"])}"),',
            f'            evidence = "{kt(e["evidence"])}",',
            "        ),",
        ]
    lines += [
        "    )",
        "",
        "    private val index: Map<String, ServiceInfo> = buildMap {",
        "        for (s in ALL) for (x in s.suffixes) put(x.lowercase(), s)",
        "    }",
        "",
        "    /** 从最具体的子域往上找，最长后缀优先。 */",
        "    fun lookup(domain: String): ServiceInfo? {",
        "        var cursor = domain.trim().trimEnd('.').lowercase()",
        "        while (cursor.isNotEmpty()) {",
        "            index[cursor]?.let { return it }",
        "            val dot = cursor.indexOf('.')",
        "            if (dot < 0) return null",
        "            cursor = cursor.substring(dot + 1)",
        "        }",
        "        return null",
        "    }",
        "",
        "    fun size(): Int = index.size",
        "}",
        "",
    ]
    OUT_KT.write_text("\n".join(lines), encoding="utf-8", newline="\n")

    suffixes = {e["suffix"] for e in entries}
    families = defaultdict(list)
    for d in domains:
        r = registered(d)
        if not covered(d, suffixes):
            families[r].append(d)
    ranked = sorted(families.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    covered_n = len(domains) - sum(len(v) for v in families.values())
    head = [
        f"# 未覆盖的域名家族（对应规则版本 {stamp or '未知'}）",
        f"# 规则域名 {len(domains)} 条，已被说明表覆盖 {covered_n} 条，"
        f"未覆盖家族 {len(families)} 个（涉及 {len(domains) - covered_n} 条规则）",
        f"# 按该家族下的规则条数降序，只列前 {FAMILY_LIMIT} 个高频家族（其余是长尾）。",
        "# 每次更新规则/订阅后读这份清单，挑高频的核实归属补进 seed.tsv，再跑一次 build_catalog.py。",
        "# 注意：查不到公开归属的随机域名不要硬编公司名；宽后缀下的特殊子域要单独登记。",
        "",
    ]
    body = []
    for r, ds in ranked[:FAMILY_LIMIT]:
        sample = " ".join(sorted(ds)[:3])
        body.append(f"{len(ds):6d}\t{r}\t{sample}")
    OUT_UNKNOWN.write_text("\n".join(head + body) + "\n", encoding="utf-8", newline="\n")
    return covered_n, len(domains), len(families)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="不联网，只用缓存的上游清单")
    args = ap.parse_args()

    domains, stamp = read_rules()
    seed = read_seed()
    upstream, fresh, failed = read_upstream(args.offline)

    # 去重：人工核实优先，其次按 pcdn > dns > anv
    seen, entries = set(), []
    for e in seed + upstream:
        if e["suffix"] in seen:
            continue
        seen.add(e["suffix"])
        entries.append(e)

    n_seed = sum(1 for e in entries if e["group"] == "seed")
    covered_n, total, families = generate(entries, stamp, domains)
    upstream_note = f"已更新 {'/'.join(fresh)}" if fresh else "使用本地缓存"
    if failed:
        upstream_note += f"（在线拉取失败：{'/'.join(failed)}）"
    log(f"规则版本      : {stamp or '未知'}")
    log(f"规则域名      : {total} 条")
    log(f"说明表条目    : {len(entries)}（人工 {n_seed}，上游争议 {len(entries) - n_seed}）")
    log(f"上游清单      : {upstream_note}")
    log(f"覆盖规则      : {covered_n} 条（{100 * covered_n / max(total, 1):.2f}%）")
    log(f"未覆盖家族    : {families} 个 → {OUT_UNKNOWN.relative_to(ROOT)}")
    log(f"生成          : {OUT_KT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
