package com.chengliu.app.core

import android.content.Context
import android.content.Intent

/** 一个已安装应用的最小信息：包名 + 桌面显示名。 */
data class AppEntry(val pkg: String, val label: String)

/**
 * 已安装应用清单。
 *
 * Android 11+ 的 `getInstalledApplications` 受包可见性限制，只会返回寥寥几个
 * 系统应用；查 `ACTION_MAIN + CATEGORY_LAUNCHER` 才能拿到用户真正装了、并且
 * 有桌面入口的应用。这个清单只在本机使用，不上传、不落盘。
 */
object AppCatalog {

    private val labelCache = HashMap<String, String>()

    fun list(ctx: Context): List<AppEntry> = runCatching {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == ctx.packageName) return@mapNotNull null
                val label = label(ctx, pkg).ifEmpty { pkg }
                AppEntry(pkg, label)
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    fun label(ctx: Context, pkg: String): String {
        labelCache[pkg]?.let { if (it.isNotEmpty()) return it }
        val s = runCatching {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        }.getOrDefault("")
        // 空结果不缓存：包可见性刚放开时可能一次拿不到，下次还要能补上
        if (s.isNotEmpty()) labelCache[pkg] = s
        return s
    }
}
