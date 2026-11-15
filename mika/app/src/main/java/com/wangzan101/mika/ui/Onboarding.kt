// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.content.Context

/** 首次上手的两步；Phone 保留以兼容已保存的页面状态。 */
enum class OnboardingStep { Welcome, Model, Phone }

/**
 * 首次打开时走哪几步(纯函数,便于测试)。和手机引导一样按「看过没有」判断,不每次都弹:
 * - 欢迎和配置模型:还没有主模型、也没在上手流程里配过模型时才走。已经配过模型的(包括老用户)直接跳过。
 * 手机操作仅在任务需要时单独引导。
 */
object Onboarding {
    data class Inputs(
        val hasMainModel: Boolean,
        val modelSetupDone: Boolean,
        /** 系统够新且手机操作还没就绪。 */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        !i.hasMainModel && !i.modelSetupDone -> OnboardingStep.Welcome
        else -> null
    }

    /** [step] 做完后的下一步;没有了为 null(进对话)。 */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = when (step) {
        OnboardingStep.Welcome -> OnboardingStep.Model
        OnboardingStep.Model -> null
        OnboardingStep.Phone -> null
    }

    /** 底部分页点:首次上手里的第几步。 */
    fun dot(step: OnboardingStep): Int = step.ordinal
    const val DOTS = 2
}

/** 上手流程里「配置模型」做完没有;存在本机。 */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)

    fun readAloud(): Boolean = prefs.getBoolean("read_aloud", true)

    fun setReadAloud(enabled: Boolean) {
        prefs.edit().putBoolean("read_aloud", enabled).apply()
    }

    fun markModelSetupDone() {
        prefs.edit().putBoolean(MODEL_DONE, true).apply()
    }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
    }
}
