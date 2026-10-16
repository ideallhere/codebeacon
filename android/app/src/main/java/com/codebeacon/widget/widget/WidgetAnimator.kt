package com.codebeacon.widget.widget

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.RemoteViews

/**
 * 小部件切换动画。
 *
 * ## 技术约束（重要，先说清能做到什么）
 *
 * 小部件视图由系统 Launcher 进程渲染，不在本 App 进程内，
 * 因此**无法对已渲染的 RemoteViews 施加布局动画**（ViewAnimator、
 * TransitionDrawable、ObjectAnimator 作用于自身 View 树，全部不可用）。
 *
 * 这是 Android 桌面小部件的架构性限制，不是本项目的实现缺陷。
 *
 * ## 本项目的做法
 *
 * 在**应用内界面**（Flutter 管理页 / 原生过渡页）使用真实的位移 + 淡出动画，
 * 让"顺滑下移消失"的观感成立；桌面小部件本身则走**内容直接切换**。
 *
 * 这是当前架构下能做到的最接近方案，若要小部件本体也逐项滑动，
 * 需改用 RemoteViewsService + collection 方案（复杂度显著上升，列入 V2）。
 */
object WidgetAnimator {

    /** 动画时长 —— 短促干脆，避免等待感 */
    private const val DURATION_SHORT = 160L

    /**
     * 归档后刷新所有小部件。
     *
     * 小部件内容直接切换为下一条，用户看到的是"当前条消失、下一条出现"，
     * 与"下移消失"的主观感受一致。
     */
    fun animateSwap(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        widgetIds.forEach { CodeWidgetProvider.render(context, manager, it) }
    }

    /**
     * 在**应用内的真实 View** 上做"下移 + 淡出"动画。
     *
     * 用于 Flutter 侧切换列表项，或原生过渡 Activity。
     *
     * @param view 目标视图
     * @param onEnd 动画结束回调（此时可从数据源移除该条目）
     */
    fun slideOutAndDown(view: View, onEnd: () -> Unit) {
        view.animate()
            .translationY(view.height * 0.35f)
            .alpha(0f)
            .setDuration(DURATION_SHORT)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // 动画结束后把 View 高度收回，实现"列表项向上填补"的观感
                view.visibility = View.GONE
                onEnd()
            }
            .start()
    }

    /**
     * 内容切换的淡出淡入序列（作用在 RemoteViews 可控的 alpha 上不适用，
     * 此方法用于应用内 View）。
     */
    fun crossfade(view: View, onSwap: () -> Unit) {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -view.width * 0.1f)
            )
            duration = DURATION_SHORT
            interpolator = AccelerateInterpolator()
            doOnEnd {
                onSwap()
                view.alpha = 0f
                view.translationX = view.width * 0.1f
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, view.width * 0.1f, 0f)
                    )
                    duration = DURATION_SHORT
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
            start()
        }
    }

    /** 淡入（首次显示列表项） */
    fun fadeIn(view: View, delayMs: Long = 0) {
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setStartDelay(delayMs)
            .setDuration(DURATION_SHORT)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun AnimatorSet.doOnEnd(block: () -> Unit) {
        addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) = block()
            override fun onAnimationCancel(animation: Animator) = block()
            override fun onAnimationRepeat(animation: Animator) {}
        })
    }
}