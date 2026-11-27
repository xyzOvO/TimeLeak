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

    /** 位置漂移时允许「按新坐标重锚」的最大位移，占屏幕宽/高的比例。 */
    const val REANCHOR_RATIO = 0.40f

    /**
     * 位置飘了还能不能按新坐标点。
     *
     * 背景：WebView 里的广告、入场动画、倒计时重排，都会让同一个按钮在几十毫秒内
     * 换个位置（真机上扫描到点击只隔 18ms 就飘出 16px）。飘了不等于换了东西，
     * 但也不能闭眼按新坐标点，所以要求两件事：尺寸基本没变、中心点没跑到屏幕别处。
     * 这两条不过，仍然按「控件位置变了」放弃。
     */
    fun canReanchor(
        oldW: Int, oldH: Int, oldCx: Int, oldCy: Int,
        newW: Int, newH: Int, newCx: Int, newCy: Int,
        screenW: Int, screenH: Int,
    ): Boolean {
        if (oldW <= 0 || oldH <= 0 || screenW <= 0 || screenH <= 0) return false
        val dw = kotlin.math.abs(newW - oldW)
        val dh = kotlin.math.abs(newH - oldH)
        if (dw > maxOf(8, oldW / 5) || dh > maxOf(8, oldH / 5)) return false
        val dx = kotlin.math.abs(newCx - oldCx)
        val dy = kotlin.math.abs(newCy - oldCy)
        return dx <= screenW * REANCHOR_RATIO && dy <= screenH * REANCHOR_RATIO
    }

    /** 内框是否完全落在外框里（含相等）。 */
    /**
     * 个头够小：自绘的跳过按钮通常只有屏宽的 5%~20%、屏高的 2%~10%。
     * 宽高任一项越界、或者面积超过屏面的 2.5%，都说明它更像卡片 / 列表项。
     */
    fun isSmallControl(w: Int, h: Int, screenW: Int, screenH: Int): Boolean {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return false
        if (w > screenW * 0.20f || h > screenH * 0.12f) return false
        val screenArea = screenW.toLong() * screenH.toLong()
        if (screenArea <= 0L) return false
        return w.toLong() * h.toLong() <= (screenArea * 0.025f).toLong()
    }

    /**
     * 贴在屏幕边缘带：跳过按钮几乎都躲在四角或上下边缘（左上 / 右上 / 右下）。
     * 要求纵向在上下 25% 之内、且横向在左右 18% 之内 —— 两条边都占，才算「角落」，
     * 屏幕中央那一大片热区一律不算。
     */
    fun isEdgeBand(cx: Int, cy: Int, screenW: Int, screenH: Int): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val vertical = cy <= screenH * 0.25f || cy >= screenH * 0.75f
        val horizontal = cx <= screenW * 0.18f || cx >= screenW * 0.82f
        return vertical && horizontal
    }

    fun contains(
        outerL: Int, outerT: Int, outerR: Int, outerB: Int,
        innerL: Int, innerT: Int, innerR: Int, innerB: Int,
    ): Boolean = innerL >= outerL && innerT >= outerT && innerR <= outerR && innerB <= outerB
}
