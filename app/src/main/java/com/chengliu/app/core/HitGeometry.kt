package com.chengliu.app.core

/**
 * 几何判定：把「真正会被点到的是谁」这类问题从无障碍服务里抽出来。
 *
 * 纯算术、零 Android 依赖，可以在电脑上直接单测。
 *
 * 背景：开屏广告里常见一种骗法——画一个「跳过 3」的胶囊当诱饵，
 * 它自己不可点，唯一的可点祖先是铺满整屏的广告图。文案、位置、字号
 * 全和真按钮一样，静态特征分不出来，但「可点祖先的面积」分得出来。
 */
object HitGeometry {

    /** 可点祖先覆盖超过这个比例，就不当作跳过按钮，而是广告热区。 */
    const val TRAP_AREA_RATIO = 0.50f

    /** 控件面积占屏幕的比例，0~1。参数为 0 时返回 0，调用方无需先校验。 */
    fun areaRatio(w: Int, h: Int, screenW: Int, screenH: Int): Float {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return 0f
        val total = screenW.toLong() * screenH.toLong()
        val area = w.toLong() * h.toLong()
        return (area.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 假按钮判据：控件自己不可点，而唯一能点的祖先盖住了大半屏幕。
     *
     * 真跳过按钮要么自己可点，要么包在一个小容器里；一旦可点目标是
     * 整张广告图，点下去就是打开落地页，不能因为文案写着「跳过」就信。
     */
    fun isTrapAncestor(
        selfClickable: Boolean,
        ancestorW: Int,
        ancestorH: Int,
        screenW: Int,
        screenH: Int,
    ): Boolean {
        if (selfClickable) return false
        return areaRatio(ancestorW, ancestorH, screenW, screenH) > TRAP_AREA_RATIO
    }

    /** 内框是否完全落在外框里（含相等）。 */
    fun contains(
        outerL: Int, outerT: Int, outerR: Int, outerB: Int,
        innerL: Int, innerT: Int, innerR: Int, innerB: Int,
    ): Boolean = innerL >= outerL && innerT >= outerT && innerR <= outerR && innerB <= outerB
}
