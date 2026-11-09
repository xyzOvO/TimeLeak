package com.chengliu.app.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.chengliu.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内「检测更新」。
 *
 * 版本信息来源（并发查询，汇总后取版本号最高的那条）：
 *   1. 仓库根的 update.json —— 与提交到根目录的 app-release.apk 同步，最贴合本项目的发布方式；
 *      raw 与 jsDelivr 两个地址互为备份；
 *   2. GitHub Releases API —— 兜底，优先取 Release 里的 APK 附件。
 * 取最高版本而非「第一个可用结论」，避免只打新 tag 忘了改 update.json（或反过来）时漏报。
 *
 * 下载地址给一组候选，逐个重试：github.com 主站 / raw / jsDelivr / Release 附件。
 * 这样某个域名在国内移动网络被 reset 时不会整个更新流程失败。
 * 安装前比对签名：读不到就放行交给系统安装器，明确不一致才拦（挡二次打包的非官方包）。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val API_URL = "https://api.github.com/repos/xyzOvO/TimeLeak/releases/latest"

    private val UPDATE_JSON_URLS = listOf(
        "https://raw.githubusercontent.com/xyzOvO/TimeLeak/main/update.json",
        "https://cdn.jsdelivr.net/gh/xyzOvO/TimeLeak@main/update.json",
    )

    // 候选下载源：主站优先（国内可达性通常好于 raw），Release 附件放最后兜底
    private const val GH_RAW_APK = "https://github.com/xyzOvO/TimeLeak/raw/main/app-release.apk"
    private const val RAW_APK = "https://raw.githubusercontent.com/xyzOvO/TimeLeak/main/app-release.apk"
    private const val JSDELIVR_APK = "https://cdn.jsdelivr.net/gh/xyzOvO/TimeLeak@main/app-release.apk"
    private const val RELEASE_APK = "https://github.com/xyzOvO/TimeLeak/releases/latest/download/app-release.apk"

    private val UA = "TimeLeak/${BuildConfig.VERSION_NAME}"

    sealed interface Result {
        object Latest : Result
        data class Available(val info: UpdateInfo) : Result
        data class Error(val message: String) : Result
    }

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val notes: String,
        val downloadUrls: List<String>,
    )

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_NAME
        // 汇总所有可用源，取版本号最高的那条再比较。
        // 这样「只打了新 tag 忘了改 update.json」或反过来，都不会漏报更新。
        // 三个源并发查，避免串行时网络差要等满全部超时
        val found = coroutineScope {
            val jobs = UPDATE_JSON_URLS.map { u ->
                async { sourceInfo { parseUpdateJson(fetchText(u), current) } }
            } + async {
                sourceInfo { parseReleases(fetchText(API_URL), current) }
            }
            jobs.awaitAll().filterNotNull()
        }
        if (found.isEmpty()) return@withContext Result.Error("暂时拿不到更新信息，请检查网络后重试")
        val best = found.maxWithOrNull(
            Comparator { a, b -> compareVersions(a.latestVersion, b.latestVersion) }
        )!!
        if (compareVersions(best.latestVersion, current) > 0) Result.Available(best) else Result.Latest
    }

    private fun parseUpdateJson(text: String, current: String): UpdateInfo {
        val json = JSONObject(text)
        val latest = json.optString("version", "").removePrefix("v").trim()
        require(latest.isNotEmpty()) { "更新信息格式不对" }
        val notes = json.optString("notes", json.optString("body", "")).trim()
        val configured = json.optString("apk", "").takeIf { it.isNotBlank() }
        val urls = (listOfNotNull(configured) + listOf(GH_RAW_APK, RAW_APK, JSDELIVR_APK, RELEASE_APK))
            .distinct()
        return UpdateInfo(latest, current, notes, urls)
    }

    private fun parseReleases(text: String, current: String): UpdateInfo {
        val json = JSONObject(text)
        val latest = json.optString("tag_name", "").removePrefix("v").trim()
        require(latest.isNotEmpty()) { "没有可用的发布" }
        val notes = json.optString("body", "").trim()
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            val ct = a.optString("content_type", "")
            if (name.endsWith(".apk") || ct == "application/vnd.android.package-archive") {
                apk = a.optString("browser_download_url", "")
                break
            }
        }
        val urls = (listOfNotNull(apk) + listOf(RELEASE_APK, GH_RAW_APK, RAW_APK, JSDELIVR_APK))
            .distinct()
        return UpdateInfo(latest, current, notes, urls)
    }

    /** 单源的网络/解析失败降级为「该源不可用」，但协程取消必须原样抛出，否则取消按钮会失效。 */
    private inline fun <T> sourceInfo(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun fetchText(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json, */*")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    /** 按候选顺序下载；某个源失败会清掉半截文件再换下一个，全失败才返回 false。 */
    suspend fun download(urls: List<String>, dest: File, onProgress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            for (u in urls.distinct()) {
                if (downloadOne(u, dest, onProgress)) return@withContext true
                runCatching { dest.delete() }
            }
            false
        }

    private suspend fun downloadOne(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                // 大文件在移动网络下可能很慢，读超时放宽到 60s
                conn.readTimeout = 60000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", UA)
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "download HTTP $code: $url")
                    return@withContext false
                }
                val total = conn.contentLengthLong.coerceAtLeast(0L)
                dest.parentFile?.mkdirs()
                var done = 0L
                var reported = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } >= 0) {
                            out.write(buf, 0, read)
                            done += read
                            // 每 128KB 报一次进度，且回调切回主线程更新 Compose 状态
                            if (done - reported >= 128 * 1024) {
                                reported = done
                                withContext(Dispatchers.Main) { onProgress(done, total) }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { onProgress(done, total) }
                dest.exists() && dest.length() > 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消时必须向外抛，否则会被下面的 catch(Exception) 吞掉，
                // 导致 download() 以为「这个源失败」又去试下一个，取消形同虚设
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url -> ${e.message}")
                false
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * 比对下载包与已装包的签名证书。
     * 读不到签名时放行 —— 系统安装器自己会做签名校验，避免 v2/v3 -only 签名被误拦。
     */
    fun signaturesMatch(context: Context, apkPath: String): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val installed = pm.getPackageInfo(context.packageName, flags)
            val archive = pm.getPackageArchiveInfo(apkPath, flags) ?: return true
            val mine = certsOf(installed)
            val other = certsOf(archive)
            if (mine.isEmpty() || other.isEmpty()) return true
            mine.any { a -> other.any { b -> a.contentEquals(b) } }
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 读取下载包的 versionCode，用于判断候选源是否给回了旧包（多源兜底时可能发生）。
     * 读不到返回 -1，交由系统安装器裁决。
     */
    @Suppress("DEPRECATION")
    fun archiveVersionCode(context: Context, apkPath: String): Long {
        return try {
            val info = context.packageManager.getPackageArchiveInfo(apkPath, 0) ?: return -1
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        } catch (_: Exception) {
            -1
        }
    }

    @Suppress("DEPRECATION")
    private fun certsOf(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptyList()
            val arr = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return arr?.map { it.toByteArray() } ?: emptyList()
        }
        return info.signatures?.map { it.toByteArray() } ?: emptyList()
    }

    /** 语义化版本比较：a>b 返回正，相等 0，a<b 负。支持 "1.2.0" / "1.10" / "v1.2.1"。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
