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

    /** 本窗口是不是「从外部新进来的」，评分时用来决定是否重置冷却。 */
    private var windowFresh = false

    /** 本次扫描是不是「12 秒之后的弹窗轮」。 */
    private var scanPopupRound = false

    /** 安全观察模式缓存值，由 syncEngine() 跟随配置热更新。 */
    private var observe = false

    /** 本次遍历的绝对截止时刻，超过就停，避免畸形界面把主线程拖住。 */
    private var scanDeadline = 0L

    /** 中置信度等待二次证据：key -> (上次时间, 上次分数)。 */
    private val evidence = HashMap<String, Pair<Long, Int>>()

    /** 观察记录按目标去重：key -> 上次记录时间。 */
    private val lastRecordAt = HashMap<String, Long>()

    /**
     * 本次扫描期间持有的节点引用。Ref 只存这里的下标，扫描一结束
     * （finally）就逐个 recycle —— AccessibilityNodeInfo 不是普通数据对象，
     * 不能跨扫描缓存，也不能等 GC 慢慢收。
     */
    private val scanNodes = ArrayList<AccessibilityNodeInfo>(128)

    /** 内容变化事件的短时合并：120ms 内的连续变化只扫一次。 */
    private var pendingScanPkg: String? = null
    private val pendingScan = Runnable {
        val pkg = pendingScanPkg ?: return@Runnable
        pendingScanPkg = null
        if (!prefs.skipAdEnabled) return@Runnable
        scan(pkg, curAct, force = false)
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
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 系统设置里「已开启」不等于服务真的连上了（OEM 省电策略会假绑定）。
        // 只有走到这里，才说明本进程确实收到了系统的绑定，界面据此显示「正在工作」。
        stateToken = SkipAdState.onConnected()
        android.util.Log.i("SkipAd", "无障碍服务已连上")
        observe = prefs.observeMode
        SkipEngine.builtin = prefs.skipBuiltin
        lastCache = prefs.skipSubCache
        lastCustom = prefs.customRules
        loadRules(lastCache, lastCustom)
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
        if (!prefs.skipAdEnabled) {
            stopPolling()
            return
        }
        syncEngine()

        val now = System.currentTimeMillis()
        when (type) {
            // 触摸类事件只更新「用户正在操作」时间戳，不扫描节点树。
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> lastInteractAt = now

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
                if (SKIP_PKGS.contains(pkg)) return
                // 兜底：无障碍服务如果是在 App 已经在前台之后才连上的
                // （重装、系统回收、手动开启都是这种情况），就再也收不到
                // TYPE_WINDOW_STATE_CHANGED，整个功能等于没开。
                // 内容变化事件带包名，用它补上「当前是哪个 App」这一步。
                if (pkg != curPkg) {
                    handler.removeCallbacks(pendingScan)
                    pendingScanPkg = null
                    handleWindow(pkg, curAct ?: "", now)
                    return
                }
                // 短时间合并：倒计时文本每秒变、列表滚动连发事件，
                // 120ms 内的连续内容变化只做一次全树遍历。
                pendingScanPkg = pkg
                handler.removeCallbacks(pendingScan)
                handler.postDelayed(pendingScan, SCAN_MERGE_MS)
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
            popupClicks = 0
            cooldownUntil = 0L
            evidence.clear()
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
        handler.removeCallbacks(pendingScan)
        pendingScanPkg = null
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

    /**
     * 扫描分三个场景，越往后越保守：
     *  A 开屏 0~9 秒：明确信号 + 订阅 / 自定义 / 弹窗规则
     *  B 开屏 9~12 秒：只认明确信号（倒计时结束、广告延迟渲染后才出现的按钮）
     *  C 12 秒之后：只处理带明确广告信号的推广弹窗，且要策略允许、用户空闲
     *
     * 无论哪一轮命中，最后都要过 [click] 里的广告类型 + 应用策略闸门。
     */
    private fun scan(pkg: String, activity: String?, force: Boolean) {
        if (SKIP_PKGS.contains(pkg) || pkg == packageName) return
        val now = System.currentTimeMillis()
        if (scanning) return
        // 一律节流：界面不可能在 200ms 内变出新的跳过按钮，重复全树遍历只会掉帧
        if (now - lastScanAt < 200) return
        scanning = true
        scanNodes.clear()
        val deadline = android.os.SystemClock.elapsedRealtime() + SCAN_TIMEOUT_MS
        scanDeadline = deadline
        try {
            lastScanAt = now
            val root = rootInActiveWindow
            // root 也是本次扫描的短期引用，finally 里和所有子节点一起释放。
            if (root != null) scanNodes.add(root)
            if (root == null) {
                if (force) android.util.Log.d("SkipAd", "扫描 $pkg 跳过: 拿不到窗口内容")
                return
            }
            val windowAge = now - windowAt
            val inPoll = windowAge <= SPLASH_POLL_MS
            // 事件里的包名可能是上一个窗口的（切回本应用时尤其明显），以窗口根节点的包名为准
            val realPkg = root.packageName?.toString() ?: pkg
            if (realPkg == packageName || SKIP_PKGS.contains(realPkg)) return
            if (realPkg != policyPkg || now - policyAt > 5000) {
                currentPolicy = SkipPolicyStore.forPkg(this, realPkg)
                policyPkg = realPkg
                policyAt = now
            }
            val policy = currentPolicy
            if (policy.mode == PolicyMode.DISABLED && !observe) {
                // 银行 / 支付类：连窗口内容都不看，也不要留任何误点的可能。
                // 观察模式例外：只识别、只记录，依然不会执行任何点击。
                if (force) {
                    android.util.Log.d("SkipAd", "扫描 $realPkg 跳过: 该应用策略为不自动点击")
                }
                return
            }
            scanPopupRound = !inPoll
            if (!inPoll) {
                // 开屏窗之外只靠事件驱动，不延长主动轮询；弹窗轮单独节流
                if (!policy.mode.popup && !observe) return
                if (now - lastPopupScanAt < 1000) return
                lastPopupScanAt = now
                if (now - lastInteractAt < 900 && !observe) return
            }
            // 开屏场景按整个 12 秒窗口算（倒计时结束后才出现的按钮也属于开屏），
            // 但订阅 / 文本推断只在前 9 秒做，两者不能混为一谈。
            val inTextWindow = inPoll && windowAge <= SPLASH_TEXT_MS
            val screen = Rect(
                0, 0,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
            scanInSplash = inPoll
            scanScreenW = screen.width()
            scanScreenH = screen.height()
            val refs = ArrayList<Ref>(64)
            collect(root, refs, 0, screen, deadline, 0)
            if (force) {
                android.util.Log.d(
                    "SkipAd",
                    "扫描 $realPkg 节点=${refs.size} 开屏窗=${windowAge}ms 策略=${policy.mode}",
                )
            }
            if (refs.isEmpty()) return

            if (!inPoll) {
                scanPopup(refs, screen, now)
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
            if (!inTextWindow) return

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
            for (r in refs) {
                if (r.text.isEmpty() && r.desc.isEmpty() && r.id.isEmpty()) continue
                if (r.bounds.width() >= screen.width() * NARROW_RATIO) continue
                if (!SkipEngine.isSafeTarget(r.text, r.desc)) continue

                if (SkipEngine.customHit(r.text, r.desc, r.id, r.cls) &&
                    click(r, now, HitReason.TEXT_GUESS)
                ) return
                when (SkipEngine.scopedHit(realPkg, activity, r.text, r.desc, r.id, windowAge, r.cls)) {
                    SkipEngine.Action.CLICK -> if (click(r, now, HitReason.SUBSCRIPTION)) return
                    SkipEngine.Action.BACK -> if (doBack(now)) return
                    null -> {}
                }
            }
        } finally {
            // 扫描完成：把本次持有的所有节点引用一次性释放。
            // AccessibilityNodeInfo 是跨进程句柄，不能当普通对象缓存到下一轮。
            for (n in scanNodes) runCatching { n.recycle() }
            scanNodes.clear()
            scanning = false
        }
    }

    /**
     * 明确信号：SDK 关闭控件、明确跳过 / 关闭文案、四角关闭按钮。
     * 四角判定替代了原来写死的「右上角」，不同 App 把按钮放在哪都能认，
     * 但仍要求控件够小且是窄控件。
     */
    private fun scanExplicit(refs: List<Ref>, screen: Rect, now: Long): Boolean {
        for (r in refs) {
            if (r.bounds.width() >= screen.width() * NARROW_RATIO) continue
            if (!SkipEngine.isSafeTarget(r.text, r.desc)) continue
            val s = r.text.ifEmpty { r.desc }
            val corner = SkipSources.closeCornerHit(
                s, r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom,
                screen.width(), screen.height(),
            )
            val explicit = SkipEngine.builtinHit(r.text, r.desc, "") ||
                SkipSources.explicitAdClose(r.text, r.desc, r.id) || corner
            if (explicit && click(r, now, HitReason.EXPLICIT_TEXT)) return true
        }
        return false
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
        val cls = node.className?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // 不可见的节点永远不点：广告 SDK 常放一个 0×0 或屏幕外的假跳过按钮
            if (node.isVisibleToUser && rect.width() > 0 && rect.height() > 0 &&
                Rect.intersects(rect, screen)
            ) {
                out.add(Ref(index, text, desc, id, rect, cls, node.isClickable))
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
    private fun click(ref: Ref, now: Long, reason: HitReason): Boolean {
        // 倒计时文本每秒都在变（"跳过 5s" -> "跳过 4s"），直接拿原文当 key
        // 等于没有判重。去掉数字和空白后才是同一个控件。
        val key = ref.id + "|" + ref.text.replace(NUM, "") + "|" + ref.desc.replace(NUM, "")
        if (!observe) {
            if (sessionClicks >= MAX_SESSION_CLICKS) return false
            if ((clickCounts[key] ?: 0) >= MAX_SAME_CLICKS) return false
            // 本窗口已经点过一次，就只允许对「同一个控件」重试。
            if (sessionClicks >= 1 && key != lastClickKey) return false
            if (now - lastClickAt < CLICK_GAP_MS) return false
        }

        val b = ref.bounds
        val label = ref.text.ifEmpty { ref.desc }
        val age = now - windowAt
        val kind = SkipEngine.classify(
            ref.text, ref.desc, ref.id, ref.cls, b,
            scanScreenW, scanScreenH, scanInSplash, reason,
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
                    SkipSources.explicitAdClose(ref.text, ref.desc, ref.id),
            corner = SkipSources.closeCornerHit(
                label, b.left, b.top, b.right, b.bottom, scanScreenW, scanScreenH,
            ),
            scopedRule = SkipEngine.scopedHit(
                policyPkg, curAct, ref.text, ref.desc, ref.id, age, ref.cls,
            ) != null,
            globalRule = SkipEngine.globalHit(ref.text, ref.desc, ref.id, age, ref.cls),
            customRule = SkipEngine.customHit(ref.text, ref.desc, ref.id, ref.cls),
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
                    appLabel = runCatching {
                        SkipPolicyStore.displayName(this, policyPkg)
                    }.getOrDefault(policyPkg),
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
                android.util.Log.d(
                    "SkipAd",
                    "未点击: ${d.level.label} 分数=${d.score} 类型=${d.kind} " +
                        "证据=$reason 策略=${currentPolicy.mode} 规则=${d.rule} " +
                        "动作=${d.action} ${d.reasons.lastOrNull().orEmpty()}",
                )
                RejectLog.add(
                    Reject(
                        at = now,
                        pkg = policyPkg,
                        appLabel = SkipPolicyStore.displayName(this, policyPkg),
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
        val node = scanNodes.getOrNull(ref.nodeIndex) ?: return false
        val fresh = Rect()
        val denied = verifyBeforeClick(ref, node, fresh)
        if (denied != null) {
            android.util.Log.d("SkipAd", "二次验证未通过: $denied")
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

        // 自己可点就点，不可点就向上找可点击祖先（很多跳过按钮是 FrameLayout 里的 TextView）
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth <= 3) {
            if (cur.isClickable && cur.isEnabled &&
                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                mark(key, now, d)
                return true
            }
            val parent = cur.parent
            // parent 也是本次扫描拿到的引用，登记后由 finally 统一释放。
            if (parent != null) scanNodes.add(parent)
            cur = parent
            depth++
        }

        // 自绘控件既不可点也拿不到可点击祖先，退化成按坐标点一下。
        // 坐标用二次验证时重新读到的位置，不用扫描时的旧值。
        if (now - lastGestureAt > 700 && fresh.width() > 20 && fresh.height() > 20) {
            val path = Path().apply { moveTo(fresh.exactCenterX(), fresh.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
            lastGestureAt = now
            if (dispatchGesture(gesture, null, null)) {
                mark(key, now, d)
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
    private fun verifyBeforeClick(ref: Ref, node: AccessibilityNodeInfo, fresh: Rect): String? {
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
        if (!SkipEngine.isSafeTarget(t, node.contentDescription?.toString().orEmpty())) {
            return "控件不再像跳过按钮"
        }
        // 窗口归属再确认：用户可能已经从开屏页点进主页，这时按旧坐标点就是误触。
        val root = rootInActiveWindow ?: return "当前窗口读不到"
        try {
            val top = root.packageName?.toString().orEmpty()
            if (top.isNotEmpty() && top != policyPkg) return "前台已切到 $top"
        } finally {
            runCatching { root.recycle() }
        }
        if (currentPolicy.mode == PolicyMode.DISABLED) return "该应用策略为不自动点击"
        return null
    }

    private fun doBack(now: Long): Boolean {
        // 观察模式下连返回键都不按，只识别不执行。
        if (observe) return false
        if (now < cooldownUntil) return false
        if (now - lastBackAt < 800) return false
        if (sessionClicks >= MAX_SESSION_CLICKS) return false
        if (currentPolicy.mode == PolicyMode.DISABLED) return false
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) return false
        lastBackAt = now
        lastClickAt = now
        lastClickKey = "back"
        sessionClicks++
        cooldownUntil = now + Weights.COOLDOWN_MS
        prefs.bumpSkip()
        RunStats.onSkip(
            policyPkg, "subscription:back", Confidence.HIGH,
            AdKind.SPLASH, DecisionAction.CLICK, Weights.HIGH_MIN,
        )
        return true
    }

    private fun mark(key: String, now: Long, d: Decision) {
        lastClickKey = key
        lastClickAt = now
        sessionClicks++
        clickCounts[key] = (clickCounts[key] ?: 0) + 1
        // 规则冷却：点击成功后 2 秒内同窗口不再点；窗口变化会清零。
        cooldownUntil = now + Weights.COOLDOWN_MS
        evidence.clear()
        prefs.bumpSkip()
        android.util.Log.i(
            "SkipAd",
            "已点击跳过: $key 分数=${d.score} ${d.level.label} 规则=${d.rule} 冷却=2s",
        )
    }

    // ---------------- 规则热更新 ----------------

    private fun syncEngine() {
        SkipEngine.builtin = prefs.skipBuiltin
        observe = prefs.observeMode
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
        SkipAdState.onDisconnected(stateToken)
        // 授权还在、服务却断了 —— 多半是省电策略把服务冻住了。
        // 只提醒一次，不尝试后台重启（系统不允许），更不弹系统授权窗口。
        ServiceRecovery.warnAccessibility(this)
        android.util.Log.i("SkipAd", "无障碍服务已断开")
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

        /** 内容变化事件合并窗口：120ms 内的连续变化只遍历一次节点树。 */
        const val SCAN_MERGE_MS = 120L

        /** 单次遍历超时：超时即停，不让畸形界面拖住主线程。 */
        const val SCAN_TIMEOUT_MS = 120L

        /** 遍历深度 / 候选数 / 访问总数上限。 */
        const val MAX_DEPTH = 12
        const val MAX_NODES = 300
        const val MAX_VISITED = 400

        /** 文本类匹配只认比这更窄的控件（跳过按钮都是小标签）。 */
        const val NARROW_RATIO = 0.45f

        /** 二次验证时允许的像素漂移：超过就认为页面已经变了，不点。 */
        const val BOUNDS_DRIFT_PX = 16

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
