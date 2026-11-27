package com.chengliu.app.core

/**
 * 「这些包名不可能有开屏广告」的判定。
 *
 * 判定拼三件事：
 *  1. 静态桌面名单：冷启动时 [registerHome] 还没跑也有兜底；
 *  2. 运行时解析出的桌面：各家 OEM 的桌面包名都不一样（MIUI 是 com.miui.home），
 *     硬编码名单追不完，交给 PackageManager 回答；
 *  3. 系统搜索入口（com.android.quicksearchbox 一类）：观感上像一个应用，
 *     其实是系统 UI 的一部分，永远不会有开屏广告。
 *
 * 为什么单独抽出来 —— v1.4.0 真机实测：一轮测试 99 次全树扫描里有 49 次打在
 * com.android.quicksearchbox 上，比任何一个真实广告应用都多，纯空转耗电。
 * 这份逻辑是纯数据 + 纯函数，不碰 Android API，JVM 单测可以直接钉住。
 */
object SystemPkgs {

    /** 常见桌面。只是兜底，真正的主力是运行时解析。 */
    val LAUNCHER: Set<String> = setOf(
        "com.miui.home", "com.android.launcher", "com.android.launcher3",
        "com.oppo.launcher", "com.huawei.android.launcher", "com.vivo.launcher",
    )

    /** 系统搜索 / 全局搜索入口：不是广告载体。 */
    val NO_AD_SEARCH: Set<String> = setOf(
        "com.android.quicksearchbox",
        "com.google.android.googlequicksearchbox",
        "com.miui.personalassistant",
        "com.android.searchlauncher",
    )

    /** 运行时从 PackageManager 拿到的桌面包名。 */
    @Volatile
    private var runtimeHome: Set<String> = emptySet()

    /** 登记桌面。空串与明显不像包名的串直接丢掉，避免污染判定。 */
    fun registerHome(pkgs: Collection<String>) {
        val add = pkgs.filter { it.isNotEmpty() && it.indexOf(' ') < 0 && it.contains('.') }
        if (add.isEmpty()) return
        runtimeHome = runtimeHome + add
    }

    /** 当前认定的桌面包名（静态 + 运行时），观测与调试用。 */
    val home: Set<String> get() = LAUNCHER + runtimeHome

    /** true 表示这个包不可能有开屏广告，扫描它纯属浪费电。 */
    fun isSkip(p: String?): Boolean {
        if (p.isNullOrEmpty()) return true
        return LAUNCHER.contains(p) || NO_AD_SEARCH.contains(p) || runtimeHome.contains(p)
    }
}
