// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("hivo_core_0", maxSteps), FailKind.StepLimit)

    /** 收尾请求本身失败或仍没交出结果时，按触发收尾的原因落结局，不谎报步数用完。 */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("任务进度已经完成,但模型没有给出结果。", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("连续 ${NewDataTracker.LIMIT} 次没有读到新数据,任务已停止。", FailKind.Guard)
    }

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("hivo_core_1"), FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("hivo_core_2"), FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed =
        if (generateSequence(e) { it.cause }.take(32).any { it.message == "community_operator_daily_limit" })
            TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("hivo_operator_daily_limit"), FailKind.Model)
        else if (generateSequence(e) { it.cause }.take(32).any { it.message == "community_operator_offline" })
            TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("phone_operator_offline"), FailKind.Model)
        else if (generateSequence(e) { it.cause }.take(32).any { it.message?.startsWith("community_") == true })
            TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("hivo_community_unavailable"), FailKind.Model)
        else when (e) {
            is ModelError.TaskBudgetExceeded, is ModelError.DailyBudgetExceeded, is ModelError.ProactiveBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
            is ModelError.RateLimited -> TaskState.Failed(userReason(e), FailKind.Model, FailureCode.ModelRateLimited)
            is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
            else -> TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("hivo_core_3", e.message ?: e::class.simpleName), FailKind.Model)
        }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_4")
            "no_progress" -> if (treeBlind) "读不到这个界面的控件,点击和滑动画面也没有反应,该应用可能不响应 Tadolio 的自动化操作" else com.wangzan101.mika.core.i18n.UserText.get("hivo_core_5")
            "read_loop" -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_6")
            "too_many_failures" -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_7")
            "sandbox_loop" -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_8")
            else -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_9")
        }
        return TaskState.Failed(com.wangzan101.mika.core.i18n.UserText.get("hivo_core_10", v.code, why), FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_11")
    is ModelError.QuotaExceeded -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_12")
    is ModelError.IllegalHeader -> "API Key 含有无法使用的字符。请在设置 › 模型里只粘贴密钥本身，不要带说明文字、换行或表情。"
    is ModelError.TaskBudgetExceeded -> "已达到本任务 ${e.limit} Token 的预算上限,任务没有完成。"
    is ModelError.DailyBudgetExceeded ->
        "服务商「${e.providerName}」已达到今天 ${e.limit} Token 的预算上限,任务没有完成。本地日期变更后会自动恢复。"
    // 主动能力的调用不在任务里发,正常不会走到这里;万一走到,只如实说明,不提示用户去改什么
    is ModelError.ProactiveBudgetExceeded -> "主动能力已达到今天的预算上限。本地日期变更后会自动恢复。"
    is ModelError.ModelNotFound -> {
        // 404 不一定是模型名不对(地址不对,或网关、中转服务说的别的情况):有原话时照原话说,不断定模型不存在
        val check = if (e.model.isEmpty()) com.wangzan101.mika.core.i18n.UserText.get("hivo_core_13")
            else com.wangzan101.mika.core.i18n.UserText.get("hivo_core_14")
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else com.wangzan101.mika.core.i18n.UserText.get("hivo_core_15", e.model)) +
                    com.wangzan101.mika.core.i18n.UserText.get("hivo_core_16", quoted(e.detail), check)
            e.model.isEmpty() -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_17", check)
            else -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_18", e.model, check)
        }
    }
    is ModelError.ModelNotAvailable ->
        com.wangzan101.mika.core.i18n.UserText.get("hivo_core_31", e.model.ifEmpty { com.wangzan101.mika.core.i18n.UserText.get("hivo_current_model") })
    is ModelError.ToolsUnsupported ->
        com.wangzan101.mika.core.i18n.UserText.get("hivo_core_32", e.model.ifEmpty { com.wangzan101.mika.core.i18n.UserText.get("hivo_current_model") })
    is ModelError.ContextTooLong -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_19")
    is ModelError.ContentRefused -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_20")
    is ModelError.RateLimited -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_21")
    is ModelError.Network -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_22")
    is ModelError.ReasoningEffortRejected ->
        "模型不接受所选的思考强度。服务端回复：${quoted(e.detail)}。请到设置 › 模型,把思考强度改为默认或服务端支持的档位。"
    is ModelError.ServiceTierRejected ->
        "OpenAI 不接受所选的响应速度。" +
            (if (e.detail.isBlank()) "" else "服务端回复：${quoted(e.detail)}。") +
            "请到设置 › 模型 › 服务商，打开该模型，将响应速度改为默认或其他可用档位。"
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            com.wangzan101.mika.core.i18n.UserText.get("hivo_core_23")
        ProxyFailure.AuthFailed -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_24")
    }
    is ModelError.Server -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_25", e.status)
    is ModelError.StreamCut ->
        com.wangzan101.mika.core.i18n.UserText.get("hivo_core_26")
    is ModelError.BadResponse -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_27", com.wangzan101.mika.core.i18n.modelUserMessage(e))
    is ModelError.ChatGptLimitReached ->
        com.wangzan101.mika.core.i18n.UserText.get("hivo_core_28")
    is ModelError.ChatGptNotEligible ->
        com.wangzan101.mika.core.i18n.UserText.get("hivo_chatgpt_eligible", if (e.detail.isEmpty()) "" else com.wangzan101.mika.core.i18n.UserText.get("hivo_core_33", quoted(e.detail)))
    is ModelError.SignInExpired -> com.wangzan101.mika.core.i18n.UserText.get("hivo_core_29", e.account, e.account)
    is ModelError.AccountPlanNotAllowed ->
        com.wangzan101.mika.core.i18n.UserText.get("hivo_account_plan", e.account, (if (e.detail.isEmpty()) "" else com.wangzan101.mika.core.i18n.UserText.get("hivo_core_33", quoted(e.detail))), e.account)
    is ModelError.ChainFailed -> {
        // 主消息是主模型最初的错误(上面按它的类型生成),后面附整条链每个模型的状态码与一行摘要(MK-1 第 2 批)
        val trail = e.entries.joinToString("；") { (name, err) ->
            "「$name」${chainErrorTag(err)}：${err.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        userReason(e.primary) + "本任务把整条备援链都试过了，全部失败：$trail。"
    }
    is ModelError.GroupUnavailable ->
        "模型组「${e.group}」没有可用的模型${if (e.detail.isEmpty()) "" else "(${e.detail})"}。请到设置 › 模型检查组成员是否启用、能力是否符合,或稍后再试。"
    is ModelError.GroupExhausted ->
        "模型组「${e.group}」的模型都达到限额了${if (e.detail.isEmpty()) "" else "(${e.detail})"}。请稍后再试或调整组设置。"
}

/** 链失败记录里每个错误的短标签:认得出的写 HTTP 状态码,其余写故障名。 */
private fun chainErrorTag(e: ModelError): String = when (e) {
    is ModelError.Server -> " HTTP ${e.status}"
    is ModelError.RateLimited -> " HTTP 429"
    is ModelError.Auth -> " " + RetryPolicy.label(e)
    else -> " ${RetryPolicy.label(e)}"
}

/** 引在句中的服务端原话:去掉末尾一个句号(原话自带的),由引它的句子补上自己的「。」;省略号(截短的「…」、「...」)不动。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
