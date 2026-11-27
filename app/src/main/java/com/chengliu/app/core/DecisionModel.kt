package com.chengliu.app.core

/**
 * 决策模型：**零 Android 依赖**。
 *
 * 单独抽出来是为了能在电脑上用纯 JVM 跑规则回归（tools/regression），
 * 不需要真机、不需要 android.jar。这里只放数据类、枚举和权重常量，
 * 任何 `import android.*` 都会让离线回归编译失败，请勿添加。
 */

/** 命中证据的强度，策略档位据此决定放不放行。 */
enum class HitReason {
    /** 广告 SDK 的皮肤资源名，最可靠。 */
    SDK_ID,

    /** 明确文案 / 明确关闭控件，例如「跳过广告」「关闭推广」。 */
    EXPLICIT_TEXT,

    /** 订阅里的 GKD 规则，覆盖广但作者未必逐条验证过。 */
    SUBSCRIPTION,

    /** 用户自定义关键词等推断式匹配。 */
    TEXT_GUESS,

    /**
     * 同容器补位：文字候选点了没反应，改点同一个父容器里那个「无文字、窄、可点」的图标。
     * 属于推断式证据，只在开了文本推断的策略档下才允许。
     */
    SIBLING_ICON,

    /**
     * 同一个控件上次已经点中并复核生效（记在 HitMemory 里）。
     * 它省的是「重新认一遍」，不是跳过判定本身：仍然要过全部闸门与二次验证。
     */
    MEMORY,

    /** 按坐标点一下，风险最高。 */
    COORDINATE,
}

enum class AdKind(val label: String) {
    SPLASH("开屏广告"),
    POPUP("推广弹窗 / 插屏"),
    FEED("信息流广告"),
    REWARDED("激励视频"),
    PAID("付费 / 下载广告"),
    UNKNOWN("无法确认"),
    ;

    /** 唯一允许自动点击的两类。 */
    val clickable: Boolean get() = this == SPLASH || this == POPUP
}

/**
 * 应用级策略档位。
 * splash/popup 决定场景，textGuess/gesture 决定允许多「软」的证据。
 */
enum class PolicyMode(
    val label: String,
    val desc: String,
    val splash: Boolean,
    val popup: Boolean,
    val textGuess: Boolean,
    val gesture: Boolean,
) {
    DISABLED(
        "不自动点击",
        "完全不自动点击，适合银行、支付、政务类应用",
        splash = false, popup = false, textGuess = false, gesture = false,
    ),
    STRICT_SPLASH(
        "仅明确开屏按钮",
        "只认广告 SDK 控件与明确跳过文案，不做订阅推断、不按坐标点",
        splash = true, popup = false, textGuess = false, gesture = false,
    ),
    SPLASH_ONLY(
        "只跳过开屏",
        "允许开屏跳过，不处理购物推荐、活动弹窗等应用内弹窗",
        splash = true, popup = false, textGuess = true, gesture = true,
    ),
    FULL(
        "开屏 + 推广弹窗",
        "默认档：跳过开屏，并保守关闭带明确广告信号的推广弹窗",
        splash = true, popup = true, textGuess = true, gesture = true,
    ),
    ;

    companion object {
        fun of(name: String?): PolicyMode? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}

/** 三档置信度：只有 HIGH 才可能自动点击。 */
enum class Confidence(val label: String) {
    HIGH("高置信度"),
    MID("中置信度"),
    LOW("低置信度"),
    ;
}

/** 决策结果里的动作，观察模式下任何一档都不执行点击。 */
enum class DecisionAction(val label: String) {
    CLICK("允许自动点击"),
    OBSERVE("观察模式，未点击"),
    WAIT("仅记录，等待更多证据"),
    SKIP("不操作"),
    ;
}

/**
 * 评分权重表。
 * 离线回归脚本会解析本文件校验权重，改动这里会直接反映到回归结论里。
 */
object Weights {
    const val SDK_ID = 55
    const val SDK_CLOSE = 50
    const val EXPLICIT_TEXT = 45
    const val SCOPED_RULE = 30
    const val GLOBAL_RULE = 20
    const val CUSTOM_RULE = 22
    const val COORDINATE = 8

    /**
     * 同容器无文字图标：开屏窗内 58 分，配合「窄控件 + 可点击 + 开屏窗内」
     * 刚好过 70 分线；离开开屏窗就够不着，不会在普通页面上乱点图标。
     */
    const val SIBLING_ICON = 58

    /**
     * 记忆命中：上次在本应用点中、并且复核过「页面确实变了」的同一控件。
     * 比补位图标可信（补位是推断，这条是实证），但依旧要配合窄控件 / 可点击 / 开屏窗内。
     */
    const val MEMORY = 62

    const val IN_SPLASH = 12
    const val NARROW = 8
    const val SHORT_TEXT = 5
    const val CORNER = 12
    const val CLICKABLE = 6
    const val AD_CLASS = 5
    const val SCOPED_BONUS = 6
    const val COUNTDOWN = 3
    const val COUNTDOWN_END = 5

    const val WIDE = -25
    const val LONG_TEXT = -20
    const val OUTSIDE_WINDOW = -20
    const val GLOBAL_PENALTY = -10
    const val SOFT_ONLY = -10

    const val HIGH_MIN = 70
    const val MID_MIN = 45
    const val MID_UPGRADE_MIN = 55

    /** 点击成功后的冷却时长，同窗口内不再点第二个目标。 */
    const val COOLDOWN_MS = 2000L

    /** 中置信度等待二次证据的窗口。 */
    const val EVIDENCE_WINDOW_MS = 1500L
}

/**
 * 一次候选目标的全部事实。
 * 由无障碍服务把 AccessibilityNodeInfo 拍平成纯数据后传入，
 * 决策核心不持有任何 Android 对象，也不访问界面树。
 */
data class NodeFacts(
    val pkg: String = "",
    val text: String = "",
    val desc: String = "",
    val id: String = "",
    val cls: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val screenW: Int = 1080,
    val screenH: Int = 1920,
    val clickable: Boolean = true,
    val visible: Boolean = true,
    val inSplash: Boolean = true,
    val freshWindow: Boolean = true,
    val windowAgeMs: Long = 0L,
    val popupRound: Boolean = false,
    val reason: HitReason = HitReason.SDK_ID,
    val kind: AdKind = AdKind.SPLASH,
    val safeTarget: Boolean = true,
    val sdkId: Boolean = false,
    val sdkClose: Boolean = false,
    val explicitText: Boolean = false,
    val corner: Boolean = false,

    /** 控件自己不可点、且唯一的可点祖先是整屏广告热区（疑似假按钮）。 */
    val trapAncestor: Boolean = false,

    /** 可点祖先覆盖屏幕的百分比，仅用于诊断与报告。 */
    val ancestorAreaPct: Int = 0,

    /** 同容器补位候选：没有文字、窄、可点的图标。 */
    val siblingIcon: Boolean = false,

    /** 这一条是 HitMemory 记住的、上次已验证生效的同一控件。 */
    val remembered: Boolean = false,
    val scopedRule: Boolean = false,
    val globalRule: Boolean = false,
    val customRule: Boolean = false,
    val coordinateRule: Boolean = false,
    val cooldownActive: Boolean = false,
    val evidenceRepeat: Boolean = false,
    val observe: Boolean = false,
    val mode: PolicyMode = PolicyMode.FULL,
    val userIdle: Boolean = true,
) {
    val label: String get() = text.ifEmpty { desc }.trim()
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** 一次判定的完整结果，可直接喂给观察模式界面或回归断言。 */
data class Decision(
    val score: Int,
    val level: Confidence,
    val kind: AdKind,
    val action: DecisionAction,
    val rule: String,
    val reasons: List<String>,
    val policyAllowed: Boolean,
    val cooldownBlocked: Boolean = false,
    val upgraded: Boolean = false,
)
