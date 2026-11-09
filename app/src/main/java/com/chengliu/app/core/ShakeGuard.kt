package com.chengliu.app.core

/**
 * 摇一摇广告拦截。
 *
 * 现实边界：非 root 应用无法阻止别的 App 读取加速度计 / 陀螺仪，所以真正能做的
 * 只有「落地页回退」——摇一摇触发跳转后，落地页基本都是浏览器 / 应用商店 / 电商，
 * 我们在跳转发生的一瞬间按返回键，让用户回到原来的应用，等于吃掉这次误跳转。
 *
 * 判定条件（全部满足才拦，宁可漏拦也不误伤）：
 *  1. 目标页在落地页名单里，来源页不在；
 *  2. 来源页不是桌面 / 系统界面（否则从桌面点开淘宝也会被弹回去）；
 *  3. 距离上一次用户点击 / 滑动超过 1.5 秒 —— 摇一摇是没有触摸的；
 *  4. 来源页在前台不超过 4 秒 —— 开屏阶段才会被摇一摇带走；
 *  5. 同一个目标页 10 秒内不重复拦，避免和用户对着干。
 */
object ShakeGuard {

    private val LANDING = setOf(
        // 浏览器
        "com.android.browser", "com.miui.browser", "com.tencent.mtt", "com.UCMobile",
        "com.quark.browser", "com.baidu.searchbox", "com.android.chrome", "com.qihoo.browser",
        "com.vivo.browser", "com.heytap.browser", "com.huawei.browser", "org.mozilla.firefox",
        // 应用商店
        "com.xiaomi.market", "com.android.vending", "com.tencent.android.qqdownloader",
        "com.oppo.market", "com.heytap.market", "com.huawei.appmarket", "com.bbk.appstore",
        // 电商
        "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo", "com.achievo.vipshop", "com.suning.mobile.ebuy",
        // 快应用容器
        "com.miui.hybrid", "com.miui.hybrid.accessory", "com.huawei.fastapp",
        "com.oppo.fastapp", "com.vivo.fastapp", "com.bbk.fastapp",
    )

    private val HOME = setOf(
        "com.miui.home", "com.android.launcher", "com.android.launcher3",
        "com.oppo.launcher", "com.huawei.android.launcher", "com.vivo.launcher",
        "com.bbk.launcher2", "com.sec.android.app.launcher", "com.android.systemui",
    )

    private var lastBlockPkg: String? = null
    private var lastBlockAt = 0L

    fun isLanding(pkg: String): Boolean = LANDING.contains(pkg)

    fun shouldBack(
        from: String?,
        to: String,
        now: Long,
        lastInteractAt: Long,
        fgAt: Long,
    ): Boolean {
        if (from.isNullOrEmpty() || from == to) return false
        if (HOME.contains(from)) return false
        if (!LANDING.contains(to)) return false
        if (LANDING.contains(from)) return false
        if (now - lastInteractAt < 1500) return false
        if (now - fgAt > 4000) return false
        if (lastBlockPkg == to && now - lastBlockAt < 10_000) return false
        return true
    }

    fun markBlocked(to: String, now: Long) {
        lastBlockPkg = to
        lastBlockAt = now
    }

    fun landingCount(): Int = LANDING.size
}
