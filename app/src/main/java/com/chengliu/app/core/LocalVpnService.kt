package com.chengliu.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.chengliu.app.BuildConfig
import com.chengliu.app.MainActivity
import com.chengliu.app.R
import com.chengliu.app.data.BuiltinRules
import com.chengliu.app.data.RuleStore
import com.chengliu.app.data.RuleTier
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 只把虚拟 DNS 地址加进路由，其余流量根本不进 VPN。
 * 因此不需要用户态 TCP/IP 栈，也不需要 Go/NDK 引擎。
 */
class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.chengliu.app.action.START"
        const val ACTION_STOP = "com.chengliu.app.action.STOP"
        const val ACTION_RELOAD = "com.chengliu.app.action.RELOAD"
        const val ACTION_RESTART = "com.chengliu.app.action.RESTART"

        // 虚拟 DNS 不能等于 tun 自己的地址：内核会在 local 路由表生成
        // `local 10.225.0.1 dev tun0 scope host`，发往它的包被当作本机收包直接回环，
        // 永远进不了 tun 的用户态队列。换成同段另一个地址才能被路由进隧道。
        const val VPN_ADDRESS = "10.225.0.1"
        const val VIRTUAL_DNS = "10.225.0.2"

        // IPv6 同一套规则，用 ULA 段（fc00::/7）里的固定前缀，绝不与公网地址冲突。
        const val VPN_ADDRESS6 = "fd00:225::1"
        const val VIRTUAL_DNS6 = "fd00:225::2"

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "chengliu_local_filter"
        private const val TAG = "ChengliuVpn"

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, LocalVpnService::class.java).setAction(action)
            try {
                if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "无法下发指令 $action", e)
            }
        }
    }

    private lateinit var prefs: AppPrefs
    private val engine = RuleEngine()

    @Volatile
    private var handler: DnsHandler = BlocklistDnsHandler(engine, ::onBlocked)

    private var fd: ParcelFileDescriptor? = null

    @Volatile
    private var running = false
    private var reader: Thread? = null

    // 每个查询都会阻塞在一次 socket 收发上，用固定线程池会被上游超时堵死，必须按需扩容
    private var pool: ExecutorService = Executors.newCachedThreadPool()

    /** 规则在后台线程解析，回到主线程重建处理器，避免多线程同时改字段。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cacheKey = ""

    @Volatile
    private var cacheUpstream: UpstreamResolver? = null

    /** 专门给 DoH 主机名做明文引导，复用同一个实例，避免每次查询都重新挑网络 */
    @Volatile
    private var bootstrapResolver: SystemUdpResolver? = null

    /** 需要吸进隧道的路由；判包与建隧道共用同一份，避免两边算得不一样 */
    @Volatile
    private var capture: CaptureRoutes = CaptureRoutes.EMPTY

    /** 上一次生效的接管集合签名；私人 DNS 地址变化时用来判断是否真的需要重建隧道 */
    private var lastCaptureKey = ""

    /** 监听底层网络变化（含系统私人 DNS 校验完成），以便运行中动态避让 */
    private var linkCallback: ConnectivityManager.NetworkCallback? = null

    private val virtualDns4: ByteArray = CaptureRoutes.literal(VIRTUAL_DNS) ?: ByteArray(4)
    private val virtualDns6: ByteArray = CaptureRoutes.literal(VIRTUAL_DNS6) ?: ByteArray(16)

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        Counters.init(this)
        createChannel()
        reloadRules()
        registerLinkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTunnel()
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
            }
            ACTION_RELOAD -> reloadRules()
            ACTION_RESTART -> {
                // 路由集合变了只能重建隧道；不 stopSelf，避免前台服务被系统回收
                stopTunnel()
                startTunnel()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        unregisterLinkCallback()
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (running) return
        VpnState.setError(null)
        VpnState.resetForeign()
        // 系统对前台服务只有约 5 秒窗口，先挂通知再建隧道
        startForeground(NOTIF_ID, buildNotification())
        reloadRules()

        // 严格私人 DNS（DoT）模式下，系统不会在 DoT 失败后回退到明文。
        // 而 VPN 网络自己的路由表里没有私人 DNS 服务器的地址（又不能把底层路由「漏」回隧道），
        // 于是 VPN 网络的 DoT 校验必然失败，严格模式直接判 DNS 失效 → 整机解析失败。
        // 这种情况我们只能「不当系统的 DNS」：把解析交还给底层网络（它的 DoT 能正常校验），
        // 代价是系统 DNS 不再经过本地过滤——这本就是严格私人 DNS 的固有属性。
        // 应用硬编码的 8.8.8.8 等仍会被路由接管并过滤，不受影响。
        val strictPdns = PrivateDns.read(this).strict

        val builder = Builder()
            .setSession(getString(R.string.vpn_label))
            .addAddress(VPN_ADDRESS, 32)
            .addAddress(VPN_ADDRESS6, 64)
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .setMtu(1500)

        if (!strictPdns) {
            // 正常情况下我们就是系统的 DNS：虚拟地址既是本接口地址，也是系统查询的落点。
            builder
                .addDnsServer(VIRTUAL_DNS)
                .addRoute(VIRTUAL_DNS, 32)
                // IPv6 也必须给一个地址与 DNS：只接管 v4 时，系统或应用直接查 v6 的 DNS 就绕开了。
                // 用 /64 让内核把整段 ULA 认成本接口，虚拟 DNS 再单独走一条 /128 路由。
                .addDnsServer(VIRTUAL_DNS6)
                .addRoute(VIRTUAL_DNS6, 128)
        }

        if (prefs.blockBypass) {
            // 把常见公共 DNS 的单地址也纳入隧道。应用硬编码 8.8.8.8 / 114.114.114.114
            // 的查询原本根本不进来，是最容易被忽略的一条明文泄露通道；
            // 加进来以后，53 端口照常接管转发，443/853 直接丢弃（加密 DNS 不该绕过过滤）。
            capture.entries.forEach { addRoute(builder, it) }
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish 失败", e)
            null
        }

        if (pfd == null) {
            VpnState.setError("无法建立本地 VPN：请确认已授权，且没有其他应用独占 VPN")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        fd = pfd
        if (pool.isShutdown || pool.isTerminated) pool = Executors.newCachedThreadPool()
        // 隧道重建后网络栈可能已经换了，旧的引导解析器要作废
        bootstrapResolver = null
        cacheUpstream = null
        running = true
        VpnState.setRunning(true)
        reader = Thread({ readLoop(pfd) }, "chengliu-dns").also { it.start() }
    }

    /** 只接受归一化后的 CIDR；v4 与 v6 都按 addRoute(addr, prefix) 加。 */
    private fun addRoute(builder: Builder, cidr: String) {
        val slash = cidr.lastIndexOf('/')
        if (slash <= 0) return
        val addr = cidr.substring(0, slash)
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return
        try {
            builder.addRoute(addr, prefix)
        } catch (_: Exception) {
        }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(2048)
        var seen = 0
        while (running) {
            val n = try {
                input.read(buf)
            } catch (_: IOException) {
                break
            }
            if (n <= 0) continue
            if (BuildConfig.DEBUG && seen < 20) {
                seen++
                val head = (0 until minOf(40, n)).joinToString("") {
                    "%02x".format(buf[it].toInt() and 0xFF)
                }
                Log.d(TAG, "读到包 n=$n 头=$head")
            }
            val packet = buf.copyOf(n)
            try {
                pool.execute { handle(packet, output) }
            } catch (_: Exception) {
                // 停止过程中线程池可能已关闭，丢掉这一个包即可
            }
        }
    }

    private fun handle(packet: ByteArray, output: FileOutputStream) {
        val v4 = IpWire.parseIpv4Udp(packet, packet.size)
        if (v4 != null) {
            // 目的地址不是虚拟 DNS，说明这个查询是应用硬编码了上游地址，
            // 被旁路路由吸进来的。单独计数，让用户看得见「有人试图绕过」。
            val relayed = !v4.dst.contentEquals(virtualDns4)
            Counters.add(queries = 1L, bypass = if (relayed) 1L else 0L)
            val answer = answerPayload(v4.payload) ?: return
            write(output, IpWire.buildIpv4Udp(v4.dst, v4.src, 53, v4.srcPort, answer))
            return
        }

        val v6 = IpWire.parseIpv6Udp(packet, packet.size)
        if (v6 != null) {
            val relayed = !v6.dst.contentEquals(virtualDns6)
            Counters.add(queries = 1L, bypass = if (relayed) 1L else 0L)
            val answer = answerPayload(v6.payload) ?: return
            write(output, IpWire.buildIpv6Udp(v6.dst, v6.src, 53, v6.srcPort, answer))
            return
        }

        dropNonDns(packet)
    }

    /** 拦截判断 + 上游转发。v4 / v6 共用，避免两条路径的规则慢慢跑偏。 */
    private fun answerPayload(raw: ByteArray): ByteArray? {
        val q = DnsWire.parseQuery(raw, raw.size)
        // 表外 DoH 域名仍需一次明文解析，否则会形成「解析 DNS 又要 DNS」的递归；
        // 内置表命中的地址直接用 IP 连，这条分支不会触发。
        val bootstrap = q != null && isBootstrap(q.name)
        val local = if (q != null && !bootstrap) handler.intercept(q, raw) else null
        if (local != null) return local
        if (q != null && !bootstrap) watch(q.name)

        // 转发前剥掉 ECS：上游不必知道你在哪个区县
        val forward = if (prefs.stripEcs) DnsWire.stripEcs(raw, raw.size) ?: raw else raw
        return if (bootstrap) {
            bootstrap().resolve(forward, forward.size)
        } else {
            upstream().resolve(forward, forward.size)
        }
    }

    /** 记录「应用在找自己的加密 DNS 服务器」这件事；只存内存，不落盘。 */
    private fun watch(name: String) {
        if (DohEndpoints.isBuiltin(name)) return
        if (!DohEndpoints.looksLikeEndpoint(name)) return
        DohWatch.observe(name)
    }

    private fun dropNonDns(packet: ByteArray) {
        // tun 接口自身会周期性冒出 IPv6 链路本地组播（MLD 报告、路由器通告、邻居请求），
        // 以及 ICMPv6；这是内核协议栈行为而非外部流量，计进「异常」会误报。
        if (IpWire.isIpv6KernelNoise(packet, packet.size)) return

        val v4 = IpWire.dstIpv4(packet, packet.size)
        if (v4 != null) {
            // 发往虚拟 DNS 的非 53 流量（系统想对它做 DoT、DoH 探测）：丢掉即可，这是预期行为
            if (v4.contentEquals(virtualDns4)) return
            // 发往公共 DNS 或自定义目标的其他流量（DoT 853 / DoH 443 / ICMP）：
            // 隧道里没有转发能力，只能丢弃。这正是「不让应用自带加密 DNS 绕过过滤」的预期结果。
            if (capture.coversV4(v4) || Privacy.isBypassDns(v4)) {
                Counters.add(bypass = 1L)
                return
            }
        } else {
            val v6 = IpWire.dstIpv6(packet, packet.size)
            if (v6 != null) {
                if (v6.contentEquals(virtualDns6)) return
                if (capture.coversV6(v6) || Privacy.isBypassDns6(v6)) {
                    Counters.add(bypass = 1L)
                    return
                }
            }
        }

        if (BuildConfig.DEBUG) Log.w(TAG, "非 DNS 包被丢弃 len=${packet.size}")
        // 非 DNS 包本不该进来；超过阈值即说明该系统不支持「仅 DNS 路由」
        VpnState.markForeign()
    }

    private fun write(output: FileOutputStream, data: ByteArray) {
        synchronized(output) {
            try {
                output.write(data)
                output.flush()
            } catch (_: IOException) {
            }
        }
    }

    private fun isBootstrap(name: String): Boolean {
        val u = upstream()
        return u is DohResolver && u.needsBootstrap() && name.equals(u.host, ignoreCase = true)
    }

    private fun bootstrap(): SystemUdpResolver {
        bootstrapResolver?.let { return it }
        return synchronized(this) {
            bootstrapResolver ?: SystemUdpResolver(this).also { bootstrapResolver = it }
        }
    }

    private fun upstream(): UpstreamResolver = synchronized(this) {
        val key = "${prefs.leakGuard}|${prefs.upstreamMode}|${prefs.upstreamIp}|${prefs.dohUrl}"
        val hit = cacheUpstream
        if (hit != null && cacheKey == key) return@synchronized hit
        // 防泄露模式下一律走加密上游：跟随系统 / 指定 IP 都是明文，留着等于留后门。
        val mode = if (prefs.leakGuard) "doh" else prefs.upstreamMode
        val made: UpstreamResolver = when (mode) {
            // protect 交给 DohResolver 自己调：这些 DNS 地址也在隧道路由里，
            // 不自建 socket 就会被自己的隧道吞掉
            "doh" -> DohResolver(prefs.dohUrl) { protect(it) }
            "ip" -> SystemUdpResolver(this, prefs.upstreamIp)
            else -> SystemUdpResolver(this)
        }
        cacheKey = key
        cacheUpstream = made
        made
    }

    private fun reloadRules() {
        val tier = RuleTier.of(prefs.ruleTier)
        val builtinOn = prefs.builtinEnabled
        val custom = prefs.customBlocked
        val sub = RuleParser.parseBoth(prefs.subscriptionCache)

        fun apply() {
            val builtin = if (builtinOn) RuleStore.blockedOf(tier.key) else emptySet()
            // 放行表恒定生效：内置档位自带的 110 条误拦修正、手动放行、订阅里的 @@ 例外，
            // 即使关掉内置拦截也保留——否则用户自己加的订阅会立刻把系统探测域名一起拦掉。
            val allow = BuiltinRules.ALLOWED + RuleStore.allowedOf(tier.key) +
                prefs.customAllowed + sub.allowed
            engine.replace(builtin, custom, sub.blocked, allow)
            BlockLog.enabled = prefs.blockLog
            if (!prefs.blockLog) BlockLog.clear()
            handler = BlocklistDnsHandler(engine, ::onBlocked)

            // 系统私人 DNS 指定了服务器时，它的地址必须放行，否则 DoT 被隧道吞掉等于整机断解析。
            // 任意主机名（含不在内置表里的）都要解析成真实 IP 才能排除，所以走带网络解析的版本。
            val pdnsIps = resolvePrivateDns(PrivateDns.read(this))
            val (routes, dropped) = CapturePlan.resolve(prefs.leakTargetIps, pdnsIps)
            capture = routes
            lastCaptureKey = routes.entries.joinToString(",")
            if (dropped.isNotEmpty()) Log.i(TAG, "私人 DNS 地址已从接管中排除：${dropped.size} 个 -> $dropped")

            // 上游配置可能刚改过，丢掉缓存让下一次查询重新构建
            synchronized(this) {
                cacheKey = ""
                cacheUpstream = null
            }
        }

        if (RuleStore.isLoaded(tier.key)) {
            apply()
            return
        }

        // 规则还没读出来：先按空表把隧道起起来（联网优先），后台读完再重建一次处理器。
        // 强档 9.6 万条文本解析要几百毫秒，放在这里同步做会顶到前台服务的 5 秒窗口。
        apply()
        RuleStore.load(this, tier.key) {
            mainHandler.post {
                if (RuleTier.of(prefs.ruleTier) == tier && prefs.builtinEnabled == builtinOn) {
                    Log.i(TAG, "内置规则加载完成：${tier.label} ${RuleStore.blockedOf(tier.key).size} 条" +
                        " / 放行 ${RuleStore.allowedOf(tier.key).size} 条")
                    reloadRules()
                }
            }
        }
    }

    /**
     * 系统私人 DNS 在严格模式下会用一个具体地址做 DoT。这个地址必须从接管列表里排除，
     * 否则它的 853 被隧道吞掉会让整机解析停摆。
     *
     * 已知服务商（dns.google / dns.alidns.com …）在内置表里有 IP；但用户完全可能填一个
     * 自定义主机名（自托管、NextDNS 子域等），那些不在表里，必须做一次真实解析。
     * 解析带 2 秒上限，超时就退化为「只排除内置表里能查到的」，不会卡住启动。
     */
    private fun resolvePrivateDns(state: PrivateDns.State): List<String> {
        val out = LinkedHashSet<String>()
        out += PrivateDns.addresses(state) // 字面量 IP 或内置表里能直接查到的
        if (!state.strict) return out.toList()
        val spec = state.specifier
        if (spec.isEmpty()) return out.toList()
        if (CaptureRoutes.literal(spec) != null) {
            out += spec
            return out.toList()
        }
        val resolved = mutableListOf<String>()
        val th = Thread {
            try {
                InetAddress.getAllByName(spec).forEach { a ->
                    val h = a.hostAddress?.substringBefore('%') ?: return@forEach
                    if (CaptureRoutes.literal(h) != null) resolved.add(h)
                }
            } catch (_: Exception) {
                // 解析失败就别排除，宁可让界面提示用户去关掉私人 DNS
            }
        }
        th.start()
        try {
            th.join(2000)
        } catch (_: InterruptedException) {
        }
        out += resolved
        return out.toList()
    }

    /** 监听底层网络（非 VPN）的链路变化；系统私人 DNS 校验完成时这里会收到回调。 */
    private fun registerLinkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                maybeRestartForPrivateDns()
            }

            override fun onAvailable(network: Network) {
                maybeRestartForPrivateDns()
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
            linkCallback = cb
        } catch (_: Exception) {
            linkCallback = null
        }
    }

    private fun unregisterLinkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        linkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        linkCallback = null
    }

    /**
     * 重新算一遍接管集合；只有私人 DNS 地址变化导致集合真的变了，才重建隧道，
     * 避免 WiFi 抖动点一下就断一次网。
     */
    private fun maybeRestartForPrivateDns() {
        val pdnsIps = resolvePrivateDns(PrivateDns.read(this))
        val (routes, _) = CapturePlan.resolve(prefs.leakTargetIps, pdnsIps)
        val key = routes.entries.joinToString(",")
        if (key == lastCaptureKey) return
        lastCaptureKey = key
        capture = routes
        if (running) send(this, ACTION_RESTART)
    }

    /** 两条互不干扰的记录：落盘的只有计数，内存里才有「谁被拦了」。 */
    private fun onBlocked(name: String, rule: String, source: RuleSource) {
        Counters.add(blocked = 1L)
        BlockLog.record(name, rule, source)
    }

    private fun stopTunnel() {
        running = false
        VpnState.setRunning(false)
        try {
            fd?.close()
        } catch (_: IOException) {
        }
        fd = null
        try {
            reader?.interrupt()
        } catch (_: Exception) {
        }
        reader = null
        pool.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL_ID, "本地过滤", NotificationManager.IMPORTANCE_LOW)
        ch.description = "仅用于保持本地 DNS 过滤运行"
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
