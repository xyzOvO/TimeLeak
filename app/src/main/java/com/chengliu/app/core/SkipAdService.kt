package com.chengliu.app.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 自动跳过开屏广告 / 弹窗 + 摇一摇落地页回退。
 *
 * 三条来自开源实现（GKD / Android-Touch-Helper / ad-skipper）的关键经验：
 *
 *  1. **不能只靠事件驱动**。开屏广告往往在启动过渡结束之后才渲染，而且不发
 *     任何无障碍事件，只监听 `TYPE_WINDOW_CONTENT_CHANGED` 会整场错过。
 *     所以进入新窗口后有一段时间的主动轮询（见 [pollTask]）。
 *  2. **启动过渡期间屏幕上是上一次会话的快照**，头几百毫秒点下去没有意义，
 *     轮询故意延后 [FIRST_POLL_DELAY_MS] 才开始。
 *  3. **点完要复查**。命中后如果按钮还在，说明那一下没生效（常见于倒计时
 *     控件吞掉了点击），下一轮继续试，但同一个控件最多 [MAX_SAME_CLICKS] 次，
 *     避免在页面上反复戳同一个位置。
 *
 * 所有判断都在本机完成，不读取、不上传、不落盘任何界面内容。
 */
class SkipAdService : AccessibilityService() {

    private data class Ref(
        val node: AccessibilityNodeInfo,
        val text: String,
        val desc: String,
        val id: String,
        val bounds: Rect,
        val cls: String,
    )

    private val prefs by lazy { AppPrefs(this) }
    private val handler = Handler(Looper.getMainLooper())

    /** 规则解析单线程：几百 KB 的订阅不能在主线程解析，否则抬手就是 ANR。 */
    private val ruleLoader = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var curPkg: String? = null
    private var curAct: String? = null

    /** 当前窗口的切换时刻，用来算「开屏时间窗」。 */
    private var windowAt = 0L

    /** 前台应用切换时刻（给摇一摇判定用）。 */
    private var fgAt = 0L
    private var lastInteractAt = 0L
    private var lastScanAt = 0L
    private var lastGestureAt = 0L
    private var lastBackAt = 0L
    private var lastClickKey = ""
    private var lastClickAt = 0L

    /** 同一个控件本次窗口内点了几次。 */
    private val clickCounts = HashMap<String, Int>()

    /** 本次窗口累计点击次数，封顶后不再折腾。 */
    private var sessionClicks = 0
    private var scanning = false
    private var lastCache = ""
    private var lastCustom = ""
    private var eventCount = 0
    private var isPolling = false

    /**
     * 开屏窗口期的主动轮询。
     * 命中后不停表：广告可能分批渲染（先出图、后出跳过按钮），继续看着。
     */
    private val pollTask = object : Runnable {
        override fun run() {
            if (!autoPoll()) {
                isPolling = false
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SkipEngine.builtin = prefs.skipBuiltin
        lastCache = prefs.skipSubCache
        lastCustom = prefs.customRules
        loadRules(lastCache, lastCustom)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (eventCount < 20) {
            eventCount++
            android.util.Log.i("SkipAd", "事件 type=${e.eventType} pkg=${e.packageName}")
        }
        if (!prefs.skipAdEnabled) {
            stopPolling()
            return
        }
        syncEngine()

        val now = System.currentTimeMillis()
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> {
                lastInteractAt = now
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                if (pkg == "com.android.systemui") return
                handleWindow(pkg, e.className?.toString() ?: "", now)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                // 系统 UI 会持续发内容变化（状态栏时钟、通知），如果放它进来，
                // curPkg 会在 App 和 systemui 之间来回翻，每次切换都把点击计数
                // 清零，防连点的保护就废了。桌面同理。
                if (pkg == packageName || pkg == "com.android.systemui") return
                if (SKIP_PKGS.contains(pkg)) return
                // 兜底：无障碍服务如果是在 App 已经在前台之后才连上的
                // （重装、系统回收、手动开启都是这种情况），就再也收不到
                // TYPE_WINDOW_STATE_CHANGED，整个功能等于没开。
                // 内容变化事件带包名，用它补上「当前是哪个 App」这一步。
                if (pkg != curPkg) {
                    handleWindow(pkg, curAct ?: "", now)
                } else if (!SKIP_PKGS.contains(pkg)) {
                    scan(pkg, curAct, force = false)
                }
            }
        }
    }

    /** 处理一次「前台换成了 pkg/act」，窗口事件与内容变化事件共用。 */
    private fun handleWindow(pkg: String, act: String, now: Long) {
        val from = curPkg
        val fromOther = from == null || from != pkg
        if (from != null && from != pkg) {
            if (prefs.shakeBlock &&
                ShakeGuard.shouldBack(from, pkg, now, lastInteractAt, fgAt)
            ) {
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    ShakeGuard.markBlocked(pkg, now)
                    lastBackAt = now
                    prefs.bumpShake()
                    android.util.Log.i("SkipAd", "摇一摇回退: $from -> $pkg")
                    return
                }
            }
            fgAt = now
        } else if (from == null) {
            fgAt = now
        }
        curPkg = pkg
        curAct = act
        if (pkg == packageName || SKIP_PKGS.contains(pkg)) {
            stopPolling()
            return
        }
        // 开屏窗口只在「从别的应用 / 桌面进来」时开启。
        //
        // 之前这里每次 Activity 变化都重置 windowAt，等于在 App 里每切
        // 一页就重新给 12 秒可点击期 —— 用户刷列表、开弹窗、切页面时
        // 服务一直在后面点，这就是「跳过广告后一直误触」的根因。
        // App 内部切页面不可能是开屏，时间窗必须继续累加。
        //
        // 系统权限框 / 系统 UI 只是临时盖住，用户点完「允许」回来还在同一个
        // 页面上，同样不能当成新的开屏会话。
        val fromOverlay = from != null && from != pkg &&
            SKIP_PKGS.contains(from) && !LAUNCHER_PKGS.contains(from)
        val fresh = fromOther && !fromOverlay
        android.util.Log.i(
            "SkipAd",
            "窗口 $from -> $pkg 新会话=$fresh 开屏窗=" +
                (if (windowAt == 0L) "无" else "${now - windowAt}ms"),
        )
        if (fresh) {
            // 只有真正新进一个应用才允许重新开始点。
            // App 内部切页面（闪屏 -> 主页）也算 Activity 变化，如果在这里
            // 清零，用户刚跳过广告、进主页还能再被点一次。
            windowAt = now
            sessionClicks = 0
            clickCounts.clear()
            lastClickKey = ""
            startPolling()
        }
        scan(pkg, act, force = true)
    }

    // ---------------- 主动轮询 ----------------

    private fun startPolling() {
        if (!prefs.skipAdEnabled) return
        handler.removeCallbacks(pollTask)
        isPolling = true
        // 启动过渡里屏幕上是上一次会话的快照，先等它过去
        handler.postDelayed(pollTask, FIRST_POLL_DELAY_MS)
    }

    private fun stopPolling() {
        isPolling = false
        handler.removeCallbacks(pollTask)
    }

    /** @return true 表示还要继续轮询。 */
    private fun autoPoll(): Boolean {
        val pkg = curPkg ?: return false
        if (!prefs.skipAdEnabled) return false
        if (SKIP_PKGS.contains(pkg) || pkg == packageName) return false
        if (sessionClicks >= MAX_SESSION_CLICKS) return false
        val now = System.currentTimeMillis()
        if (now - windowAt > SPLASH_POLL_MS) return false
        if (scanning) return true
        scan(pkg, curAct, force = false)
        return true
    }

    // ---------------- 扫描 ----------------

    private fun scan(pkg: String, activity: String?, force: Boolean) {
        if (SKIP_PKGS.contains(pkg) || pkg == packageName) return
        val now = System.currentTimeMillis()
        if (scanning) return
        // 一律节流：界面不可能在 200ms 内变出新的跳过按钮，重复全树遍历只会掉帧
        if (now - lastScanAt < 200) return
        scanning = true
        try {
            lastScanAt = now
            val root = rootInActiveWindow
            if (root == null) {
                if (force) android.util.Log.d("SkipAd", "扫描 $pkg 跳过: 拿不到窗口内容")
                return
            }
            val windowAge = now - windowAt
            if (windowAge > SPLASH_POLL_MS) return
            val inSplash = windowAge <= SPLASH_TEXT_MS
            // 事件里的包名可能是上一个窗口的（切回本应用时尤其明显），以窗口根节点的包名为准
            val realPkg = root.packageName?.toString() ?: pkg
            if (realPkg == packageName || SKIP_PKGS.contains(realPkg)) return
            val screen = Rect(
                0, 0,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
            val refs = ArrayList<Ref>(64)
            collect(root, refs, 0, screen)
            if (force) {
                android.util.Log.d("SkipAd", "扫描 $realPkg 节点=${refs.size} 开屏窗=${windowAge}ms")
            }
            if (refs.isEmpty()) return

            // 第一轮：只认「确定是广告控件」的目标 —— SDK 皮肤资源名、用户手写坐标。
            // 这一层不看文字，误点风险最低，所以放在最前面，宁可先点对再谈别的。
            if (SkipEngine.builtin) {
                for (r in refs) {
                    if (r.id.isEmpty()) continue
                    if (SkipSources.sdkHit(r.id) && click(r, now)) return
                }
            }
            for (r in refs) {
                if (SkipEngine.boundsHit(r.bounds) && click(r, now)) return
            }

            // 文本类匹配只在前 9 秒内做
            if (!inSplash) return

            // 第二轮：用户自己写死的弹窗规则（触发 -> 动作）
            for (p in SkipEngine.popups()) {
                var triggered = false
                for (r in refs) {
                    if (p.trigger.hit(r.text, r.desc, r.id, r.cls)) { triggered = true; break }
                }
                if (!triggered) continue
                if (p.back) {
                    if (doBack(now)) return
                    continue
                }
                val act = p.action ?: continue
                for (r in refs) {
                    if (act.hit(r.text, r.desc, r.id, r.cls) && click(r, now)) return
                }
            }

            // 第三轮：文本类。只认「窄控件」——
            // 跳过按钮都是角落里的小标签，而整行宽的列表项、卡片、按钮上
            // 写着「跳过」时，那是用户要点的功能，不是广告入口。
            for (r in refs) {
                if (r.text.isEmpty() && r.desc.isEmpty() && r.id.isEmpty()) continue
                if (r.bounds.width() >= screen.width() * NARROW_RATIO) continue
                if (!SkipEngine.isSafeTarget(r.text, r.desc)) continue

                if (SkipEngine.customHit(r.text, r.desc, r.id, r.cls) && click(r, now)) return
                if (SkipEngine.builtinHit(r.text, r.desc, r.id) && click(r, now)) return
                // 纯图标关闭按钮：只在开屏窗内、且贴在右上角时才认
                if (SkipSources.cornerCloseHit(
                        r.text, r.bounds.right, r.bounds.top,
                        screen.width(), screen.height(),
                    ) && click(r, now)
                ) return
                when (SkipEngine.scopedHit(realPkg, activity, r.text, r.desc, r.id, windowAge, r.cls)) {
                    SkipEngine.Action.CLICK -> if (click(r, now)) return
                    SkipEngine.Action.BACK -> if (doBack(now)) return
                    null -> {}
                }
            }
        } finally {
            scanning = false
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<Ref>,
        depth: Int,
        screen: Rect,
    ) {
        if (node == null || depth > 28 || out.size >= 600) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName ?: ""
        val cls = node.className?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // 不可见的节点永远不点：广告 SDK 常放一个 0×0 或屏幕外的假跳过按钮
            if (node.isVisibleToUser && rect.width() > 0 && rect.height() > 0 &&
                Rect.intersects(rect, screen)
            ) {
                out.add(Ref(node, text, desc, id, rect, cls))
            }
        }
        val n = node.childCount
        for (i in 0 until n) {
            collect(node.getChild(i), out, depth + 1, screen)
            if (out.size >= 600) return
        }
    }

    // ---------------- 动作 ----------------

    private fun click(ref: Ref, now: Long): Boolean {
        if (sessionClicks >= MAX_SESSION_CLICKS) return false
        // 倒计时文本每秒都在变（"跳过 5s" -> "跳过 4s"），直接拿原文当 key
        // 等于没有判重。去掉数字和空白后才是同一个控件。
        val key = ref.id + "|" + ref.text.replace(NUM, "") + "|" + ref.desc.replace(NUM, "")
        if ((clickCounts[key] ?: 0) >= MAX_SAME_CLICKS) return false
        // 本窗口已经点过一次，就只允许对「同一个控件」重试。
        // 广告点掉之后页面上还有别的弹窗 / 列表项，绝不能顺手再点。
        if (sessionClicks >= 1 && key != lastClickKey) return false
        // 全局节流：倒计时文本每次都不同，同一个 key 的判重拦不住连点
        if (now - lastClickAt < CLICK_GAP_MS) return false
        if (!ref.node.isVisibleToUser) return false
        if (!SkipEngine.isSafeTarget(ref.text, ref.desc)) return false

        // 自己可点就点，不可点就向上找可点击祖先（很多跳过按钮是 FrameLayout 里的 TextView）
        var node: AccessibilityNodeInfo? = ref.node
        var depth = 0
        while (node != null && depth <= 3) {
            if (node.isClickable && node.isEnabled &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                mark(key, now)
                return true
            }
            node = node.parent
            depth++
        }

        // 自绘控件既不可点也拿不到可点击祖先，退化成按坐标点一下
        if (now - lastGestureAt > 700 && ref.bounds.width() > 20 && ref.bounds.height() > 20) {
            val path = Path().apply {
                moveTo(ref.bounds.exactCenterX(), ref.bounds.exactCenterY())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
            lastGestureAt = now
            if (dispatchGesture(gesture, null, null)) {
                mark(key, now)
                return true
            }
        }
        return false
    }

    private fun doBack(now: Long): Boolean {
        if (now - lastBackAt < 800) return false
        if (sessionClicks >= MAX_SESSION_CLICKS) return false
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) return false
        lastBackAt = now
        lastClickAt = now
        lastClickKey = "back"
        sessionClicks++
        prefs.bumpSkip()
        return true
    }

    private fun mark(key: String, now: Long) {
        lastClickKey = key
        lastClickAt = now
        sessionClicks++
        clickCounts[key] = (clickCounts[key] ?: 0) + 1
        prefs.bumpSkip()
        android.util.Log.i("SkipAd", "已点击跳过: $key")
    }

    // ---------------- 规则热更新 ----------------

    private fun syncEngine() {
        SkipEngine.builtin = prefs.skipBuiltin
        val cache = prefs.skipSubCache
        val custom = prefs.customRules
        if (cache != lastCache || custom != lastCustom) {
            lastCache = cache
            lastCustom = custom
            loadRules(cache, custom)
        }
    }

    /** 后台解析规则，解析完成后 [SkipEngine] 会一次性换掉快照。 */
    private fun loadRules(cache: String, custom: String) {
        ruleLoader.execute {
            runCatching {
                SkipEngine.ensureLoaded(cache, custom)
                android.util.Log.i(
                    "SkipAd",
                    "规则已载入: 订阅组=${SkipEngine.groupCount()} 订阅规则=${SkipEngine.ruleCount()} " +
                        "自定义关键词=${SkipEngine.keywordCount()} 弹窗=${SkipEngine.popupCount()}",
                )
            }.onFailure { android.util.Log.w("SkipAd", "规则解析失败: ${it.message}") }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopPolling()
        ruleLoader.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        /** 文本层泛化匹配的时间窗：窗口切换后这段时间内才算「开屏」。 */
        const val SPLASH_TEXT_MS = 9000L

        /** 轮询持续时长。比文本层长一点，覆盖「广告图先出、跳过按钮后出」。 */
        const val SPLASH_POLL_MS = 12000L

        /** 主动轮询间隔。700ms 兼顾命中速度与耗电。 */
        const val POLL_MS = 700L

        /** 首次轮询延时，避开 OEM 启动过渡里的上一帧快照。 */
        const val FIRST_POLL_DELAY_MS = 420L

        /** 同一个窗口内总点击上限：一次首点 + 一次失败重试。 */
        const val MAX_SESSION_CLICKS = 2

        /** 同一个控件最多点几次（点完没跳走就说明没生效，再试一次即可）。 */
        const val MAX_SAME_CLICKS = 2

        /** 两次点击之间的最小间隔。 */
        const val CLICK_GAP_MS = 800L

        /** 文本类匹配只认比这更窄的控件（跳过按钮都是小标签）。 */
        const val NARROW_RATIO = 0.45f

        /** 去掉数字后比较，倒计时文本才认得出是同一个按钮。 */
        val NUM = Regex("[0-9\\s]")

        /** 桌面：从桌面启动 App 是最典型的开屏场景，必须算「新会话」。 */
        val LAUNCHER_PKGS = setOf(
            "com.miui.home", "com.android.launcher", "com.android.launcher3",
            "com.oppo.launcher", "com.huawei.android.launcher", "com.vivo.launcher",
        )

        /** 桌面 / 系统界面不可能有开屏广告，扫它们纯属浪费电。 */
        val SKIP_PKGS = LAUNCHER_PKGS + setOf(
            "com.android.systemui", "com.miui.securitycenter", "com.lbe.security.miui",
            "com.android.settings",
            "android", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.miui.packageinstaller",
            // 输入法会以各种形式侵入当前页面，误判成「切了应用」只会白白扫描
            "com.sohu.inputmethod.sogou", "com.iflytek.inputmethod",
            "com.baidu.input", "com.tencent.qqpinyin", "com.google.android.inputmethod.latin",
            "com.miui.inputmethod", "com.iflytek.inputmethod.miui",
        )
    }
}
