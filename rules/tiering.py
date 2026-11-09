# -*- coding: utf-8 -*-
"""把 anti-AD 全量规则按语义确定性分成 弱 / 中 / 强 三档。

分级原则：
  弱：只保留「整域就是广告平台」的域名，零误伤，装上感觉不到副作用
  中：弱 + 明确的统计 / 埋点 / 归因 / 推送 SDK 子域
  强：全量 anti-AD（精确子域表，误伤已经很低的集合）
三档是包含关系，例外规则（@@）全部保留。
"""
import re
import os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "filter.txt")
OUT = os.path.dirname(os.path.abspath(__file__))

raw = open(SRC, encoding="utf-8", errors="ignore").read().splitlines()
rules = [l.strip() for l in raw if l.strip() and not l.startswith("!")]

dom_rules, except_rules, other_rules = [], [], []
for l in rules:
    if l.startswith("||"):
        dom_rules.append(l)
    elif l.startswith("@@"):
        except_rules.append(l)
    else:
        other_rules.append(l)


def host(l):
    m = re.match(r"^\|\|([^\^/]+)\^?", l)
    return m.group(1).lower() if m else ""


# ---------- 弱档：整域即广告平台 ----------
# 只收「这个域名本身就是广告/追踪服务，不存在正常业务用途」的主域。
WEAK_SUFFIX = {
    # 国际广告交易 / DSP / SSP
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net", "taboola.com",
    "outbrain.com", "pubmatic.com", "rubiconproject.com", "magnite.com",
    "openx.net", "casalemedia.com", "adform.net", "smartadserver.com",
    "33across.com", "sharethrough.com", "teads.tv", "spotxchange.com",
    "freewheel.tv", "moatads.com", "doubleverify.com", "adsafeprotected.com",
    "innovid.com", "tremorhub.com", "undertone.com", "yieldmo.com",
    "sovrn.com", "lijit.com", "gumgum.com",
    # 国际移动广告 SDK
    "applovin.com", "unityads.unity3d.com", "vungle.com", "liftoff.io",
    "adcolony.com", "chartboost.com", "inmobi.com", "ironsrc.com",
    "ironsource.com", "mopub.com", "fyber.com", "supersonicads.com",
    "tapjoy.com", "digitalturbine.com", "mintegral.com", "pangle.io",
    # 国内广告平台
    "pos.baidu.com", "cpro.baidu.com", "union.baidu.com", "mobads.baidu.com",
    "mobads-logs.baidu.com", "tanx.com", "alimama.com", "mmstat.com",
    "simba.taobao.com", "adash.m.taobao.com", "adashx.m.taobao.com",
    "gdt.qq.com", "e.qq.com", "adping.qq.com", "adrdir.qq.com",
    "pgdt.gtimg.cn", "adsmind.gdtimg.com", "pingfore.qq.com", "pingjs.qq.com",
    "pangolin-sdk-toutiao.com", "pglstatp-toutiao.com",
    "ads.byteoversea.com", "ads.toutiao.com",
    "cnzz.com", "umeng.com", "umengcloud.com", "uyunad.com",
    "talkingdata.com", "talkingdata.net", "growingio.com", "sensorsdata.cn",
}

# ---------- 中档追加：统计 / 埋点 / 归因 / 推送 的语义段 ----------
# 按「点分段」判断，避免 download / shadow 这类子串误伤。
MID_SEGMENTS = {
    "ad", "ads", "adx", "adserver", "adservice", "adservices", "adsystem",
    "adclick", "adtrack", "adview", "adzone", "adbanner", "adrequest",
    "analytics", "analytic", "track", "tracker", "tracking", "trackers",
    "stat", "stats", "statistic", "statistics", "telemetry", "metrics",
    "metric", "measurable", "beacon", "collect", "collection", "impression",
    "impressions", "pixel", "pixels", "attribution", "conversion",
    "eventlog", "eventlogs", "logserver", "report", "reports",
}
# 归因 / 推送 / 崩溃 SDK：整域收进来
MID_SUFFIX = {
    "appsflyer.com", "adjust.com", "adjust.io", "branch.io", "kochava.com",
    "tenjin.io", "singular.net", "appmetrica.yandex.com", "adbrix.com",
    "getui.com", "igexin.com", "jpush.cn", "jiguang.cn", "mob.com",
    "crashlytics.com", "bugly.qq.com", "umeng.co",
}
# 明确不拦的段：避免把正常业务打断
SAFE_SEGMENTS = {
    "update", "updates", "upgrade", "download", "downloads", "login",
    "logout", "auth", "oauth", "pay", "payment", "push", "upload",
    "cdn", "static", "img", "image", "images", "video", "media",
}


def segments(d):
    return set(d.split("."))


def is_weak(d):
    return any(d == s or d.endswith("." + s) for s in WEAK_SUFFIX)


def is_mid(d):
    if is_weak(d):
        return True
    if any(d == s or d.endswith("." + s) for s in MID_SUFFIX):
        return True
    segs = segments(d)
    if segs & SAFE_SEGMENTS:
        return False
    return bool(segs & MID_SEGMENTS)


weak = [l for l in dom_rules if is_weak(host(l))]
mid = [l for l in dom_rules if is_mid(host(l))]
strong = list(dom_rules)


def write_tier(name, title, desc, doms, with_exceptions):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"!Title: Time Leak 分级规则 · {title}\n")
        f.write(f"!Description: {desc}\n")
        f.write(f"!Source: anti-AD 20261105074937 (https://github.com/privacy-protection-tools/anti-AD)\n")
        f.write(f"!Rules: {len(doms)}\n")
        f.write("!\n")
        for l in with_exceptions:
            f.write(l + "\n")
        for l in doms:
            f.write(l + "\n")
    return path


p1 = write_tier("weak.txt", "弱档", "只含整域广告平台，零误伤", weak, except_rules)
p2 = write_tier("medium.txt", "中档", "弱档 + 统计/埋点/归因/推送 SDK 子域", mid, except_rules)
p3 = write_tier("strong.txt", "强档", "anti-AD 全量精确子域表", strong, except_rules)

print(f"弱档  {len(weak):6d} 条  -> {p1}")
print(f"中档  {len(mid):6d} 条  -> {p2}")
print(f"强档  {len(strong):6d} 条  -> {p3}")
print(f"例外  {len(except_rules):6d} 条（三档都带）")
print()
print("包含关系检查：", set(weak) <= set(mid) <= set(strong))
