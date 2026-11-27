package com.chengliu.app.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 自动跳过开屏广告 / 弹窗 + 摇一摇落地页回退。
 *
 * 三条来自开源实现（GKD / Android-Touch-Helper / ad-skipper）的关键经验：
 *
 *  1. **不能只靠事件驱动**。开屏广告往往在启动过渡结束之后才渲染，而且不发
 *     任何无障碍事件，只监听 `TYPE_WINDOW_CONTENT_CHANGED` 会整场错过。
 *     所以进入新窗口后有一段时间的主动轮询（见 [pollTask]）。
 *  2. **启动过渡期间屏幕上是上一次会话的快照**，换窗口那次 force 扫描本来
 *     就拿不到按钮，所以主动轮询只需等 [FIRST_POLL_DELAY_MS] 让动画过去。
 *  3. **点完要复查**。命中后如果按钮还在，说明那一下没生效（常见于倒计时
 *     控件吞掉了点击），下一轮继续试，但同一个控件最多 [MAX_SAME_CLICKS] 次，
 *     避免在页面上反复戳同一个位置。
 *
 * 所有判断都在本机完成，不读取、不上传、不落盘任何界面内容。
 */
class SkipAdService : AccessibilityService() {

    /**
     * 节点树的**短期快照**：全部是纯数据，不持有 AccessibilityNodeInfo。
     * 真正的节点只在本次扫描期间放在 [scanNodes] 里，扫描结束后统一释放。
     */
    private data class Ref(
        val nodeIndex: Int,
        val text: String,
        val desc: String,
        val id: String,
        val bounds: Rect,
        val cls: String,
        val clickable: Boolean,
    )

    private val prefs by lazy { AppPrefs(this) }
    private val handler = Handler(Looper.getMainLooper())

    /** 规则解析单线程：几百 KB 的订阅不能在主线程解析，否则抬手就是 ANR。 */
    private val ruleLoader = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 取树专用线程。`rootInActiveWindow` 与节点属性全是跨进程调用，单次 2~645ms，
     * 必须离开无障碍主线程，否则事件处理和点击都被堵在队列后面。
     * 单线程是有意的：同一时刻只让一棵树在被读。
     */
    private val scanWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SkipScan")
    }

    private var curPkg: String? = null
    private var curAct: String? = null

    /** 当前窗口的切换时刻，用来算「开屏时间窗」。 */
    private var windowAt = 0L

    /** 前台应用切换时刻（给摇一摇判定用）。 */
    private var fgAt = 0L
    private var lastInteractAt = 0L
    @Volatile
    private var lastScanAt = 0L
    private var lastGestureAt = 0L
    private var lastBackAt = 0L
    private var lastClickKey = ""
    private var lastClickAt = 0L

    /** 上次点击控件「不含位置」的标识与左上角，用来按容差认同一个控件。 */
    private var lastClickBase = ""
    private var lastClickLeft = 0
    private var lastClickTop = 0

    /** 同一个控件本次窗口内点了几次。 */
    private val clickCounts = HashMap<String, Int>()

    /** 本次窗口累计点击次数，封顶后不再折腾。 */
    private var sessionClicks = 0
    @Volatile
    private var scanning = false
    private var lastCache = ""
    private var lastCustom = ""
    private var eventCount = 0
    private var isPolling = false


    /** 本次服务实例在 [SkipAdState] 里的令牌，销毁时只清自己。 */
    private var stateToken = 0

    /** 当前应用的跳过策略；用户在界面改档位后最多 5 秒生效。 */
    private var currentPolicy = SkipPolicy(PolicyMode.FULL)
    private var policyPkg = ""
    private var policyAt = 0L

    /** 本次扫描的上下文，click() 用它做广告类型判定。无障碍回调都在主线程。 */
    private var scanInSplash = false
    private var scanScreenW = 0
    private var scanScreenH = 0

    /** 应用内弹窗每个会话最多关一次，且必须避开用户正在操作的时候。 */
    private var popupClicks = 0
    private var lastPopupScanAt = 0L

    private var lastRejectLogAt = 0L

    // ---------------- 评分 / 观察 / 冷却 / 扫描优化 ----------------

    /** 点击成功后进入冷却，同窗口内不再点；换窗口（fresh）时清零。 */
    private var cooldownUntil = 0L

    // ---------------- 点击效果复核 ----------------
    // 「点到了」和「跳过了」是两件事：performAction 返回 true 只说明控件接受了动作。
    // 点击后隔 [EFFECT_CHECK_MS] 回来复核页面有没有真的变化，变了才算跳过成功。

    /** 已发出、等待复核的那次点击。 */
    private var pendingDecision: Decision? = null
    private var pendingPkg: String? = null
    private var pendingAct: String? = null
    private var pendingViewId = ""
    private var pendingKey = ""
    private var pendingAt = 0L
    private var pendingWindowAt = 0L
    private var pendingVerifyMs = 0

    /** 第一次复核还挂着控件时，是否已经放过宽限轮。 */
    private var pendingGraceUsed = false

    private val effectCheck = object : Runnable {
        override fun run() {
            verifyEffect()
        }
    }

    /** 本窗口是不是「从外部新进来的」，评分时用来决定是否重置冷却。 */
    private var windowFresh = false

    /** 本次扫描是不是「12 秒之后的弹窗轮」。 */
    private var scanPopupRound = false

    /** 安全观察模式缓存值，由 syncEngine() 跟随配置热更新。 */
    @Volatile
    private var observe = false
    /** 快路径：按上一轮命中的控件 id 直取节点，省掉整棵树遍历。 */
    private var fastPath = true

    /** 高级开关缓存：附加动作、圆形倒计时、手势兜底、跳过提示。每次事件同步一次。 */
    private var extraTeen = false
    private var extraWechat = false
    @Volatile
    private var extraInstall = false
    /** 安装拦截测试模式：只放开安装拦截的 2 次上限，300ms 冷却与 600ms 扫描节流仍在。 */
    private var installTestMode = false
    private var gestureFallback = true
    private var skipToast = true
    private var toastText = ""
    private var lastToastAt = 0L

    /** 附加动作节流，避免每轮轮询都做一次全树扫描。 */
    private var lastExtraAt = 0L

    /** 安装弹窗节流：安装器窗口自己一套，不占广告扫描的节流。 */
    private var lastInstallAt = 0L

    /** 本次遍历的绝对截止时刻，超过就停，避免畸形界面把主线程拖住。 */
    private var scanDeadline = 0L

    /** 中置信度等待二次证据：key -> (上次时间, 上次分数)。 */
    private val evidence = HashMap<String, Pair<Long, Int>>()

    /** 观察记录按目标去重：key -> 上次记录时间。 */
    private val lastRecordAt = HashMap<String, Long>()

    /** 本轮扫描命中的规则名；扫描结束时用它把心跳结论标成「命中」。 */
    private var scanHitRule: String? = null
    /** 本轮扫描里二次验证累计花掉的毫秒，只为诊断。 */
    private var scanVerifyMs = 0

    /**
     * 空转退避：连续几轮扫不到任何候选，就把内容变化事件的合并窗口拉长。
     * 逻辑全在 [ScanPacer] 里（纯函数、可单测），这里只负责喂结果。
     */
    private val pacer = ScanPacer(SCAN_MERGE_MS, SCAN_MERGE_MS, SCAN_MERGE_MAX_MS)

    /** 当前窗口有没有已经因为「内容变化」扫过一次：第一次立即扫，之后按退避走。 */
    private var contentSeenInWindow = false

    /**
     * 桌面过渡期补扫状态。
     *
     * 冷启动那一瞬，事件里的包名已经是目标应用，活动窗口却还停在桌面：
     * 直接丢掉这一轮，等于白丢开屏窗最早的一次机会（v1.4.0 真机实测丢掉 19 次，
     * 斗鱼 / 百度地图 / 高德 / 飞猪都中招）。补扫不换树、不放宽任何闸门，
     * 只是 180ms 后再走一次正常的 rootInActiveWindow + verifyBeforeClick。
     */
    private var launcherRetryPkg: String? = null
    private var launcherRetryAt = 0L
    private var launcherRetryCount = 0
    private val launcherRetry = object : Runnable {
        override fun run() {
            val p = launcherRetryPkg ?: return
            launcherRetryPkg = null
            scan(p, curAct, force = true)
        }
    }

    /** 应用名缓存：心跳每条都要显示应用名，不能每次都问 PackageManager。 */
    private val labelCache = HashMap<String, String>()

    /**
     * 本次扫描期间持有的节点引用。Ref 只存这里的下标，扫描一结束
     * （finally）就逐个 recycle —— AccessibilityNodeInfo 不是普通数据对象，
     * 不能跨扫描缓存，也不能等 GC 慢慢收。
     */
    private val scanNodes = ArrayList<AccessibilityNodeInfo>(128)

    /** 内容变化事件的短时合并：开屏窗之外的连续变化只扫一次。 */
    private var pendingScanPkg: String? = null
    // 用对象表达式 + this 重排自己：在 Runnable lambda 里直接引用同名 val
    // 会让编译器报「Variable must be initialized」。
    private val pendingScan: Runnable = object : Runnable {
        override fun run() {
            val pkg = pendingScanPkg ?: return
            if (!prefs.skipAdEnabled) {
                pendingScanPkg = null
                return
            }
            // 合并后的这一轮若撞上扫描节流，必须重排一次：跳过按钮常常是
            // 「延迟渲染」后才出现，那次内容变化事件被丢掉就再也不会扫了。
            val now = System.currentTimeMillis()
            val wait = scanGap(now) - (now - lastScanAt)
            if (wait > 0) {
                handler.removeCallbacks(this)
                handler.postDelayed(this, wait)
                return
            }
            pendingScanPkg = null
            scan(pkg, curAct, force = false)
        }
    }

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
            // 跳过按钮常在开屏后 0~3 秒才延迟渲染，这段窗口内把轮询压到
            // POLL_FAST_MS，按钮一出现就能扫到。命中后或 3 秒之后回落到
            // 常规间隔，避免整个 12 秒开屏窗都高频取树。
            val now = System.currentTimeMillis()
            val fast = sessionClicks == 0 && now - windowAt <= POLL_FAST_WINDOW_MS
            handler.postDelayed(this, if (fast) POLL_FAST_MS else POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 系统设置里「已开启」不等于服务真的连上了（OEM 省电策略会假绑定）。
        // 只有走到这里，才说明本进程确实收到了系统的绑定，界面据此显示「正在工作」。
        stateToken = SkipAdState.onConnected()
        android.util.Log.i("SkipAd", "无障碍服务已连上")
        // 系统重新绑定成功时补一次保活，避免之后又被省电策略回收。
        SkipGuardService.ensure(this)
        syncFlags()
        // 桌面包名问系统要，别硬编码：MIUI 叫 com.miui.home，换 ROM 就换名字。
        refreshLauncherPkgs(resolveHomePkgs())
        SkipEngine.builtin = prefs.skipBuiltin
        lastCache = prefs.skipSubCache
        lastCustom = prefs.customRules
        loadRules(lastCache, lastCustom)
        // MIUI 会在安装框弹出瞬间把无障碍服务踢掉，2 秒后才重连。重连时窗口已经
        // 静止、不再发事件，若只被动等事件就永远补不上那一帧。这里连上后主动
        // 补扫一次：窗口是安装器就走拦截，普通应用就走广告扫描。延迟给窗口渲染。
        handler.removeCallbacks(reconnectScan)
        handler.postDelayed(reconnectScan, RECONNECT_SCAN_DELAY_MS)
    }

    /** 服务重连后的补扫：专门兜住「安装框弹出时服务被踢、重连后无事件」的场景。 */
    private val reconnectScan = Runnable {
        val root = rootInActiveWindow ?: return@Runnable
        try {
            val pkg = root.packageName?.toString() ?: return@Runnable
            val now = System.currentTimeMillis()
            android.util.Log.i("SkipAd", "重连补扫: pkg=$pkg")
            if (INSTALLER_PKGS.contains(pkg)) {
                // 走独立入口，但绕过 INSTALL_SCAN_GAP_MS 节流（刚断连，节流无意义）。
                if (extraInstall && !observe) {
                    lastInstallAt = 0L
                    handleInstallWindow(pkg, now)
                }
            } else if (prefs.skipAdEnabled && pkg != packageName && !isSkipPkg(pkg)) {
                windowAt = now
                windowFresh = true
                scan(pkg, "", force = true)
            }
        } finally {
            recycleNode(root)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        SkipAdState.lastEventAt = System.currentTimeMillis()
        // 事件过滤：只认下面四类。通知、公告、触摸探索等一律不触发任何树遍历。
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (eventCount < 20) {
            eventCount++
            android.util.Log.i("SkipAd", "事件 type=$type pkg=${e.packageName}")
        }
        val now = System.currentTimeMillis()
        // 安装拦截是「附加动作」里的独立开关（blockInstall），不是广告跳过的子功能。
        // 必须在 skipAdEnabled 闸门之前分流：否则只开安装拦截、没开广告跳过时，
        // 下面那个 return 会把所有安装器事件挡掉，拦截 100% 失效。
        //
        // 所有安装器事件都在这里收口，绝不落进 handleWindow / handleWindow 的
        // 摇一摇回退与 curPkg 会话逻辑 —— 安装器本来就在 SKIP_PKGS，进广告链
        // 既没有意义，还可能对确认框误触返回键。
        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            val pkg = e.packageName?.toString()
            if (pkg != null && INSTALLER_PKGS.contains(pkg)) {
                syncFlags() // 配置热更新：blockInstall / installTestMode / observe
                if (extraInstall && !observe) {
                    // handleInstallWindow 内部还有 600ms 扫描节流与开关闸门。
                    handleInstallWindow(pkg, now)
                }
                return
            }
        }
        if (!prefs.skipAdEnabled) {
            stopPolling()
            return
        }
        syncEngine()

        when (type) {
            // 触摸类事件只更新「用户正在操作」时间戳，不扫描节点树。
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> {
                lastInteractAt = now
                // 用户自己在动，画面随时可能翻页，退避立刻归零。
                pacer.reset()
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                if (pkg == "com.android.systemui") return
                handler.removeCallbacks(pendingScan)
                pendingScanPkg = null
                handleWindow(pkg, e.className?.toString() ?: "", now)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                // 系统 UI 会持续发内容变化（状态栏时钟、通知），如果放它进来，
                // curPkg 会在 App 和 systemui 之间来回翻，每次切换都把点击计数
                // 清零，防连点的保护就废了。桌面同理。
                if (pkg == packageName || pkg == "com.android.systemui") return
                if (isSkipPkg(pkg)) return
                // 兜底：无障碍服务如果是在 App 已经在前台之后才连上的
                // （重装、系统回收、手动开启都是这种情况），就再也收不到
                // TYPE_WINDOW_STATE_CHANGED，整个功能等于没开。
                // 内容变化事件带包名，用它补上「当前是哪个 App」这一步。
                if (pkg != curPkg) {
                    handler.removeCallbacks(pendingScan)
                    pendingScanPkg = null
                    // 这条兜底路径拿不到 Activity，绝不能把上一个 App 的
                    // className 带过来；传空串让 scopedHit 按「宁可漏点」处理。
                    handleWindow(pkg, "", now)
                    return
                }
                // 开屏窗之外才做时间合并：倒计时文本每秒变、列表滚动连发事件，
                // 120ms 内的连续变化只做一次全树遍历。开屏初期直接立即扫，
                // 不再白等一整个合并窗口。
                pendingScanPkg = pkg
                // 同一条内容变化事件会被连发好几十次：先把上一次还没跑的补扫撤掉，
                // 否则队列里攒一堆扫描，跑起来就是一串「扫完立刻再扫」。
                handler.removeCallbacks(pendingScan)
                // 开屏窗的第一次内容变化立即扫（抢首帧，真机实测这一步能压到十几毫秒），
                // 之后的连续变化按空转退避走，开屏窗内封在 SPLASH_MERGE_MAX_MS。
                val mergeMs = when {
                    !inFastWindow(now) -> pacer.mergeDelayMs
                    !contentSeenInWindow -> SCAN_MERGE_FAST_MS
                    else -> pacer.mergeDelayMs.coerceAtMost(SPLASH_MERGE_MAX_MS)
                }
                contentSeenInWindow = true
                handler.postDelayed(pendingScan, mergeMs)
            }

            // 其它事件在函数开头已被 return 掉；这里显式收尾，避免 lint 的 SwitchIntDef 告警。
            else -> {}
        }
    }

    /**
     * 桌面过渡期补扫：活动窗口还是桌面、事件包名已经是目标应用时补一次短延迟重扫。
     * 只补 [LAUNCHER_RETRY_MAX] 次、两次之间至少 [LAUNCHER_RETRY_GAP_MS]，
     * 免得用户就停在桌面上时无休止地空转。
     */
    private fun scheduleLauncherRetry(pkg: String, winPkg: String, now: Long) {
        if (!isSkipPkg(winPkg)) return
        if (pkg == packageName || isSkipPkg(pkg)) return
        if (launcherRetryCount >= LAUNCHER_RETRY_MAX) return
        if (now - launcherRetryAt < LAUNCHER_RETRY_GAP_MS) return
        launcherRetryAt = now
        launcherRetryCount++
        launcherRetryPkg = pkg
        handler.removeCallbacks(launcherRetry)
        handler.postDelayed(launcherRetry, LAUNCHER_RETRY_DELAY_MS)
    }

    /** 处理一次「前台换成了 pkg/act」，窗口事件与内容变化事件共用。 */
    private fun handleWindow(pkg: String, act: String, now: Long) {
        // 换窗口 = 新页面开局，空转退避清零，开屏窗必须立刻扫。
        pacer.reset()
        // 新窗口的头一次内容变化仍然立即扫，别让上一个窗口的计数把首帧拖慢。
        contentSeenInWindow = false
        // 新窗口已经在手上了，过渡期那次补扫就没意义了，先撤掉。
        handler.removeCallbacks(launcherRetry)
        launcherRetryPkg = null
        launcherRetryCount = 0
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
        if (pkg == packageName || isSkipPkg(pkg)) {
            stopPolling()
            return
        }
        // 最近使用：只在真正切到别的 App 时记一次，桌面 / 权限框不算。
        if (!LAUNCHER_PKGS.contains(pkg)) prefs.recordRecentApp(pkg)
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
        // App 内部切页面不算新会话：冷却与去重都不能在这里被清掉，
        // 否则刷列表时每切一页都能重新获得一次点击机会。
        windowFresh = fresh
        if (fresh) {
            // 只有真正新进一个应用才允许重新开始点。
            // App 内部切页面（闪屏 -> 主页）也算 Activity 变化，如果在这里
            // 清零，用户刚跳过广告、进主页还能再被点一次。
            windowAt = now
            sessionClicks = 0
            clickCounts.clear()
            lastClickKey = ""
            lastClickBase = ""
            popupClicks = 0
            cooldownUntil = 0L
            evidence.clear()
            // key 是 id|text|desc 组合，跨 App 只增不减；服务是长期进程，
            // 换窗口时清掉，避免观察记录去重表无界增长。
            lastRecordAt.clear()
            startPolling()
        }
        scan(pkg, act, force = true)
    }

    // ---------------- 主动轮询 ----------------

    private fun startPolling() {
        if (!prefs.skipAdEnabled) return
        handler.removeCallbacks(pollTask)
        isPolling = true
        // 过渡帧那次扫描已由 handleWindow 的 force 覆盖，这里只等动画过去
        handler.postDelayed(pollTask, FIRST_POLL_DELAY_MS)
    }

    private fun stopPolling() {
        isPolling = false
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(pendingScan)
        pendingScanPkg = null
    }

    /**
     * 开屏最初的「按钮延迟渲染期」。命中过一次就退出：首点之后只剩
     * CLICK_GAP_MS=800 的失败重试，常规 700ms 轮询足够，不必继续高频取树。
     */
    private fun inFastWindow(now: Long): Boolean =
        sessionClicks == 0 && now - windowAt <= POLL_FAST_WINDOW_MS

    /** 非强制扫描的最小间隔；只在开屏初期放宽，其余时间保持 200ms 省电。 */
    private fun scanGap(now: Long): Long =
        if (inFastWindow(now)) SCAN_GAP_FAST_MS else SCAN_GAP_MS

    /** @return true 表示还要继续轮询。 */
    private fun autoPoll(): Boolean {
        val pkg = curPkg ?: return false
        if (!prefs.skipAdEnabled) return false
        if (isSkipPkg(pkg) || pkg == packageName) return false
        if (sessionClicks >= MAX_SESSION_CLICKS) return false
        val now = System.currentTimeMillis()
        if (now - windowAt > SPLASH_POLL_MS) return false
        if (scanning) return true
        scan(pkg, curAct, force = false)
        return true
    }

    // ---------------- 扫描 ----------------

    /**
     * 安装器窗口的独立入口：只做安装弹窗拦截，不进广告判定链。
     *
     * 安装器本来就在 SKIP_PKGS 里（扫广告没意义），只有用户打开安装拦截才放行。
     * 刻意不复用 `windowAt` / `sessionClicks` —— 那是「开屏广告会话」的概念，
     * 而安装弹窗可能在任何时刻弹出，跟当前 App 的会话毫无关系。
     */
    private fun handleInstallWindow(pkg: String, now: Long) {
        if (!extraInstall || observe) return
        if (now - lastInstallAt < INSTALL_SCAN_GAP_MS) return
        // 上一轮扫描（可能是广告扫描）还没结束时，scan() 开头的 scanning 守卫
        // 会把这一帧直接丢掉。这里**不能**先记 lastInstallAt —— 否则节流反而
        // 堵住 600ms 内的重试，安装弹窗就漏了这一帧。留空让下一个安装事件重试。
        if (scanning) return
        // 刻意**不**检查 lastInteractAt：安装弹窗本来就是用户刚点完广告才弹出来的，
        // 用「用户 900ms 内操作过就退让」会把拦截 100% 挡掉。防连点靠 clickCounts 去重。
        lastInstallAt = now
        scan(pkg, "", force = true)
    }

    /**
     * 释放无障碍节点句柄。recycle() 自 API 33 起标记废弃（系统改为自动管理），
     * 但本项目 minSdk=26：老设备上不主动回收会泄漏跨进程句柄，故保留调用并就地抑制告警。
     */
    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        runCatching { node?.recycle() }
    }

    /**
     * 扫描分三个场景，越往后越保守：
     *  A 开屏 0~9 秒：明确信号 + 订阅 / 自定义 / 弹窗规则
     *  B 开屏 9~12 秒：只认明确信号（倒计时结束、广告延迟渲染后才出现的按钮）
     *  C 12 秒之后：只处理带明确广告信号的推广弹窗，且要策略允许、用户空闲
     *
     * 无论哪一轮命中，最后都要过 [click] 里的广告类型 + 应用策略闸门。
     */
    /**
     * 扫描入口（主线程）。这里只做闸门与节流，重活丢给 [scanWorker]。
     *
     * 为什么拆线程：真机实测一次 `rootInActiveWindow` + `collect` 要 2~645ms，全是跨进程
     * 读属性。它原来跑在无障碍主线程上，把主线程堵住之后消息队列能堆到 3 秒
     * （`PerfMonitor late=3016ms`）、本进程 `Skipped 114 frames`，点击因此被推迟——
     * 「跳过广告慢」的真正原因在取树，不在规则匹配（匹配段实测 2~4ms）。
     * 拆开之后：主线程仍独占全部点击与状态，取的只是换个线程读。
     */
    private fun scan(pkg: String, activity: String?, force: Boolean) {
        if (pkg == packageName) return
        // 安装器在 SKIP_PKGS 里（扫广告没意义），只有开了安装拦截才放行。
        if (isSkipPkg(pkg) && !(extraInstall && INSTALLER_PKGS.contains(pkg))) return
        val now = System.currentTimeMillis()
        if (scanning) {
            // content-changed 触发的重复扫描不记心跳，否则列表会被「被节流合并」刷满。
            if (force) beat(now, pkg, 0, 0, BeatStage.SKIPPED, BeatResult.THROTTLED, "上一次扫描还没结束")
            return
        }
        // 事件合并的重复扫描节流即可；换窗口的强制扫描是新一轮开屏唯一的
        // 一次立即扫描，绝不能被上一个窗口留下的 lastScanAt 挡掉。
        if (!force && now - lastScanAt < scanGap(now)) return
        scanning = true
        // 槽位在派活的那一刻就占住：worker 在跑的时候来的事件应该被上面那道闸挡掉，
        // 而不是排队堆起来——堆起来的每一轮都是几百毫秒的跨进程读。
        lastScanAt = now
        scanHitRule = null
        scanVerifyMs = 0
        val startedAt = android.os.SystemClock.elapsedRealtime()
        scanDeadline = startedAt + SCAN_TIMEOUT_MS
        try {
            scanWorker.execute { readTree(pkg, activity, force, now, startedAt) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 服务正在销毁（worker 已 shutdownNow）：把槽位放掉，别让扫描状态永久卡在 true。
            scanning = false
            android.util.Log.d("SkipAd", "扫描线程已关闭，跳过本次")
        }
    }

    /** 一次扫描要用的节点树快照，跨线程传递用；纯数据，不持有节点。 */
    private class TreeRead(
        val pkg: String,
        val activity: String?,
        val force: Boolean,
        val now: Long,
        val startedAt: Long,
        val treeMs: Int,
        val rootMs: Int,
        /** 这一轮的节点是不是「按 id 直取」拿到的。 */
        val fast: Boolean,
        /** 按 id 查询本身花掉的时间。 */
        val idMs: Int,
        /** 这一轮命中的 viewId；没走快路径时为空串。 */
        val fastId: String,
        val realPkg: String,
        val screen: Rect,
        val refs: List<Ref>,
        val abortResult: BeatResult?,
        val abortNote: String,
    )

    /**
     * 取树（worker 线程）：只读窗口内容，不碰任何点击状态。
     *
     * 系统窗口 / 本应用 / 策略关闭的窗口连 [collect] 都不做，结论照样交回主线程记心跳——
     * 「银行类连窗口内容都不看」这条性质没有丢。
     */
    private fun readTree(pkg: String, activity: String?, force: Boolean, now: Long, startedAt: Long) {
        val deadline = startedAt + SCAN_TIMEOUT_MS
        scanNodes.clear()
        var treeMs = 0
        var rootMs = 0
        var fast = false
        var fastId = ""
        var idMs = 0
        var realPkg = pkg
        var screen = Rect(0, 0, 0, 0)
        val refs = ArrayList<Ref>(64)
        var abortResult: BeatResult? = null
        var abortNote = ""
        try {
            val root = rootInActiveWindow
            if (root == null) {
                abortResult = BeatResult.NO_TREE
                abortNote = "拿不到窗口内容"
            } else {
                // root 也是本次扫描的短期引用，主线程收尾时和其他节点一起释放。
                scanNodes.add(root)
                // 事件里的包名可能是上一个窗口的（切回本应用时尤其明显），以窗口根节点的包名为准
                realPkg = root.packageName?.toString() ?: pkg
                val dm = resources.displayMetrics
                screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
                // 取树的第一跳（拿窗口根节点）单独计时：那是系统 API，不归我们控制，
                // 能优化的只有后面的遍历，所以两者必须分开记。
                rootMs = (android.os.SystemClock.elapsedRealtime() - startedAt).toInt()
                when {
                    realPkg == packageName || isSkipPkg(realPkg) -> {
                        abortResult = BeatResult.SELF
                        abortNote = "窗口属于系统或本应用"
                    }
                    INSTALLER_PKGS.contains(realPkg) -> {
                        // 安装器窗口只跑安装弹窗拦截（还要接着匹配，所以取树）
                        if (extraInstall && !observe) collect(root, refs, 0, screen, deadline, 0)
                        else {
                            abortResult = BeatResult.SELF
                            abortNote = "安装器界面"
                        }
                    }
                    // 银行 / 支付类：连窗口内容都不看，也不要留任何误点的可能。
                    // 观察模式例外：只识别、只记录，依然不会执行任何点击。
                    !observe && SkipPolicyStore.forPkg(this, realPkg).mode == PolicyMode.DISABLED -> {
                        abortResult = BeatResult.POLICY
                        abortNote = "应用策略为不自动点击"
                    }
                    else -> {
                        // 快路径：上一轮在这一页点中过某个控件，就直接按它的 viewId 问系统要节点，
                        // 省掉「把整棵树每个节点的属性都读一遍」那一段（真机实测 ~110ms）。
                        // 拿到的仍是真节点，照样要过 click() 里的全部闸门与二次验证——
                        // 这条路径省的是「找」，不是「验」。
                        val hinted = if (fastPath) HitMemory.viewIdFor(realPkg) else null
                        // 记住的控件如果刚被判定过「点了没反应」，这一轮别再用它：
                        // 老实扫全树，让补位逻辑去同容器里找那个真能用的。
                        val remembered = hinted?.takeUnless {
                            DeadTargets.isDead(realPkg, it, System.currentTimeMillis())
                        }
                        if (remembered != null) {
                            val idStart = android.os.SystemClock.elapsedRealtime()
                            val found = runCatching {
                                root.findAccessibilityNodeInfosByViewId(remembered)
                            }.getOrNull()
                            idMs = (android.os.SystemClock.elapsedRealtime() - idStart).toInt()
                            val hit = found?.firstOrNull { it.isVisibleToUser }
                            // 同一个 id 可能命中多个节点（隐藏的兄弟控件）。
                            // 除了要给 click() 用的那一个，其余立刻还回去，否则句柄会攒着。
                            found?.forEach { if (it !== hit) recycleNode(it) }
                            val ref = if (hit == null) {
                                null
                            } else {
                                scanNodes.add(hit)
                                refOf(hit, scanNodes.size - 1, screen)
                            }
                            if (ref != null) {
                                refs.add(ref)
                                fast = true
                                fastId = remembered
                            } else {
                                // 这一轮没拿到，不等于 id 失效：开屏按钮常常是「还没渲染出来」
                                // 或者「刚被我们点掉」。真机实测（某长视频应用）：点中之后再查这个 id
                                // 就一个节点都查不到了，如果在这里忘掉，下次冷启动永远用不上记忆。
                                // 所以记忆只当提示用：查不到就老实走完整扫描，条目不删，
                                // 等下一次点中别的控件时自然覆盖。
                                collect(root, refs, 0, screen, deadline, 0)
                            }
                        } else {
                            collect(root, refs, 0, screen, deadline, 0)
                        }
                    }
                }
                treeMs = (android.os.SystemClock.elapsedRealtime() - startedAt).toInt()
            }
        } catch (t: Throwable) {
            // 跨进程读节点随时会抛（窗口被销毁、句柄失效）。worker 线程不能因此死掉，
            // 否则之后所有扫描都会静默失效——这类坑 Json5 那次已经踩过一次。
            abortResult = BeatResult.NO_TREE
            abortNote = "读节点树失败: ${t.javaClass.simpleName}"
            treeMs = (android.os.SystemClock.elapsedRealtime() - startedAt).toInt()
        }
        val read = TreeRead(
            pkg = pkg,
            activity = activity,
            force = force,
            now = now,
            startedAt = startedAt,
            treeMs = treeMs,
            rootMs = rootMs,
            fast = fast,
            idMs = idMs,
            fastId = fastId,
            realPkg = realPkg,
            screen = screen,
            refs = refs,
            abortResult = abortResult,
            abortNote = abortNote,
        )
        val posted = handler.post { finishScan(read) }
        if (!posted) {
            // 主线程都排不上（服务正在销毁）：把句柄放掉，别泄漏。
            for (n in scanNodes) recycleNode(n)
            scanNodes.clear()
            scanning = false
        }
    }

    /** 扫描的第二段（主线程）：树已经取好，这里做策略、匹配、点击，并统一收尾。 */
    private fun finishScan(t: TreeRead) {
        // 用「此刻」而不是派发时刻：取树在 worker 上跑，回来时 windowAt 可能已经被新的
        // 窗口事件重置过，拿派发时刻算年龄会得到负数（真机日志出现过 开屏窗=-1ms）。
        // 必须与 windowAt 同源（System.currentTimeMillis），不能用 elapsedRealtime。
        val now = System.currentTimeMillis()
        val realPkg = t.realPkg
        val refs = t.refs
        val screen = t.screen
        val force = t.force
        val startedAt = t.startedAt
        val treeMs = t.treeMs
        val rootMs = t.rootMs
        val fast = t.fast
        val idMs = t.idMs
        var beatPkg = realPkg
        // 心跳结论先按「扫完未命中」兜底，各分支只负责改写它。
        var beatStage = BeatStage.NORMAL
        var beatResult = BeatResult.DONE
        var beatNote = ""
        var nodeCount = 0
        var candidateCount = 0
        val windowAge = now - windowAt
        val inPoll = windowAge <= SPLASH_POLL_MS
        if (t.abortResult != BeatResult.NO_TREE) {
            beatStage = if (inPoll) BeatStage.SPLASH else BeatStage.POPUP
        }
        try {
            if (t.abortResult != null) {
                beatResult = t.abortResult
                beatNote = t.abortNote
                when (t.abortResult) {
                    BeatResult.SELF -> {
                        android.util.Log.i("SkipAd", "扫描跳过: 活动窗口=$realPkg 事件包名=${t.pkg} force=$force")
                        scheduleLauncherRetry(t.pkg, realPkg, now)
                    }
                    BeatResult.NO_TREE -> if (force) android.util.Log.d("SkipAd", "扫描 ${t.pkg} 跳过: 拿不到窗口内容")
                    BeatResult.POLICY -> if (force) android.util.Log.d("SkipAd", "扫描 $realPkg 跳过: 该应用策略为不自动点击")
                    else -> {}
                }
                return
            }
            // 安装器窗口：只跑安装弹窗拦截，不参与广告策略判定。
            if (INSTALLER_PKGS.contains(realPkg)) {
                android.util.Log.i("SkipAd", "安装器扫描 $realPkg 节点=${refs.size}")
                scanInstall(refs, screen, now, realPkg)
                beatResult = BeatResult.SELF
                beatNote = "安装器界面"
                return
            }
            // 真的扫到了一个应用窗口：过渡期的补扫预算清零。
            launcherRetryCount = 0
            if (realPkg != policyPkg || now - policyAt > 5000) {
                currentPolicy = SkipPolicyStore.forPkg(this, realPkg)
                policyPkg = realPkg
                policyAt = now
            }
            val policy = currentPolicy
            if (policy.mode == PolicyMode.DISABLED && !observe) {
                beatResult = BeatResult.POLICY
                beatNote = "应用策略为不自动点击"
                return
            }
            scanPopupRound = !inPoll
            if (!inPoll) {
                // 开屏窗之外只靠事件驱动，不延长主动轮询；弹窗轮单独节流
                if (!policy.mode.popup && !observe) {
                    beatResult = BeatResult.OUTSIDE
                    beatNote = "该应用未开启弹窗轮"
                    return
                }
                if (now - lastPopupScanAt < 1000) {
                    beatResult = BeatResult.THROTTLED
                    beatNote = "弹窗轮 1 秒内已扫过"
                    return
                }
                lastPopupScanAt = now
                if (now - lastInteractAt < 900 && !observe) {
                    beatResult = BeatResult.BUSY
                    beatNote = "用户在 900ms 内操作过"
                    return
                }
            }
            // 开屏场景按整个 12 秒窗口算（倒计时结束后才出现的按钮也属于开屏），
            // 但订阅 / 文本推断只在前 9 秒做，两者不能混为一谈。
            val inTextWindow = inPoll && windowAge <= SPLASH_TEXT_MS
            scanInSplash = inPoll
            scanScreenW = screen.width()
            scanScreenH = screen.height()
            android.util.Log.i(
                "SkipAd",
                "扫描 $realPkg 节点=${refs.size} 开屏窗=${windowAge}ms 策略=${policy.mode} " +
                    "取树=${treeMs}ms（根=${rootMs} 遍历=${treeMs - rootMs} 快路径=$fast 按id=${idMs}ms）" +
                        "force=$force",
            )
            nodeCount = refs.size
            // 附加动作（青少年弹窗 / 微信原图）默认全关，开关没开时一行退出、零开销。
            if (scanExtra(refs, screen, now, realPkg)) return
            // 候选 = 有文案或 id、够窄、且不是业务按钮。这个数字是心跳里最有用的，
            // 它能直接回答「到底是没扫到，还是扫到了但没有一个算候选」。
            candidateCount = refs.count { r ->
                (r.text.isNotEmpty() || r.desc.isNotEmpty() || r.id.isNotEmpty()) &&
                    r.bounds.width() < screen.width() * NARROW_RATIO &&
                    SkipEngine.isSafeTarget(r.text, r.desc)
            }
            if (refs.isEmpty()) {
                beatResult = BeatResult.EMPTY
                beatNote = "这一轮没拿到任何节点"
                return
            }

            if (!inPoll) {
                scanPopup(refs, screen, now)
                beatResult = BeatResult.NO_CANDIDATE
                beatNote = "弹窗轮没有唯一关闭候选"
                return
            }

            // 第一轮：广告 SDK 控件（最可靠，不看文字、不看 9 秒窗）
            if (SkipEngine.builtin) {
                for (r in refs) {
                    if (r.id.isEmpty()) continue
                    if (SkipSources.sdkHit(r.id) && click(r, now, HitReason.SDK_ID)) return
                    if (SkipSources.sdkCloseHit(r.id) && click(r, now, HitReason.EXPLICIT_TEXT)) return
                }
            }
            for (r in refs) {
                if (SkipEngine.boundsHit(r.bounds) && click(r, now, HitReason.COORDINATE)) return
            }

            // 明确信号轮：倒计时归零、按钮延迟渲染、按钮放在任意一角都能覆盖。
            // 不受 9 秒文本窗限制，但仍在 12 秒开屏窗内。
            if (scanExplicit(refs, screen, now)) return

            // 文本推断只在前 9 秒内做
            if (!inTextWindow) {
                beatResult = BeatResult.TEXT_OVER
                beatNote = "超过 9 秒文本窗，只跑了明确信号轮"
                return
            }

            // 用户自己写死的弹窗规则（触发 -> 动作）
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
                    if (act.hit(r.text, r.desc, r.id, r.cls) &&
                        click(r, now, HitReason.TEXT_GUESS)
                    ) return
                }
            }

            // 文本推断轮：只认「窄控件」—— 整行宽的列表项、卡片上写着「跳过」
            // 时那是用户要点的功能，不是广告入口。
            // 顺序不是遍历顺序：一屏真假两个「跳过」时，先试更像真按钮的那个
            // （自身可点、面积小、贴近屏幕角）。
            for (r in HitRanker.order(refs, ::rankKey)) {
                // 记忆命中优先：这一条是上次真正点掉过广告的控件，只是它常常没有文字
                // （有的应用把真按钮做成一个无字图标），靠文案匹配永远轮不到它。
                if (t.fastId.isNotEmpty() && r.id == t.fastId) {
                    if (click(r, now, HitReason.MEMORY)) return
                    continue
                }
                if (r.text.isEmpty() && r.desc.isEmpty() && r.id.isEmpty()) continue
                if (r.bounds.width() >= screen.width() * NARROW_RATIO) continue
                if (!SkipEngine.isSafeTarget(r.text, r.desc)) continue

                if (SkipEngine.customHit(r.text, r.desc, r.id, r.cls) &&
                    click(r, now, HitReason.TEXT_GUESS)
                ) return
                when (SkipEngine.scopedHit(realPkg, t.activity, r.text, r.desc, r.id, windowAge, r.cls)) {
                    SkipEngine.Action.CLICK -> if (click(r, now, HitReason.SUBSCRIPTION)) return
                    SkipEngine.Action.BACK -> if (doBack(now)) return
                    null -> {}
                }
            }
            beatResult = BeatResult.NO_CANDIDATE
            beatNote = "开屏窗内没有符合条件的跳过目标"
        } finally {
            // 任何一条 return 路径都会走到这里，所以心跳只在这一个地方写，
            // 不会因为新增分支而漏记。命中优先于「没有候选」的兜底结论。
            if (scanHitRule != null) {
                beatResult = BeatResult.HIT
                beatNote = scanHitRule.orEmpty()
            }
            val costMs = (android.os.SystemClock.elapsedRealtime() - startedAt).toInt()
            // 空转退避只认一个事实：这一轮有没有真的点下去。
            pacer.onScan(scanHitRule != null, scanInSplash)
            beat(
                now, beatPkg, nodeCount, candidateCount, beatStage, beatResult, beatNote,
                costMs, treeMs, rootMs, scanVerifyMs, fast, idMs,
            )
            // 扫描完成：把本次持有的所有节点引用一次性释放。
            // AccessibilityNodeInfo 是跨进程句柄，不能当普通对象缓存到下一轮。
            for (n in scanNodes) recycleNode(n)
            scanNodes.clear()
            scanning = false
        }
    }

    /** 写一条扫描心跳。只在内存，最多 40 条，同一应用同一结论 10 秒内合并。 */
    private fun beat(
        at: Long,
        pkg: String,
        nodes: Int,
        candidates: Int,
        stage: BeatStage,
        result: BeatResult,
        note: String,
        costMs: Int = 0,
        treeMs: Int = 0,
        rootMs: Int = 0,
        verifyMs: Int = 0,
        fast: Boolean = false,
        idMs: Int = 0,
    ) {
        // 切回自己时的那一轮没有任何信息量，界面里不出现，logcat 仍有窗口日志。
        if (result == BeatResult.SELF) return
        ScanLog.add(
            ScanBeat(
                at = at,
                pkg = pkg,
                appLabel = labelOf(pkg),
                nodes = nodes,
                candidates = candidates,
                stage = stage,
                result = result,
                note = note,
                costMs = costMs,
                treeMs = treeMs,
                rootMs = rootMs,
                verifyMs = verifyMs,
                fast = fast,
                idMs = idMs,
            ),
        )
    }

    /** 应用名只查一次，之后走缓存；查不到就直接显示包名。 */
    private fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching { SkipPolicyStore.displayName(this, pkg) }.getOrDefault(pkg)
    }

    /**
     * 明确信号：SDK 关闭控件、明确跳过 / 关闭文案、四角关闭按钮。
     * 四角判定替代了原来写死的「右上角」，不同 App 把按钮放在哪都能认，
     * 但仍要求控件够小且是窄控件。
     */
    private fun scanExplicit(refs: List<Ref>, screen: Rect, now: Long): Boolean {
        for (r in HitRanker.order(refs, ::rankKey)) {
            if (r.bounds.width() >= screen.width() * NARROW_RATIO) continue
            if (!SkipEngine.isSafeTarget(r.text, r.desc)) continue
            val s = r.text.ifEmpty { r.desc }
            val corner = SkipSources.closeCornerHit(
                s, r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom,
                screen.width(), screen.height(),
            )
            val explicit = SkipEngine.builtinHit(r.text, r.desc, "") ||
                SkipSources.explicitAdClose(r.text, r.desc, r.id) || corner
            // 只有数字的圆形倒计时：几何 + 资源名双重确认，才算明确跳过按钮。
            val roundHit = ExtraRules.roundEnabled && ExtraRules.isRoundCountdown(
                ExtraRules.Node(
                    r.text, r.desc, r.id,
                    r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom,
                    r.clickable,
                ),
                screen.width(), screen.height(),
            )
            if ((explicit || roundHit) && click(r, now, HitReason.EXPLICIT_TEXT, roundHit)) return true
        }
        return false
    }

    /**
     * 附加动作：青少年模式弹窗 / 微信查看原图。默认全关。
     *
     * 这类目标不是广告，`classify()` 会把「我知道了」判成 UNKNOWN 硬否决，
     * 所以不进广告判定链；但冷却、会话上限、用户空闲、二次验证、点击去重
     * 全部复用既有实现，不新增裸点击路径。只有「今日已跳过广告」计数与
     * 跳过提示语不复用 —— 那两项只统计真正的广告。
     */
    private fun scanExtra(refs: List<Ref>, screen: Rect, now: Long, realPkg: String): Boolean {
        if (!extraTeen && !extraWechat) return false
        if (observe) return false // 观察模式只识别不点击，附加动作同样不执行
        if (now - lastExtraAt < EXTRA_SCAN_GAP_MS) return false
        lastExtraAt = now
        if (now < cooldownUntil || sessionClicks >= MAX_SESSION_CLICKS) return false
        if (now - lastInteractAt < 900) return false
        if (currentPolicy.mode == PolicyMode.DISABLED) return false
        val nodes = refs.map {
            ExtraRules.Node(
                it.text, it.desc, it.id,
                it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom,
                it.clickable,
            )
        }
        val hit = ExtraRules.match(
            realPkg, nodes, screen.width(), screen.height(),
            ExtraRules.Flags(teenPopup = extraTeen, wechatOriginal = extraWechat),
        ) ?: return false
        val ref = refs.getOrNull(hit.index) ?: return false
        return extraClick(ref, now, hit)
    }

    /**
     * 安装弹窗拦截的判定入口。
     *
     * 执行器复用 [extraClick]，所以二次验证、去重、可点击性检查全都不新增。
     * 只有两点不同：
     * ① 冷却用 300ms —— 安装弹窗通常在点完广告后立刻弹出来，800ms 的广告冷却
     *    会把这次拦截整个挡掉；
     * ② 不检查 `sessionClicks` —— 那是开屏广告会话的上限，与安装弹窗无关。
     */
    private fun scanInstall(refs: List<Ref>, screen: Rect, now: Long, pkg: String): Boolean {
        if (refs.isEmpty()) return false
        val nodes = refs.map {
            ExtraRules.Node(
                it.text, it.desc, it.id,
                it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom,
                it.clickable,
            )
        }
        val hit = ExtraRules.matchInstall(nodes, screen.width())
        if (hit == null) {
            // 真机排障用：拦截没生效时，这条能直接看出是文案没对上还是尺寸被过滤。
            val w = screen.width()
            val brief = nodes.asSequence()
                .map { it.text.ifEmpty { it.desc }.trim() to it }
                .filter { it.first.isNotEmpty() && it.first.length <= 6 }
                .take(10)
                .joinToString(" / ") { "${it.first}(${it.second.width}x${it.second.height},c=${it.second.clickable})" }
            android.util.Log.i("SkipAd", "安装器未命中 W=$w 短文本=$brief")
            return false
        }
        val ref = refs.getOrNull(hit.index)
        if (ref == null) {
            android.util.Log.i("SkipAd", "安装器索引越界 index=${hit.index} refs=${refs.size}")
            return false
        }
        return extraClick(
            ref, now, hit, INSTALL_CLICK_GAP_MS,
            expectPkg = pkg, installMode = true,
        )
    }

    /** 附加动作的唯一执行出口：二次验证 -> 控件点击 -> 可选手势兜底 -> 统一记账。 */
    private fun extraClick(
        ref: Ref,
        now: Long,
        hit: ExtraRules.Hit,
        gapMs: Long = CLICK_GAP_MS,
        expectPkg: String = policyPkg,
        installMode: Boolean = false,
    ): Boolean {
        val base = "extra:${hit.kind.name}|" + ref.id + "|" + ref.text.replace(NUM, "")
        val key = base + "|" + ref.bounds.left + "," + ref.bounds.top
        if ((clickCounts[key] ?: 0) >= MAX_SAME_CLICKS) {
            android.util.Log.i("SkipAd", "附加动作未执行(${hit.kind.label}): 同控件已点满")
            return false
        }
        if (now - lastClickAt < gapMs) {
            android.util.Log.i("SkipAd", "附加动作未执行(${hit.kind.label}): 冷却中，还差 ${gapMs - (now - lastClickAt)}ms")
            return false
        }
        val node = scanNodes.getOrNull(ref.nodeIndex)
        if (node == null) {
            android.util.Log.i("SkipAd", "附加动作未执行(${hit.kind.label}): 节点索引失效 idx=${ref.nodeIndex} size=${scanNodes.size}")
            return false
        }
        val fresh = Rect()
        val denied = verifyBeforeClick(ref, node, fresh, expectPkg, installMode)
        if (denied != null) {
            android.util.Log.i("SkipAd", "附加动作未执行(${hit.kind.label}): $denied")
            return false
        }
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth <= 3) {
            if (cur.isClickable && cur.isEnabled &&
                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                markExtra(key, base, ref, now, hit)
                return true
            }
            val parent = cur.parent
            if (parent != null) scanNodes.add(parent)
            cur = parent
            depth++
        }
        if (gestureFallback && now - lastGestureAt > 700 &&
            fresh.width() > 20 && fresh.height() > 20
        ) {
            val path = Path().apply { moveTo(fresh.exactCenterX(), fresh.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
            lastGestureAt = now
            if (dispatchGesture(gesture, null, null)) {
                markExtra(key, base, ref, now, hit)
                return true
            }
        }
        return false
    }

    /**
     * 借 mark() 统一写冷却 / 去重 / 会话点击上限，规则名标明是附加动作。
     * `ad = false`：附加动作不是广告，不进「今日已跳过广告」，也不弹跳过提示。
     */
    private fun markExtra(key: String, base: String, ref: Ref, now: Long, hit: ExtraRules.Hit) {
        mark(
            key, base, ref.bounds.left, ref.bounds.top, now,
            Decision(
                score = Weights.HIGH_MIN,
                level = Confidence.HIGH,
                kind = AdKind.POPUP,
                action = DecisionAction.CLICK,
                rule = "extra:${hit.kind.name.lowercase()}",
                reasons = listOf(hit.kind.label),
                policyAllowed = true,
            ),
            ad = false,
        )
        // 测试模式：mark() 刚给这个控件记了一次，立刻抹掉，让它永远停在 1 次，
        // 从而绕过 MAX_SAME_CLICKS 的 2 次上限；关掉开关后计数也是干净的。
        // 只针对安装拦截，青少年的「我知道了」和微信原图仍受原上限约束。
        if (installTestMode && hit.kind == ExtraRules.Kind.INSTALL_CANCEL) {
            clickCounts.remove(key)
        }
        android.util.Log.i("SkipAd", "附加动作已执行: ${hit.kind.label} ${hit.label}")
    }

    /**
     * 应用内弹窗：只有「明确广告关闭信号」才进，纯 `×` / 「不感兴趣」一律不点。
     *
     * 区分插屏弹窗与信息流广告的关键启发式：插屏 / 全屏推广通常只有**一个**关闭控件，
     * 信息流列表里会同时出现多个广告卡片的「关闭广告」。候选超过一个就整个放弃，
     * 宁可漏掉一次弹窗，也不要在用户刷列表时替他连点。
     *
     * 每个会话最多关一次；上游 scan 已保证策略允许且用户 900ms 内没交互。
     */
    private fun scanPopup(refs: List<Ref>, screen: Rect, now: Long): Boolean {
        if (popupClicks >= 1) return false
        val candidates = ArrayList<Ref>(2)
        val seen = HashSet<String>()
        for (r in refs) {
            if (r.bounds.width() >= screen.width() * NARROW_RATIO) continue
            if (!SkipEngine.isSafeTarget(r.text, r.desc)) continue
            val s = r.text.ifEmpty { r.desc }
            val sdk = SkipSources.sdkCloseHit(r.id)
            val corner = SkipSources.closeCornerHit(
                s, r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom,
                screen.width(), screen.height(),
            )
            // 光有「关闭广告」四个字不够，还得有 SDK id 或屏幕级四角这类硬信号
            if (!sdk && !corner) continue
            if (!sdk && !SkipEngine.explicitPopupClose(r.text, r.desc, r.id)) continue
            val key = r.id + "|" + s.replace(NUM, "")
            if (!seen.add(key)) continue
            candidates.add(r)
            if (candidates.size > 1) return false
        }
        if (candidates.size != 1) return false
        val r = candidates[0]
        if (click(r, now, HitReason.EXPLICIT_TEXT)) {
            popupClicks++
            android.util.Log.i("SkipAd", "已关闭推广弹窗: ${r.id.ifEmpty { r.text }}")
            return true
        }
        return false
    }

    /**
     * 受限遍历：深度、候选数、访问总数、耗时四个上限同时生效。
     * 每个遍历到的节点都登记进 [scanNodes]（扫描结束统一 recycle），
     * Ref 只保存它的下标和纯数据，不持有节点本身。
     */
    /**
     * 把一个节点读成 [Ref]：只读判定真的会用到的那几项。
     * 三样文本全空、不可见、尺寸为零、或不在屏幕内的节点返回 null。
     */
    private fun refOf(node: AccessibilityNodeInfo, index: Int, screen: Rect): Ref? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName ?: ""
        if (text.isEmpty() && desc.isEmpty() && id.isEmpty()) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!node.isVisibleToUser || rect.width() <= 0 || rect.height() <= 0) return null
        if (!Rect.intersects(rect, screen)) return null
        val cls = if (SkipEngine.needsClass) node.className?.toString()?.trim().orEmpty() else ""
        return Ref(index, text, desc, id, rect, cls, node.isClickable)
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<Ref>,
        depth: Int,
        screen: Rect,
        deadline: Long,
        index: Int,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        if (scanNodes.size >= MAX_VISITED) return
        if (android.os.SystemClock.elapsedRealtime() > deadline) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName ?: ""
        // 剩下的属性每读一次都是一次跨进程往返。一屏里占大多数的纯容器节点
        // （三个文本都空）根本不会生成 Ref，读了也没人用——所以全部挪进判空里。
        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            // className 只有订阅里写了 [name="..."] 时才会有人用，按需读。
            val cls = if (SkipEngine.needsClass) node.className?.toString()?.trim().orEmpty() else ""
            val clickable = node.isClickable
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // 不可见的节点永远不点：广告 SDK 常放一个 0×0 或屏幕外的假跳过按钮
            if (node.isVisibleToUser && rect.width() > 0 && rect.height() > 0 &&
                Rect.intersects(rect, screen)
            ) {
                out.add(Ref(index, text, desc, id, rect, cls, clickable))
            }
        }
        val n = node.childCount
        for (i in 0 until n) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) return
            if (scanNodes.size >= MAX_VISITED || out.size >= MAX_NODES) return
            val child = node.getChild(i) ?: continue
            scanNodes.add(child)
            collect(child, out, depth + 1, screen, deadline, scanNodes.size - 1)
        }
    }

    // ---------------- 动作 ----------------

    /**
     * 唯一的点击出口。所有扫描轮最后都到这里，统一过五道关：
     * 多条件评分 -> 广告类型 -> 应用策略 -> 冷却 -> 观察模式。
     * 观察模式下这里会完整识别并记录，但永远不执行任何点击 / 返回键。
     */
    /**
     * 点击前的「目标探测」结果：谁会被真正点到、它有多大。
     * node 为 null 表示整条祖先链都不可点。
     */
    private class ClickProbe(
        val node: AccessibilityNodeInfo?,
        val selfClickable: Boolean,
        val w: Int,
        val h: Int,
        val areaPct: Int,
    )

    /**
     * 找出点击真正会落到的节点，并量它的面积占比。
     *
     * 跳过按钮常见两种形态：自己可点的 TextView/ImageView，或者包在
     * FrameLayout 里不可点、由父容器响应点击。两种都允许，但如果可点的
     * 那一层是整屏广告图，那它不是跳过按钮，是广告热区。
     *
     * 只读不点；拿到的父节点登记进 [scanNodes]，由扫描结束统一回收。
     */
    private fun probeClickTarget(start: AccessibilityNodeInfo): ClickProbe {
        val self = start.isClickable && start.isEnabled
        if (self) {
            val r = Rect()
            start.getBoundsInScreen(r)
            return ClickProbe(
                start, true, r.width(), r.height(),
                (HitGeometry.areaRatio(r.width(), r.height(), scanScreenW, scanScreenH) * 100).toInt(),
            )
        }
        var cur: AccessibilityNodeInfo? = start
        var depth = 0
        while (cur != null && depth <= 3) {
            if (cur.isClickable && cur.isEnabled) {
                val r = Rect()
                cur.getBoundsInScreen(r)
                return ClickProbe(
                    cur, false, r.width(), r.height(),
                    (HitGeometry.areaRatio(r.width(), r.height(), scanScreenW, scanScreenH) * 100).toInt(),
                )
            }
            val parent = cur.parent
            if (parent != null) scanNodes.add(parent)
            cur = parent
            depth++
        }
        return ClickProbe(null, false, 0, 0, 0)
    }

    /** 排序用的轻量键：只读扫描阶段已有的纯数据，不额外跨进程。 */
    private fun rankKey(r: Ref): HitRanker.RankKey = HitRanker.RankKey(
        clickable = r.clickable,
        width = r.bounds.width(),
        height = r.bounds.height(),
        left = r.bounds.left,
        top = r.bounds.top,
        right = r.bounds.right,
        bottom = r.bounds.bottom,
        screenW = scanScreenW,
        screenH = scanScreenH,
    )

    private fun click(ref: Ref, now: Long, reason: HitReason, round: Boolean = false): Boolean {
        // 倒计时文本每秒都在变（"跳过 5s" -> "跳过 4s"），直接拿原文当 key
        // 等于没有判重。去掉数字和空白后才是同一个控件；再带上位置，
        // 让同文案但不同位置的控件彼此可区分（下面用 16px 容差认同一个按钮）。
        val base = ref.id + "|" + ref.text.replace(NUM, "") + "|" + ref.desc.replace(NUM, "")
        val key = base + "|" + ref.bounds.left + "," + ref.bounds.top
        // 倒计时从「跳过 10s」变「跳过 9s」时按钮常会左右挪几像素，而点击前的
        // 二次验证允许 16px 漂移；判重也必须按同一容差认作同一个控件，否则
        // key 一变，「点完没跳走再试一次」就永远轮不到。
        val sameAsLast = ClickTarget.same(
            base, ref.bounds.left, ref.bounds.top,
            lastClickBase, lastClickLeft, lastClickTop,
        )
        // 上次点了这个控件、页面却纹丝不动 —— 不在它身上浪费额度，
        // 改试同一个父容器里那个「没有文字、窄、可点」的图标（真跳过常常就是这种图标）。
        if (!observe && DeadTargets.isDead(policyPkg, ref.id, now)) {
            val icon = iconSiblingRef(ref)
            if (icon != null) {
                android.util.Log.i(
                    "SkipAd",
                    "补位: ${ref.id.ifEmpty { "无 id 文本候选" }} 已判无效，改试同容器图标 " +
                        icon.id.ifEmpty { "${icon.bounds.left},${icon.bounds.top}" },
                )
                if (click(icon, now, HitReason.SIBLING_ICON, round)) return true
            }
            return false
        }
        if (!observe) {
            if (sessionClicks >= MAX_SESSION_CLICKS) return false
            if ((clickCounts[key] ?: 0) >= MAX_SAME_CLICKS) return false
            // 本窗口已经点过一次，就只允许对「同一个控件」重试。
            if (sessionClicks >= 1 && key != lastClickKey && !sameAsLast) return false
            if (now - lastClickAt < CLICK_GAP_MS) return false
        }

        // 判定之前先摸清「真正会被点到的是谁」：控件自己可点就用它，
        // 否则向上找 ≤3 层第一个可点祖先，并量出它的面积占比。
        // 只读不点 —— 判定通过之后才在这个节点上 performAction。
        val node = scanNodes.getOrNull(ref.nodeIndex) ?: return false
        val probe = probeClickTarget(node)

        val b = ref.bounds
        val label = ref.text.ifEmpty { ref.desc }
        val age = now - windowAt
        val kind = SkipEngine.classify(
            ref.text, ref.desc, ref.id, b,
            scanScreenW, scanScreenH, scanInSplash, reason, round,
        )
        val facts = NodeFacts(
            pkg = policyPkg,
            text = ref.text,
            desc = ref.desc,
            id = ref.id,
            cls = ref.cls,
            left = b.left, top = b.top, right = b.right, bottom = b.bottom,
            screenW = scanScreenW, screenH = scanScreenH,
            clickable = ref.clickable,
            visible = true,
            inSplash = scanInSplash,
            freshWindow = windowFresh,
            windowAgeMs = age,
            popupRound = scanPopupRound,
            reason = reason,
            kind = kind,
            safeTarget = SkipEngine.isSafeTarget(ref.text, ref.desc),
            sdkId = SkipEngine.builtin && SkipSources.sdkHit(ref.id),
            sdkClose = SkipSources.sdkCloseHit(ref.id),
            explicitText =
                (SkipEngine.builtin && SkipEngine.builtinHit(ref.text, ref.desc, "")) ||
                    SkipSources.explicitAdClose(ref.text, ref.desc, ref.id) ||
                    // 圆形倒计时本身就是明确的跳过按钮，按「明确文案」记分，
                    // 否则纯数字控件拿不到分数会被 DecisionCore 判成低置信度。
                    round,
            corner = SkipSources.closeCornerHit(
                label, b.left, b.top, b.right, b.bottom, scanScreenW, scanScreenH,
            ),
            scopedRule = SkipEngine.scopedHit(
                policyPkg, curAct, ref.text, ref.desc, ref.id, age, ref.cls,
            ) != null,
            globalRule = SkipEngine.globalHit(ref.text, ref.desc, ref.id, age, ref.cls),
            customRule = SkipEngine.customHit(ref.text, ref.desc, ref.id, ref.cls),
            trapAncestor = HitGeometry.isTrapAncestor(
                probe.selfClickable, probe.w, probe.h, scanScreenW, scanScreenH,
            ),
            ancestorAreaPct = probe.areaPct,
            siblingIcon = reason == HitReason.SIBLING_ICON,
            // 记忆命中的是「上次点中并复核生效的同一个控件」，要把这条证据带进判定，
            // 否则它只是一个没有文字、没有规则的节点，分数永远过不了线（真机踩过）。
            remembered = reason == HitReason.MEMORY,
            coordinateRule = SkipEngine.boundsHit(b),
            cooldownActive = now < cooldownUntil,
            observe = observe,
            mode = currentPolicy.mode,
            userIdle = now - lastInteractAt >= 900,
        )

        var d = DecisionCore.decide(facts)
        // 中置信度：只记录，等同一目标短时间内再次出现且证据变强才升级一次。
        if (!observe && d.level == Confidence.MID) {
            val prev = evidence[key]
            val repeat = prev != null &&
                now - prev.first <= Weights.EVIDENCE_WINDOW_MS && d.score >= prev.second
            if (repeat) {
                evidence.remove(key)
                d = DecisionCore.decide(facts.copy(evidenceRepeat = true))
            } else {
                evidence[key] = now to d.score
            }
        }

        // 观察记录只存内存。观察模式下同一个目标 4 秒内只刷一次，避免刷屏。
        val lastRecord = lastRecordAt[key] ?: 0L
        val shouldRecord = d.action == DecisionAction.CLICK ||
            (d.action == DecisionAction.OBSERVE && now - lastRecord > 4000)
        if (shouldRecord) {
            lastRecordAt[key] = now
            ObservationLog.add(
                Observation(
                    at = now,
                    pkg = policyPkg,
                    appLabel = labelOf(policyPkg),
                    text = ref.text,
                    desc = ref.desc,
                    id = ref.id,
                    clickable = ref.clickable,
                    rule = d.rule,
                    score = d.score,
                    level = d.level,
                    kind = d.kind,
                    action = d.action,
                    reasons = d.reasons,
                ),
            )
            RunStats.onSkip(policyPkg, d.rule, d.level, d.kind, d.action, d.score)
        }

        if (d.action != DecisionAction.CLICK) {
            if (now - lastRejectLogAt > 2000) {
                lastRejectLogAt = now
                android.util.Log.i(
                    "SkipAd",
                    "未点击: ${d.level.label} 分数=${d.score} 类型=${d.kind} " +
                        "证据=$reason 策略=${currentPolicy.mode} 规则=${d.rule} " +
                        "动作=${d.action} ${d.reasons.lastOrNull().orEmpty()}",
                )
                RejectLog.add(
                    Reject(
                        at = now,
                        pkg = policyPkg,
                        appLabel = labelOf(policyPkg),
                        target = ref.text.ifEmpty { ref.desc }.ifEmpty { ref.id },
                        level = d.level,
                        kind = d.kind,
                        action = d.action,
                        rule = d.rule,
                        reason = d.reasons.lastOrNull().orEmpty(),
                    ),
                )
            }
            return false
        }

        // 二次验证：扫描 → 判定 → 真正下手之间可能隔了几十毫秒，
        // 倒计时刷新、页面跳转、弹窗自动消失都可能发生。
        // 只信扫描时那份快照，等于闭着眼睛按坐标。
        val fresh = Rect()
        // 二次验证要再向系统要一次窗口根节点，是实打实的主线程跨进程往返。
        // 单独计时，才知道「能不能省掉这一跳」值不值得讨论。
        val verifyAt = android.os.SystemClock.elapsedRealtime()
        val denied = verifyBeforeClick(ref, node, fresh)
        val verifyMs = (android.os.SystemClock.elapsedRealtime() - verifyAt).toInt()
        scanVerifyMs += verifyMs
        if (denied != null) {
            android.util.Log.i("SkipAd", "二次验证未通过（验证=${verifyMs}ms）: $denied")
            RejectLog.add(
                Reject(
                    at = now,
                    pkg = policyPkg,
                    appLabel = SkipPolicyStore.displayName(this, policyPkg),
                    target = label.ifEmpty { ref.id },
                    level = d.level,
                    kind = d.kind,
                    action = DecisionAction.SKIP,
                    rule = d.rule,
                    reason = "二次验证未通过：$denied",
                ),
            )
            return false
        }

        // 点 probe 在判定前就找好的那个节点（自己或 ≤3 层内的可点祖先），
        // 不再重新向上走一遍：同一棵树走两次，第二次还可能走到别的地方去。
        val target = probe.node
        if (target != null && target.isClickable && target.isEnabled &&
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            mark(
                key, base, ref.bounds.left, ref.bounds.top, now, d,
                verifyMs = verifyMs, viewId = ref.id,
            )
            return true
        }

        // 控件本身与可点击祖先都点不动时，退化成按坐标点一下。
        // 坐标用二次验证时重新读到的位置，不用扫描时的旧值。
        if (gestureFallback && now - lastGestureAt > 700 &&
            fresh.width() > 20 && fresh.height() > 20
        ) {
            val path = Path().apply { moveTo(fresh.exactCenterX(), fresh.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
            lastGestureAt = now
            if (dispatchGesture(gesture, null, null)) {
                mark(
                    key, base, ref.bounds.left, ref.bounds.top, now, d,
                    verifyMs = verifyMs, viewId = ref.id,
                )
                return true
            }
        }
        return false
    }

    /**
     * 点击前的第二道验证：重新向系统要一次节点状态。
     *
     * 只认四件事都对得上——控件还在、还可见、位置没漂、文字没变、窗口还属于原应用。
     * 任何一条不过就放弃这次点击，并把原因记进诊断，而不是「先点了再说」。
     *
     * @return null 表示通过；否则返回中文原因。
     */
    /**
     * @param expectPkg 本次点击期望的前台包名。默认是广告策略包；
     *   安装拦截要传安装器包名，否则「前台已切到 xxx」会永远判失败。
     * @param installMode 安装拦截专用：跳过「像不像跳过按钮」与 DISABLED 策略检查。
     *   安装框里的「暂不安装 / 放弃安装」含 CTA 词「安装」，会被 [SkipEngine.isSafeTarget]
     *   整条挡掉；而 DISABLED 说的是当前 App 的广告策略，与系统安装框无关。
     */
    private fun verifyBeforeClick(
        ref: Ref,
        node: AccessibilityNodeInfo,
        fresh: Rect,
        expectPkg: String = policyPkg,
        installMode: Boolean = false,
    ): String? {
        if (!node.refresh()) return "控件已消失"
        if (!node.isVisibleToUser || !node.isEnabled) return "控件已不可见"
        node.getBoundsInScreen(fresh)
        val d = ref.bounds
        if (kotlin.math.abs(fresh.left - d.left) > BOUNDS_DRIFT_PX ||
            kotlin.math.abs(fresh.top - d.top) > BOUNDS_DRIFT_PX ||
            kotlin.math.abs(fresh.right - d.right) > BOUNDS_DRIFT_PX ||
            kotlin.math.abs(fresh.bottom - d.bottom) > BOUNDS_DRIFT_PX
        ) {
            return "控件位置变了"
        }
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.replace(NUM, "") != ref.text.replace(NUM, "")) return "控件文字变了"
        if (!installMode && !SkipEngine.isSafeTarget(t, node.contentDescription?.toString().orEmpty())) {
            return "控件不再像跳过按钮"
        }
        // 窗口归属再确认：用户可能已经从开屏页点进主页，这时按旧坐标点就是误触。
        val root = rootInActiveWindow ?: return "当前窗口读不到"
        try {
            val top = root.packageName?.toString().orEmpty()
            if (top.isNotEmpty() && top != expectPkg) return "前台已切到 $top"
        } finally {
            recycleNode(root)
        }
        if (!installMode && currentPolicy.mode == PolicyMode.DISABLED) return "该应用策略为不自动点击"
        return null
    }

    private fun doBack(now: Long): Boolean {
        // 观察模式下连返回键都不按，只识别不执行。
        if (observe) {
            scanHitRule = "back:观察模式未执行"
            return false
        }
        if (now < cooldownUntil) return false
        if (now - lastBackAt < 800) return false
        if (sessionClicks >= MAX_SESSION_CLICKS) return false
        if (currentPolicy.mode == PolicyMode.DISABLED) return false
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) return false
        lastBackAt = now
        lastClickAt = now
        lastClickKey = "back"
        lastClickBase = ""
        sessionClicks++
        cooldownUntil = now + Weights.COOLDOWN_MS
        prefs.bumpSkip()
        RunStats.onSkip(
            policyPkg, "subscription:back", Confidence.HIGH,
            AdKind.SPLASH, DecisionAction.CLICK, Weights.HIGH_MIN,
        )
        return true
    }

    private fun mark(
        key: String, base: String, left: Int, top: Int, now: Long, d: Decision,
        ad: Boolean = true,
        verifyMs: Int = 0,
        viewId: String = "",
    ) {
        scanHitRule = d.rule
        lastClickKey = key
        lastClickBase = base
        lastClickLeft = left
        lastClickTop = top
        lastClickAt = now
        sessionClicks++
        clickCounts[key] = (clickCounts[key] ?: 0) + 1
        // 规则冷却：点击成功后 2 秒内同窗口不再点；窗口变化会清零。
        cooldownUntil = now + Weights.COOLDOWN_MS
        // 点过一次之后，本窗口余下的轮询不再是「新窗口会话」，
        // 否则 freshWindow 会一直为 true，把冷却判定在决策层抵消掉。
        windowFresh = false
        evidence.clear()
        // 「点到了」不等于「跳过了」：performAction 返回 true 只代表控件接受了动作，
        // 页面有没有变化完全是另一回事（有的应用把「跳过广告1」做成点了没反应的假标签）。
        // 所以这里只把复核排进队列 —— 统计、提示、记 id 全部挪到 confirmSkip()。
        if (ad) {
            armEffectCheck(d, viewId, verifyMs, now)
        }
        android.util.Log.i(
            "SkipAd",
            "点击已发出（待校验）${if (ad) "跳过" else "附加动作"}: $key 分数=${d.score} ${d.level.label} " +
                "规则=${d.rule} 耗时=${now - windowAt}ms 验证=${verifyMs}ms 冷却=2s",
        )
    }

    /** 把这次点击登记为「待复核」，[EFFECT_CHECK_MS] 后回来看看页面变没变。 */
    private fun armEffectCheck(d: Decision, viewId: String, verifyMs: Int, now: Long) {
        pendingDecision = d
        pendingPkg = policyPkg
        pendingAct = curAct
        pendingViewId = viewId
        pendingKey = lastClickKey
        pendingAt = now
        pendingWindowAt = windowAt
        pendingVerifyMs = verifyMs
        pendingGraceUsed = false
        handler.removeCallbacks(effectCheck)
        handler.postDelayed(effectCheck, EFFECT_CHECK_MS)
    }

    /**
     * 复核点击效果：控件还在、窗口没动 → 这次点击白点了。
     *
     * 判据全部来自「系统给的现在的状态」，不看我们自己的日志：
     *  1. 同一个 viewId 的控件是否还可见；
     *  2. 前台 Activity / 包名是否变了；
     *  3. 期间是否有新的窗口事件。
     */
    private fun verifyEffect() {
        val d = pendingDecision ?: return
        val viewId = pendingViewId
        val now = System.currentTimeMillis()

        var stillVisible: Boolean? = null
        if (viewId.isNotEmpty()) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root != null) {
                val found = runCatching {
                    root.findAccessibilityNodeInfosByViewId(viewId)
                }.getOrNull()
                stillVisible = found?.any { runCatching { it.isVisibleToUser }.getOrDefault(false) } ?: false
                found?.forEach { recycleNode(it) }
            }
        }

        val changed = EffectVerdict.judge(
            stillVisible = stillVisible,
            actChanged = curAct != pendingAct,
            pkgChanged = curPkg != pendingPkg,
            windowAdvanced = windowAt > pendingWindowAt,
        )
        if (changed) {
            pendingDecision = null
            confirmSkip(d, viewId, now, pendingVerifyMs)
            return
        }
        if (!pendingGraceUsed) {
            pendingGraceUsed = true
            handler.postDelayed(effectCheck, EFFECT_GRACE_MS)
            return
        }

        // 两次复核都没变化：这次点击无效。
        // 不弹提示、不记统计、不占「每次会话两次点击」的额度，并把控件拉黑。
        pendingDecision = null
        sessionClicks = (sessionClicks - 1).coerceAtLeast(0)
        cooldownUntil = 0L
        DeadTargets.remember(pendingPkg, viewId, now)
        android.util.Log.i(
            "SkipAd",
            "点击无效: $pendingKey 复核 ${EFFECT_CHECK_MS}+${EFFECT_GRACE_MS}ms 页面无变化，已拉黑该控件",
        )
        RejectLog.add(
            Reject(
                at = now,
                pkg = pendingPkg ?: "",
                appLabel = labelOf(pendingPkg ?: ""),
                target = pendingKey.ifEmpty { viewId },
                level = d.level,
                kind = d.kind,
                action = DecisionAction.SKIP,
                rule = d.rule,
                reason = "点击无效：页面无变化（疑为假按钮）",
            ),
        )
        // 立刻补扫一次，让补位逻辑接上（这次会走 iconSiblingRef）。
        val pkg = pendingPkg ?: policyPkg
        handler.post {
            if (pkg.isNotEmpty()) scan(pkg, curAct, force = true)
        }
    }

    /** 复核确认页面真的变了，这才是「跳过成功」：统计、提示、记 id 都从这里发。 */
    private fun confirmSkip(d: Decision, viewId: String, now: Long, verifyMs: Int) {
        prefs.bumpSkip()
        showSkipToast(now)
        HitMemory.remember(pendingPkg ?: policyPkg, viewId)
        android.util.Log.i(
            "SkipAd",
            "跳过生效: $pendingKey 分数=${d.score} 规则=${d.rule} " +
                "复核=${now - pendingAt}ms 验证=${verifyMs}ms",
        )
    }

    /**
     * 在失效候选的同一个父容器里，找一个「没有文字、窄、可点」的图标当补位候选。
     *
     * 只用来救一种情况：文字候选点了没反应，而真正的跳过是紧挨着它的无字图标。
     * 找到的节点同样进 [scanNodes] 交给统一回收，判定也照走
     * classify + DecisionCore + verifyBeforeClick 三道闸，不额外放行。
     */
    private fun iconSiblingRef(ref: Ref): Ref? {
        val node = scanNodes.getOrNull(ref.nodeIndex) ?: return null
        val parent = runCatching { node.parent }.getOrNull() ?: return null
        val screen = Rect(0, 0, scanScreenW, scanScreenH)
        val maxW = (scanScreenW * SIBLING_MAX_WIDTH_RATIO).toInt()
        var picked: AccessibilityNodeInfo? = null
        try {
            for (i in 0 until parent.childCount) {
                val child = runCatching { parent.getChild(i) }.getOrNull() ?: continue
                val usable = runCatching {
                    child != node && child.isClickable && child.isVisibleToUser &&
                        child.text.isNullOrEmpty() && child.contentDescription.isNullOrEmpty() &&
                        !child.viewIdResourceName.isNullOrEmpty()
                }.getOrDefault(false)
                if (!usable) {
                    recycleNode(child)
                    continue
                }
                val r = Rect()
                runCatching { child.getBoundsInScreen(r) }
                val fits = r.width() in 1..maxW && r.height() > 0 &&
                    scanScreenW > 0 && Rect.intersects(r, screen)
                if (fits && picked == null) {
                    picked = child
                } else {
                    recycleNode(child)
                }
            }
            val hit = picked ?: return null
            scanNodes.add(hit)
            return refOf(hit, scanNodes.size - 1, screen)
        } finally {
            recycleNode(parent)
        }
    }

    /** 跳过成功的轻提示：文案可自定义，800ms 内不重复弹，避免同窗口连点刷屏。 */
    private fun showSkipToast(now: Long) {
        if (!skipToast || observe || now - lastToastAt < TOAST_GAP_MS) return
        lastToastAt = now
        val text = toastText.ifBlank { DEFAULT_TOAST }
        handler.post {
            runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------------- 规则热更新 ----------------

    /** 开关类配置每次事件同步一次，服务进程内不反复读 SharedPreferences。 */
    private fun syncFlags() {
        observe = prefs.observeMode
        fastPath = prefs.skipFastPath
        ExtraRules.roundEnabled = prefs.roundCountdown
        extraTeen = prefs.teenPopup
        extraWechat = prefs.wechatOriginal
        extraInstall = prefs.blockInstall
        installTestMode = prefs.installTestMode
        gestureFallback = prefs.gestureFallback
        skipToast = prefs.skipToast
        toastText = prefs.skipToastText
    }

    private fun syncEngine() {
        SkipEngine.builtin = prefs.skipBuiltin
        syncFlags()
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

    /**
     * 问系统「谁是桌面」。比硬编码包名列表可靠，也顺手覆盖了各家 OEM 的桌面。
     * 解析失败时返回空表，退回静态名单，不影响任何功能。
     */
    private fun resolveHomePkgs(): List<String> = runCatching {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val all = pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }
        val def = pm.resolveActivity(intent, 0)?.activityInfo?.packageName
        if (def != null) all + def else all
    }.getOrDefault(emptyList())

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopPolling()
        ruleLoader.shutdownNow()
        SkipAdState.onDisconnected(stateToken)
        // 授权还在、服务却断了 —— 多半是省电策略把服务冻住了。
        // 只提醒一次，不尝试后台重启（系统不允许），更不弹系统授权窗口。
        handler.removeCallbacks(launcherRetry)
        handler.removeCallbacks(effectCheck)
        pendingDecision = null
        scanWorker.shutdownNow()
        ServiceRecovery.warnAccessibility(this)
        android.util.Log.i("SkipAd", "无障碍服务已断开")
        super.onDestroy()
    }

    private companion object {
        /** 文本层泛化匹配的时间窗：窗口切换后这段时间内才算「开屏」。 */
        const val SPLASH_TEXT_MS = 9000L

        /** 轮询持续时长。比文本层长一点，覆盖「广告图先出、跳过按钮后出」。 */
        const val SPLASH_POLL_MS = 12000L

        /** 常规主动轮询间隔。700ms 兼顾命中速度与耗电。 */
        const val POLL_MS = 700L

        /**
         * 开屏最初 3 秒的高频轮询。广告图先出、跳过按钮延迟渲染是常态，
         * 这段窗口内 120ms 一轮；3 秒后或成功命中后立刻回落到 [POLL_MS]，
         * 不增加常驻耗电。
         *
         * 带 FAST 后缀的四个值必须联动：scanGap 若大于轮询间隔，轮询扫描会被
         * 自己的节流吃掉；合并窗口与首轮延时则是叠加在命中之前的固定等待，
         * 只调其中一个几乎看不出提速。
         */
        const val POLL_FAST_MS = 120L
        const val POLL_FAST_WINDOW_MS = 3000L

        /**
         * 首次轮询延时。换窗口时已经做过一次 force 扫描，过渡帧拿不到按钮
         * 是预期内；这里只需等过渡动画过去，不必等满 420ms。万一早一轮扫到
         * 旧快照，点击前的二次验证还会复核，所以缩短它只多一两次空扫描，
         * 不会带来误点。
         */
        const val FIRST_POLL_DELAY_MS = 160L

        /** 同一个窗口内总点击上限：一次首点 + 一次失败重试。 */
        const val MAX_SESSION_CLICKS = 2

        /** 同一个控件最多点几次（点完没跳走就说明没生效，再试一次即可）。 */
        const val MAX_SAME_CLICKS = 2

        /** 两次点击之间的最小间隔。 */
        const val CLICK_GAP_MS = 800L

        /** 点击之后隔多久回来复核「页面真的变了吗」。 */
        const val EFFECT_CHECK_MS = 600L

        /** 有的开屏是淡出动画，第一次复核控件还挂着就再等这么久。 */
        const val EFFECT_GRACE_MS = 800L

        /** 同容器补位候选（无文字图标）的最大宽度占屏比。 */
        const val SIBLING_MAX_WIDTH_RATIO = 0.45f

        /** 内容变化事件合并窗口：120ms 内的连续变化只遍历一次节点树。 */
        const val SCAN_MERGE_MS = 120L

        /** 开屏初期按钮刚渲染，首个事件立即扫描，不额外等待合并窗口。 */
        const val SCAN_MERGE_FAST_MS = 0L

        /** 开屏窗内容变化的合并上限：既保住首帧，也别每秒扫八次扫满整段开屏。 */
        const val SPLASH_MERGE_MAX_MS = 240L

        /**
         * 空转退避的上限：连续扫不到候选时，合并窗口最多拉长到这里。
         * 再长就会让「广告刚出现」的那一下明显变迟钝，不划算。
         */
        const val SCAN_MERGE_MAX_MS = 480L

        /** 桌面还盖着目标应用时，隔多久补扫一次。 */
        const val LAUNCHER_RETRY_DELAY_MS = 180L
        /** 两次补扫的最小间隔，防止在桌面上刷屏。 */
        const val LAUNCHER_RETRY_GAP_MS = 120L
        /** 一次过渡最多补几次。 */
        const val LAUNCHER_RETRY_MAX = 3

        /** 非强制扫描的最小间隔：开屏初期 60ms，其余保持 200ms。 */
        const val SCAN_GAP_MS = 200L
        const val SCAN_GAP_FAST_MS = 60L

        /** 单次遍历超时：超时即停，不让畸形界面拖住主线程。 */
        const val SCAN_TIMEOUT_MS = 120L

        /** 遍历深度 / 候选数 / 访问总数上限。 */
        const val MAX_DEPTH = 12
        const val MAX_NODES = 300
        const val MAX_VISITED = 400

        /** 文本类匹配只认比这更窄的控件（跳过按钮都是小标签）。 */
        const val NARROW_RATIO = 0.45f

        /** 二次验证与点击判重共用的像素漂移上限：超过就认为页面已经变了，不点。 */
        const val BOUNDS_DRIFT_PX = ClickTarget.DRIFT_PX

        /** 去掉数字后比较，倒计时文本才认得出是同一个按钮。 */
        val NUM = Regex("[0-9\\s]")

        /** 跳过提示的默认文案；用户没填或清空时用它（界面回显同一来源）。 */
        const val DEFAULT_TOAST = AppPrefs.DEFAULT_SKIP_TOAST

        /** 附加动作全树判定的最小间隔，避免每轮轮询都做一次匹配。 */
        const val EXTRA_SCAN_GAP_MS = 500L

        /** 安装器窗口的扫描间隔。比附加动作略宽：安装弹窗只有一次有效时机。 */
        const val INSTALL_SCAN_GAP_MS = 600L

        /** 服务重连后补扫的延迟：给安装器窗口渲染留时间。 */
        const val RECONNECT_SCAN_DELAY_MS = 400L

        /**
         * 安装拦截的点击冷却。刻意短于广告的 800ms：安装弹窗大多在点完广告
         * 后立刻弹出来，用广告冷却会把这次拦截整个挡掉。
         */
        const val INSTALL_CLICK_GAP_MS = 300L

        /**
         * 系统安装器。这些包在 SKIP_PKGS 里（扫广告没意义），只有安装拦截
         * 开关打开时才单独放行，并且只跑 [ExtraRules.matchInstall] 一条判定。
         *
         * 覆盖主流 ROM 的安装器包名；不在表内的厂商安装器不会误伤，
         * 只是拦不到而已 —— 宁可漏拦，不要乱点。
         */
        val INSTALLER_PKGS = setOf(
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.oppo.packageinstaller",
            "com.vivo.packageinstaller",
            "com.huawei.packageinstaller",
        )

        /** 跳过提示最短间隔，同窗口连续命中不刷屏。 */
        const val TOAST_GAP_MS = 800L

        /**
         * 桌面：从桌面启动 App 是最典型的开屏场景，必须算「新会话」。
         * 名单来自 [SystemPkgs]：静态兜底 + 运行时从 PackageManager 解析。
         */
        val LAUNCHER_PKGS = SystemPkgs.LAUNCHER

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

        /**
         * 扫描前的统一过滤：静态系统包 + 系统搜索入口 + ROM 自己报的桌面。
         * 之前只看 [SKIP_PKGS]，于是 com.android.quicksearchbox 这类系统搜索
         * 被当成普通应用反复扫 —— v1.4.0 真机一轮 99 次扫描里它占 49 次。
         */
        fun isSkipPkg(p: String?): Boolean {
            if (p.isNullOrEmpty()) return true
            return SKIP_PKGS.contains(p) || SystemPkgs.isSkip(p)
        }

        /** 服务连上时登记系统回答的桌面包名。 */
        fun refreshLauncherPkgs(pkgs: Collection<String>) {
            SystemPkgs.registerHome(pkgs)
        }
    }
}

/**
 * 无障碍服务的进程内状态，供界面读取。
 *
 * 只读内存量，不落盘、不记录任何界面内容。进程被回收时全部归零，
 * 所以「最近事件时间」只代表本次进程生命周期内的事实，不会说谎。
 */
object SkipAdState {
    /** 当前存活的服务实例令牌；0 表示没有实例。 */
    @Volatile
    private var liveToken = 0

    /** 只在主线程递增，服务连接/销毁都在主线程回调。 */
    private var seq = 0

    @Volatile
    var lastEventAt: Long = 0L

    /** 返回本次连接的令牌，销毁时凭令牌认领，旧实例的 onDestroy 不会误清新实例。 */
    fun onConnected(): Int {
        val t = ++seq
        liveToken = t
        return t
    }

    fun onDisconnected(token: Int) {
        if (liveToken == token) liveToken = 0
    }

    /**
     * 服务是否真的在跑。除了实例令牌，还认最近 60 秒内收到过界面事件：
     * 部分 ROM 反复解绑/重绑时不一定重新走 onServiceConnected，但事件一直在来。
     */
    val connected: Boolean
        get() = liveToken != 0 || System.currentTimeMillis() - lastEventAt < 60_000L
}
