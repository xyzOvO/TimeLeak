package com.chengliu.app.core

import android.app.ActivityManager
import android.content.Context

/**
 * 运行时把本应用从「最近任务」列表里隐藏。
 *
 * 用 `AppTask.setExcludeFromRecents()` 而不是 Manifest 里的静态
 * `android:excludeFromRecents`：后者写死在清单里、没法做成开关，而且会让
 * 用户连一次最近任务都用不上。这里只隐藏任务卡片，进程、前台服务通知、
 * 无障碍授权全都保留 —— 桌面图标和通知点进来照常可用。
 *
 * 只对当前进程已有的 task 生效，所以每次冷启动（MainActivity.onCreate）
 * 和开关切换时都要重新应用一次。个别 ROM 可能不完全遵守，属系统行为。
 *
 * 只要 Context 就够了：`appTasks` 是本应用全局的任务列表，不需要先拿到 Activity，
 * 界面里从 `LocalContext` 直接调用即可。
 */
object RecentsVisibility {
    fun apply(ctx: Context, hide: Boolean) {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        runCatching {
            for (task in am.appTasks) task.setExcludeFromRecents(hide)
        }
    }
}
