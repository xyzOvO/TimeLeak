package com.chengliu.app.data

/** 四类用途。分类词会直接显示在界面上，所以写成人话而不是术语。 */
enum class ServiceKind(val label: String) {
    AD("广告投放"),
    TRACK("统计追踪"),
    ATTRIB("安装归因"),
    TELEMETRY("设备上报"),
    PCDN("带宽共享"),
    NONAD("争议拦截"),
    CDN("公共 CDN"),
    FAMILY("厂商域名族"),
    OTHER("未收录"),
}

/**
 * 一条「这个域名是谁、干什么、为什么该拦」的说明。
 *
 * what / why 都刻意写成一句人话：用户看到的应该是「它做什么」，
 * 而不是「域名为 doubleclick.net」这种只有工程师看得懂的东西。
 * evidence 不能为空：卡片会把它显示成「资料依据」，没有依据的条目
 * 应当交给 ServiceExplainer 的推断层，而不是混进这张归属表里。
 */
data class ServiceInfo(
    val key: String,
    val name: String,
    val owner: String,
    val kind: ServiceKind,
    val what: String,
    val why: String,
    val suffixes: List<String>,
    /** 这条归属说明的公开依据；空表示「按形态推断，无归属资料」。 */
    val evidence: String = "",
)

/**
 * 域名 → 服务说明的对照表。
 *
 * 只收录能被公开资料确认归属、且用途单一（广告 / 统计 / 归因 / 上报）的域名；
 * 登录、支付、推送、系统更新这类混合用途的域名一律不收，避免在界面上说错话。
 * 命中的是「最长后缀」，所以 adservice.google.com 不会被泛化的 google.com 抢走。
 */
object ServiceCatalog {

    private fun e(
        key: String,
        name: String,
        owner: String,
        kind: ServiceKind,
        what: String,
        why: String,
        vararg suffixes: String,
    ) = ServiceInfo(
        key, name, owner, kind, what, why, suffixes.toList(),
        "厂商公开产品说明 / 隐私政策；按域名用途人工归类（未逐条附链接）",
    )

    val ALL: List<ServiceInfo> = listOf(
        // ── 广告投放 ──────────────────────────────────────────────
        e(
            "google-ads", "谷歌广告", "Google", ServiceKind.AD,
            "网页和 App 里的横幅、贴片、开屏广告",
            "它要先记下你看过什么才能挑广告，所以页面一打开就在后台报到；拦掉不影响正文。",
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        ),
        e(
            "baidu-union", "百度联盟", "百度", ServiceKind.AD,
            "很多小网站和免费 App 上挂的百度广告位",
            "广告按曝光和点击计费，本身不提供你要看的内容。",
            "pos.baidu.com", "cpro.baidu.com", "union.baidu.com",
            "mobads.baidu.com", "mobads-logs.baidu.com",
        ),
        e(
            "tanx", "阿里妈妈 TanX", "阿里巴巴", ServiceKind.AD,
            "淘宝联盟的广告竞价市场",
            "把你正在打开的页面实时拿去拍卖，谁出价高就展示谁的广告。",
            "tanx.com",
        ),
        e(
            "alimama-mmstat", "阿里妈妈统计", "阿里巴巴", ServiceKind.AD,
            "广告的曝光、点击与成交统计",
            "只用于广告结算，对你没有任何用处。",
            "mmstat.com", "adash.m.taobao.com", "adashx.m.taobao.com",
        ),
        e(
            "alimama-simba", "阿里妈妈定向", "阿里巴巴", ServiceKind.AD,
            "判断该给你看哪一类商品广告",
            "按你的浏览习惯打人群标签，属于典型的画像追踪。",
            "simba.taobao.com",
        ),
        e(
            "gdt", "腾讯广点通", "腾讯", ServiceKind.AD,
            "微信、QQ 和腾讯系 App 里的广告位",
            "跨应用识别同一台设备，用来投放和计费。",
            "gdt.qq.com", "pgdt.gtimg.cn", "adping.qq.com", "adrdir.qq.com",
        ),
        e(
            "alimama-ad", "阿里妈妈投放", "阿里巴巴", ServiceKind.AD,
            "淘宝客链接跳转、广告素材与点击分成统计",
            "你点开的商品链接要先经过它记一次账再跳到淘宝，只影响分成，不影响你买东西。",
            "acookie.alimama.com", "afpeng.alimama.com", "afpssp.alimama.com", "afptrack.alimama.com",
            "banner.alimama.com", "c.alimama.com", "cpc.click.alimama.com", "p.alimama.com",
            "static.alimama.com", "t.alimama.com",
        ),
        e(
            "tencent-stat", "腾讯统计", "腾讯", ServiceKind.TRACK,
            "腾讯系页面的访问统计与广告点击回传",
            "记录页面打开速度、来源和机型，属于运营数据，对你没有用处。",
            "e.qq.com", "pingfore.qq.com", "pingjs.qq.com",
        ),
        e(
            "pangolin", "穿山甲", "字节跳动", ServiceKind.AD,
            "免费 App 里最常见的广告 SDK 之一",
            "App 一启动就预加载广告并回传设备信息，是国产 App 广告的主要来源。",
            "pangolin-sdk-toutiao.com", "pglstatp-toutiao.com",
        ),
        e(
            "mintegral", "Mintegral", "汇量科技", ServiceKind.AD,
            "小游戏、工具类 App 里的广告 SDK",
            "加载插屏与激励视频，同时上报设备标识用于定向。",
            "mintegral.com",
        ),
        e(
            "applovin", "AppLovin", "AppLovin", ServiceKind.AD,
            "移动应用内的广告变现平台",
            "在后台持续竞价广告位，会消耗流量和电量。",
            "applovin.com",
        ),
        e(
            "mopub", "MoPub", "AppLovin（已并入）", ServiceKind.AD,
            "Twitter 原来的广告聚合平台",
            "服务已停运，但老版本 App 里还留着这条地址，会反复请求失败。",
            "ads.mopub.com", "mopub.com",
        ),
        e(
            "unity-ads", "Unity Ads", "Unity", ServiceKind.AD,
            "Unity 游戏引擎自带的广告",
            "游戏里的强制插屏和「看广告领奖励」。",
            "unityads.unity3d.com",
        ),
        e(
            "vungle", "Vungle", "Vungle", ServiceKind.AD,
            "激励视频广告：看完给游戏奖励",
            "视频广告和追踪脚本一起加载，通常还很占带宽。",
            "vungle.com",
        ),
        e(
            "adcolony", "AdColony", "AdColony", ServiceKind.AD,
            "移动端视频广告网络",
            "自动播放视频广告，并回传观看行为。",
            "adcolony.com",
        ),
        e(
            "chartboost", "Chartboost", "Chartboost", ServiceKind.AD,
            "游戏内插屏广告与互相推广",
            "在游戏切换场景时弹广告，容易误触。",
            "chartboost.com",
        ),
        e(
            "inmobi", "InMobi", "InMobi", ServiceKind.AD,
            "应用内广告网络",
            "跨应用识别设备来投放广告。",
            "inmobi.com",
        ),
        e(
            "twitter-ads", "X 广告", "X（原 Twitter）", ServiceKind.AD,
            "X 的广告素材与转化像素",
            "埋在其他网站里，追踪你看了广告之后做了什么。",
            "static.ads-twitter.com",
        ),
        e(
            "taboola", "Taboola", "Taboola", ServiceKind.AD,
            "文章末尾「你可能还喜欢」的推荐位",
            "多数是付费内容广告，靠标题吸引点击。",
            "taboola.com",
        ),
        e(
            "outbrain", "Outbrain", "Outbrain", ServiceKind.AD,
            "和 Taboola 同类的推荐广告",
            "把推广内容混进正文推荐里，很难一眼分辨。",
            "outbrain.com",
        ),
        e(
            "criteo", "Criteo", "Criteo", ServiceKind.AD,
            "重定向广告：看过什么就追着推什么",
            "跨站记录你浏览过的商品，属于典型的再营销追踪。",
            "criteo.com", "criteo.net",
        ),
        e(
            "xandr", "Xandr（AppNexus）", "Microsoft", ServiceKind.AD,
            "广告交易平台，实时拍卖广告位",
            "把你的浏览行为带进竞价流程，决定你看到哪条广告。",
            "adnxs.com",
        ),
        e(
            "pubmatic", "PubMatic", "PubMatic", ServiceKind.AD,
            "广告交易平台（卖方侧）",
            "只做广告位拍卖，与你要看的内容无关。",
            "pubmatic.com",
        ),
        e(
            "magnite", "Magnite（Rubicon）", "Magnite", ServiceKind.AD,
            "广告竞价与变现平台",
            "每次页面加载都发起一次拍卖请求。",
            "rubiconproject.com", "spotxchange.com",
        ),
        e(
            "openx", "OpenX", "OpenX", ServiceKind.AD,
            "广告交易平台",
            "拍卖广告位并回传用户标识。",
            "openx.net",
        ),
        e(
            "index-exchange", "Index Exchange", "Index Exchange", ServiceKind.AD,
            "广告交易平台",
            "只服务程序化广告竞价。",
            "casalemedia.com",
        ),
        e(
            "ttd", "The Trade Desk", "The Trade Desk", ServiceKind.AD,
            "程序化广告投放平台",
            "跨设备匹配你的身份，用来决定投什么广告。",
            "adsrvr.org",
        ),
        e(
            "equativ", "Equativ（Smart）", "Equativ", ServiceKind.AD,
            "欧洲的广告交易平台",
            "页面广告位竞价，不提供任何内容。",
            "smartadserver.com",
        ),
        e(
            "teads", "Teads", "Teads", ServiceKind.AD,
            "新闻网站正文里的视频贴片广告",
            "自动播放视频广告，很占流量。",
            "teads.tv",
        ),
        e(
            "33across", "33Across", "33Across", ServiceKind.AD,
            "广告受众数据与身份匹配",
            "跨站拼凑你的兴趣标签，再卖给广告主。",
            "33across.com",
        ),
        e(
            "sharethrough", "Sharethrough", "Sharethrough", ServiceKind.AD,
            "原生广告：长得像正文的推广内容",
            "把广告伪装成内容推荐。",
            "sharethrough.com",
        ),
        e(
            "yieldmo", "Yieldmo", "Yieldmo", ServiceKind.AD,
            "移动端原生广告样式",
            "记录你对广告的交互，用来优化下一次投放。",
            "yieldmo.com",
        ),
        e(
            "adform", "Adform", "Adform", ServiceKind.AD,
            "欧洲市场的横幅与视频广告投放",
            "程序化竞价买广告位，按曝光计费。",
            "adform.net",
        ),
        e(
            "gumgum", "GumGum", "GumGum", ServiceKind.AD,
            "图片内嵌广告与上下文广告",
            "扫描页面图片内容来匹配广告，等于连你正在看什么图都要读一遍。",
            "gumgum.com",
        ),
        e(
            "innovid", "Innovid", "Innovid", ServiceKind.AD,
            "视频广告的播放与互动统计",
            "记录广告看了几秒、有没有点，用于和广告主结算。",
            "innovid.com",
        ),
        e(
            "ironsrc", "ironSource", "Unity", ServiceKind.AD,
            "应用内广告与中介聚合（含 Supersonic）",
            "既放广告又做数据分析，是免费 App 变现的主要通道。",
            "ironsrc.com", "supersonicads.com",
        ),
        e(
            "liftoff", "Liftoff", "Liftoff", ServiceKind.AD,
            "激励视频与原生广告投放",
            "按安装和互动计费，需要持续追踪你的设备。",
            "liftoff.io",
        ),
        e(
            "sovrn", "Sovrn（Lijit）", "Sovrn", ServiceKind.AD,
            "中小网站的广告位聚合",
            "把大量长尾网站的流量打包卖给广告主。",
            "sovrn.com", "lijit.com",
        ),
        e(
            "tapjoy", "Tapjoy", "Tapjoy", ServiceKind.AD,
            "用奖励换广告观看的激励平台",
            "看完广告给游戏币，代价是它要记录你的设备和观看行为。",
            "tapjoy.com",
        ),
        e(
            "tremor", "Tremor（Nexxen）", "Nexxen", ServiceKind.AD,
            "视频广告的程序化投放",
            "按人群标签竞价视频广告位。",
            "tremorhub.com",
        ),
        e(
            "undertone", "Undertone", "Undertone", ServiceKind.AD,
            "品牌全屏与插屏广告",
            "按浏览兴趣定向投放，需要先给你打标签。",
            "undertone.com",
        ),
        e(
            "uyunad", "优云", "优云", ServiceKind.AD,
            "国内 App 的开屏与插屏广告",
            "启动时加载广告并回传设备标识。",
            "uyunad.com",
        ),
        e(
            "amazon-ads", "亚马逊广告", "Amazon", ServiceKind.AD,
            "亚马逊的站外广告与转化追踪",
            "追踪你在别的网站上看了什么、买了什么。",
            "amazon-adsystem.com",
        ),
        e(
            "moat", "Oracle Moat", "Oracle", ServiceKind.AD,
            "广告验证：统计广告有没有被真人看到",
            "在广告位上再加一层测量脚本。",
            "moatads.com",
        ),
        e(
            "ias", "IAS", "Integral Ad Science", ServiceKind.AD,
            "广告可见性测量",
            "为广告主统计曝光，与你要看的内容无关。",
            "adsafeprotected.com",
        ),
        e(
            "doubleverify", "DoubleVerify", "DoubleVerify", ServiceKind.AD,
            "广告反作弊与曝光测量",
            "又一层广告测量脚本，只服务广告主。",
            "doubleverify.com",
        ),
        e(
            "miaozhen", "秒针系统", "秒针", ServiceKind.AD,
            "国内广告监测与到达率统计",
            "给广告主出报告，记录你的广告曝光。",
            "miaozhen.com",
        ),
        e(
            "admaster", "AdMaster", "秒针（AdMaster）", ServiceKind.AD,
            "广告效果监测",
            "记录广告曝光与点击，用于结案报告。",
            "admaster.com.cn",
        ),

        // ── 统计追踪 ──────────────────────────────────────────────
        e(
            "gtm", "谷歌代码管理器", "Google", ServiceKind.TRACK,
            "开发者往页面里塞统计和广告代码的容器",
            "它一加载就会连带请求一堆追踪脚本，拦掉不影响页面功能。",
            "googletagmanager.com",
        ),
        e(
            "google-analytics", "谷歌分析", "Google", ServiceKind.TRACK,
            "网站和 App 的访问统计",
            "记录你看了哪些页面、停留多久、从哪来。",
            "google-analytics.com", "analytics.google.com", "app-measurement.com",
        ),
        e(
            "baidu-tongji", "百度统计", "百度", ServiceKind.TRACK,
            "站长看访问量用的统计工具",
            "记录来源、设备型号和停留时间。",
            "hm.baidu.com",
        ),
        e(
            "umeng", "友盟统计", "阿里巴巴", ServiceKind.TRACK,
            "App 开发商看装机量与活跃度的统计",
            "上报启动次数、机型与渠道来源。",
            "alog.umeng.com", "analytics.umeng.com", "umeng.com", "umengcloud.com",
        ),
        e(
            "cnzz", "CNZZ 统计", "阿里（友盟）", ServiceKind.TRACK,
            "老牌网站流量统计",
            "统计访问来源与页面热度。",
            "cnzz.com",
        ),
        e(
            "51la", "51LA 统计", "51LA", ServiceKind.TRACK,
            "中小站长常用的免费统计",
            "记录访问来源和 IP 归属地。",
            "51.la",
        ),
        e(
            "talkingdata", "TalkingData", "TalkingData", ServiceKind.TRACK,
            "移动数据统计与用户画像",
            "汇总设备行为，做行业报告和广告定向。",
            "talkingdata.net", "talkingdata.com",
        ),
        e(
            "comscore", "Comscore", "Comscore", ServiceKind.TRACK,
            "媒体测量：统计有多少人看了哪个网站",
            "这些数据最终用来给广告定价。",
            "scorecardresearch.com",
        ),
        e(
            "quantcast", "Quantcast", "Quantcast", ServiceKind.TRACK,
            "受众测量与广告定向",
            "给访客打兴趣标签，再用于投放。",
            "quantserve.com",
        ),
        e(
            "clarity", "微软 Clarity", "Microsoft", ServiceKind.TRACK,
            "把页面上的点击和滚动录成回放",
            "录的是你的操作轨迹，属于行为数据；拦掉不影响网页。",
            "c.clarity.ms",
        ),
        e(
            "bing-uet", "微软广告追踪", "Microsoft", ServiceKind.TRACK,
            "必应广告的转化追踪",
            "记录你点了广告之后有没有下单。",
            "bat.bing.com",
        ),
        e(
            "twitter-analytics", "X 分析", "X（原 Twitter）", ServiceKind.TRACK,
            "X 的站外访问统计",
            "追踪第三方页面上的点击与转化。",
            "analytics.twitter.com",
        ),
        e(
            "growingio", "GrowingIO", "GrowingIO", ServiceKind.TRACK,
            "国内常见的用户行为分析埋点",
            "把每一次点击和页面流转都逐条上报。",
            "growingio.com",
        ),
        e(
            "sensors", "神策数据", "神策", ServiceKind.TRACK,
            "企业级行为分析埋点",
            "记录你在 App 里的完整操作路径。",
            "sensorsdata.cn", "sensorsdata.com",
        ),
        e(
            "irs", "艾瑞监测", "艾瑞咨询", ServiceKind.TRACK,
            "网站流量与广告监测代码",
            "把访问数据汇入行业报告。",
            "irs01.com",
        ),

        // ── 安装归因 ──────────────────────────────────────────────
        e(
            "appsflyer", "AppsFlyer", "AppsFlyer", ServiceKind.ATTRIB,
            "判断你是点了哪条广告才装这个 App 的",
            "把设备标识和点击来源报给广告平台，属于跨应用追踪。",
            "appsflyer.com",
        ),
        e(
            "adjust", "Adjust", "Adjust", ServiceKind.ATTRIB,
            "和 AppsFlyer 同类的安装归因服务",
            "跨应用追踪安装来源，用来给广告渠道结账。",
            "adjust.com", "adjust.io",
        ),

        // ── 设备与崩溃上报 ────────────────────────────────────────
        e(
            "xiaomi-ads", "小米广告", "小米", ServiceKind.AD,
            "小米系统和自家 App 里的广告服务",
            "推送小米广告并回传设备信息；与系统更新无关。",
            "api.ad.xiaomi.com", "ad.mi.com",
        ),
        e(
            "miui-stat", "小米统计", "小米", ServiceKind.TELEMETRY,
            "MIUI 的使用数据统计",
            "上报设备与使用情况；拦掉不影响系统更新和应用商店。",
            "data.mistat.xiaomi.com",
        ),
        e(
            "miui-track", "MIUI 追踪", "小米", ServiceKind.TELEMETRY,
            "系统组件里的追踪埋点",
            "记录系统内的使用行为，普通用户在设置里关不掉。",
            "tracking.miui.com", "tracker.ai.xiaomi.com",
        ),
        e(
            "crashlytics", "Firebase 崩溃上报", "Google", ServiceKind.TELEMETRY,
            "App 闪退时把崩溃日志发给开发者",
            "日志里含机型、系统版本和一段运行记录；拦掉后开发者收不到你的崩溃报告。",
            "crashlytics.com",
        ),
        e(
            "bugly", "腾讯 Bugly", "腾讯", ServiceKind.TELEMETRY,
            "国内常用的崩溃上报服务",
            "会把设备信息和崩溃堆栈上传到腾讯服务器。",
            "bugly.qq.com",
        ),
    )

    private val manualIndex: Map<String, ServiceInfo> = buildMap {
        for (s in ALL) for (suffix in s.suffixes) put(suffix.lowercase(), s)
    }

    /**
     * 两张表合成一张索引再按「最长后缀」逐级查：生成表里更精确的子域
     * （例如 -ad.a.yximgs.com 是广告）必须能盖过手工表里较宽的父域，
     * 否则「整体手工表优先」会把广告子域说成内容 CDN。
     * 同一个后缀冲突时，人工核实过的手工表覆盖生成表。
     */
    private val mergedIndex: Map<String, ServiceInfo> = buildMap {
        for (s in GeneratedCatalog.ALL) for (x in s.suffixes) put(x.lowercase(), s)
        manualIndex.forEach { (k, v) -> put(k, v) }
    }

    fun lookup(domain: String): ServiceInfo? {
        var cursor = domain.trim().trimEnd('.').lowercase()
        while (cursor.isNotEmpty()) {
            mergedIndex[cursor]?.let { return it }
            val dot = cursor.indexOf('.')
            if (dot < 0) return null
            cursor = cursor.substring(dot + 1)
        }
        return null
    }

    /** 手工表登记的后缀数，用于界面上的说明表统计。 */
    fun size(): Int = manualIndex.size
    fun generatedSize(): Int = GeneratedCatalog.size()
    fun ruleSource(): String = GeneratedCatalog.RULE_SOURCE

    /** 说明表对应哪一版规则，显示成 anti-AD 的日期戳，便于核对有没有漏同步。 */
    fun ruleSourceLabel(): String {
        val s = GeneratedCatalog.RULE_SOURCE
        return if (s.length >= 8) "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
        else "当前规则"
    }
}
