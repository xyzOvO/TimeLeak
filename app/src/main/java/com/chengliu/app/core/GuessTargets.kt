package com.chengliu.app.core

/**
 * 「猜无文字跳过按钮」的适用范围：只对用户显式放行的应用开放。
 *
 * 为什么必须按应用放行：几何猜测靠的是「贴在角落、个头很小、自身可点」这类间接证据，
 * 在没有广告的普通页面上，这些特征和真的自绘跳过按钮完全一样——真机上就因此把某应用
 * 设置页右上角的「去设置」当成跳过按钮点掉过（「跳到系统设置页」还被判成跳过成功）。
 * 所以默认一个都不猜，由用户按应用逐个放行；放行的应用里也不再要求广告话术。
 */
object GuessTargets {

    /** 名单内的应用用 [BOLD]（放开猜）；名单外一律 [OFF]（连候选都不收）。 */
    enum class Mode { OFF, BOLD }

    /** 每个开屏窗口最多猜几次：大胆档两次（有的广告两层叠加），保守档一次。 */
    const val BOLD_TRIES = 2
    const val NORMAL_TRIES = 1

    fun modeFor(pkg: String?, apps: Set<String>): Mode =
        if (pkg != null && apps.contains(pkg)) Mode.BOLD else Mode.OFF

    fun maxTries(mode: Mode): Int = when (mode) {
        Mode.BOLD -> BOLD_TRIES
        Mode.OFF -> NORMAL_TRIES
    }

    /** 大胆档不拿广告话术当前置证据：名单是用户自己放行的，直接交给闸门去判。 */
    fun needsAdEvidence(mode: Mode): Boolean = mode != Mode.BOLD
}
