package com.chengliu.app.core

/**
 * 多条件评分决策核心：零 Android 依赖，可在 PC 上离线回归。
 *
 * 从「关键词命中即点击」升级为「多个证据加权求和」：
 *  - 高置信度（>= [Weights.HIGH_MIN]）：策略允许才自动点击；
 *  - 中置信度（[Weights.MID_MIN] ~ HIGH）：只记录，或在同一目标短时间内
 *    再次出现且证据变强时升级一次；
 *  - 低置信度：不操作。
 *
 * 硬否决优先于分数：目标不安全、类型不是开屏/推广弹窗、策略不允许、
 * 用户正在操作、冷却期内，任何一种都直接不点，分数再高也没用。
 */
object DecisionCore {

    /** 与 SkipPolicy.allows() 完全一致的纯实现，避免离线回归时依赖 Android。 */
    fun policyAllows(kind: AdKind, reason: HitReason, mode: PolicyMode): Boolean {
        if (mode == PolicyMode.DISABLED) return false
        if (!kind.clickable) return false
        if (kind == AdKind.SPLASH && !mode.splash) return false
        if (kind == AdKind.POPUP && !mode.popup) return false
        if (!mode.textGuess &&
            (reason == HitReason.SUBSCRIPTION || reason == HitReason.TEXT_GUESS)
        ) return false
        if (!mode.gesture && reason == HitReason.COORDINATE) return false
        return true
    }

    fun decide(f: NodeFacts): Decision {
        val reasons = ArrayList<String>(10)
        val label = f.label

        // ---------------- 硬否决：不进入评分 ----------------
        fun reject(why: String): Decision = Decision(
            score = 0,
            level = Confidence.LOW,
            kind = f.kind,
            action = if (f.observe) DecisionAction.OBSERVE else DecisionAction.SKIP,
            rule = ruleOf(f),
            reasons = reasons + why,
            policyAllowed = false,
        )

        if (!f.visible) return reject("节点不可见")
        if (!f.safeTarget) return reject("业务按钮 / 否定词，禁止点击")
        if (f.kind == AdKind.REWARDED) return reject("激励视频，关闭会丢失奖励")
        if (f.kind == AdKind.PAID) return reject("付费 / 下载广告 CTA，点击等于打开广告")
        if (f.kind == AdKind.FEED) return reject("信息流广告，关闭按钮多半是「不感兴趣」")
        if (f.kind == AdKind.UNKNOWN) return reject("无法确认广告类型")
        if (f.mode == PolicyMode.DISABLED) return reject("应用策略：不自动点击")
        if (f.popupRound && !f.userIdle) return reject("用户正在操作，避免抢点击")

        val policyAllowed = policyAllows(f.kind, f.reason, f.mode)
        if (!policyAllowed) return reject("应用策略不允许该场景 / 该证据强度")

        // ---------------- 多条件加权 ----------------
        var s = 0
        fun add(v: Int, why: String) {
            s += v
            reasons.add("$why ${if (v >= 0) "+" else ""}$v")
        }

        if (f.sdkId) add(Weights.SDK_ID, "广告 SDK 控件 id")
        if (f.sdkClose) add(Weights.SDK_CLOSE, "SDK 关闭控件")
        if (f.explicitText) add(Weights.EXPLICIT_TEXT, "明确跳过 / 关闭文案")
        if (f.scopedRule) add(Weights.SCOPED_RULE, "订阅作用域规则")
        if (f.globalRule) add(Weights.GLOBAL_RULE, "订阅全局规则")
        if (f.customRule) add(Weights.CUSTOM_RULE, "自定义关键词")
        if (f.coordinateRule) add(Weights.COORDINATE, "坐标兜底规则")

        if (f.inSplash) add(Weights.IN_SPLASH, "开屏时间窗内")
        if (f.freshWindow) reasons.add("新窗口会话")

        val wide = f.screenW > 0 && f.width >= f.screenW * 0.45f
        if (wide) add(Weights.WIDE, "宽控件（可能是列表项）")
        else add(Weights.NARROW, "窄控件")

        if (label.length > 12) add(Weights.LONG_TEXT, "文案过长")
        else if (label.isNotEmpty()) add(Weights.SHORT_TEXT, "短文案")

        if (f.corner) add(Weights.CORNER, "屏幕四角关闭按钮")
        if (f.clickable) add(Weights.CLICKABLE, "控件可点击")
        if (Regex("skip|close|splash|advert", RegexOption.IGNORE_CASE).containsMatchIn(f.cls)) {
            add(Weights.AD_CLASS, "广告 / 跳过类名")
        }
        if (f.scopedRule) add(Weights.SCOPED_BONUS, "规则带包名作用域")
        if (Regex("\\d").containsMatchIn(label)) add(Weights.COUNTDOWN, "带倒计时数字")
        if (Regex("""^(跳过|skip)\s*[（(]?\s*0\s*[s秒]?\s*[)）]?\s*[>»›→]?$""", RegexOption.IGNORE_CASE)
                .matches(label) || label.trim() == "跳过 >"
        ) add(Weights.COUNTDOWN_END, "倒计时已结束")

        if (!f.inSplash && !f.popupRound) add(Weights.OUTSIDE_WINDOW, "开屏时间窗之外")
        if (f.globalRule) add(Weights.GLOBAL_PENALTY, "全局规则未限定应用")
        if (!f.sdkId && !f.sdkClose && !f.explicitText && !f.corner) {
            add(Weights.SOFT_ONLY, "缺少硬信号")
        }

        val score = s.coerceIn(0, 100)
        var level = when {
            score >= Weights.HIGH_MIN -> Confidence.HIGH
            score >= Weights.MID_MIN -> Confidence.MID
            else -> Confidence.LOW
        }

        // 中置信度：同一目标短时间内二次出现且证据变强，升级一次。
        // 坐标 / 纯推断不允许靠重复升级，否则高频刷新就能把低分刷成自动点击。
        var upgraded = false
        if (level == Confidence.MID && f.evidenceRepeat &&
            score >= Weights.MID_UPGRADE_MIN &&
            (f.sdkId || f.sdkClose || f.explicitText || f.corner || f.scopedRule)
        ) {
            level = Confidence.HIGH
            upgraded = true
            reasons.add("二次证据确认，升级为高置信度")
        }

        // 冷却：点击成功后同窗口内不再点；窗口变化会由服务把 cooldownActive 清掉。
        val cooldownBlocked = f.cooldownActive && !f.freshWindow
        if (cooldownBlocked) reasons.add("冷却期内（${Weights.COOLDOWN_MS / 1000} 秒），同窗口不重复点击")

        // 观察模式：只识别、只记录，永不点击。
        val action = when {
            f.observe -> DecisionAction.OBSERVE
            cooldownBlocked || level != Confidence.HIGH -> {
                if (level == Confidence.MID) DecisionAction.WAIT else DecisionAction.SKIP
            }
            else -> DecisionAction.CLICK
        }

        return Decision(
            score = score,
            level = level,
            kind = f.kind,
            action = action,
            rule = ruleOf(f),
            reasons = reasons,
            policyAllowed = policyAllowed,
            cooldownBlocked = cooldownBlocked,
            upgraded = upgraded,
        )
    }

    /** 观察模式与回归报告里显示的规则标识，尽量还原「是哪条规则命中的」。 */
    private fun ruleOf(f: NodeFacts): String {
        val tail = f.id.substringAfterLast('/').ifEmpty { f.label }
        return when {
            f.sdkId -> "sdk_id:$tail"
            f.sdkClose -> "sdk_close:$tail"
            f.explicitText -> "builtin:${f.label.ifEmpty { tail }}"
            f.scopedRule -> "gkd:${f.pkg}:$tail"
            f.globalRule -> "gkd_global:$tail"
            f.customRule -> "custom:${f.label.ifEmpty { tail }}"
            f.coordinateRule -> "coordinate:${f.left},${f.top}"
            else -> "none"
        }
    }
}
