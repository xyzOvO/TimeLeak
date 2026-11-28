package com.chengliu.app.core

/**
 * 点击之后的「效果复核」判据。
 *
 * 为什么需要它：`performAction(ACTION_CLICK)` 返回 true 只代表控件接受了这个动作，
 * 不代表页面有任何变化。有的应用开屏就摆着三个「跳过广告1/2/3」文字标签，点了没有任何
 * 反应，而真正的跳过是旁边那个没有文字的图标 —— 老版本在这里既记了一次跳过、
 * 又弹了「已跳过广告」，纯属自欺。
 *
 * 纯逻辑、零 Android 依赖，方便 JVM 单测覆盖。
 */
object EffectVerdict {

    /**
     * @param stillVisible 同一个控件现在还看得见吗；null 表示没法确认（没有 viewId / 拿不到根节点）
     * @param actChanged 前台 Activity 变了
     * @param pkgChanged 前台包名变了
     * @param windowAdvanced 这期间发生过新的窗口事件
     * @return true 表示「页面确实变了」，可以认定为跳过生效
     */
    fun judge(
        stillVisible: Boolean?,
        actChanged: Boolean,
        pkgChanged: Boolean,
        windowAdvanced: Boolean,
    ): Boolean {
        // 包名 / Activity 换了：页面确实走了，算生效。
        if (actChanged || pkgChanged) return true
        // 「又来了窗口事件」只是弱证据：悬浮通知、气泡、输入法弹出都会发窗口事件。
        // 只有在我们根本查不到那个控件、没有更强信息时才采信它。
        if (windowAdvanced && stillVisible == null) return true
        // 控件还看得见 → 点了没反应；查不到 → 连控件一起消失了，算生效。
        return stillVisible == false
    }
}
