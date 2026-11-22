// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.i18n

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.net.ProxyFailure

/** User-facing rendering only. Original exceptions and model/tool feedback stay unchanged. */
fun modelUserMessage(error: ModelError): String = if (UserText.source == null) error.message.orEmpty() else when (error) {
    is ModelError.IllegalHeader, is ModelError.ProactiveBudgetExceeded, is ModelError.ServiceTierRejected,
    is ModelError.ChainFailed, is ModelError.GroupUnavailable, is ModelError.GroupExhausted,
    is ModelError.TaskBudgetExceeded, is ModelError.DailyBudgetExceeded -> error.message.orEmpty()
    is ModelError.Auth -> UserText.get("hivo_error_auth")
    is ModelError.QuotaExceeded -> UserText.get("hivo_error_quota")
    is ModelError.RateLimited -> UserText.get("hivo_error_rate")
    is ModelError.ContextTooLong -> UserText.get("hivo_error_context")
    is ModelError.ModelNotFound -> UserText.get("hivo_error_missing", error.model)
    is ModelError.ModelNotAvailable -> UserText.get("hivo_error_access", error.model)
    is ModelError.ToolsUnsupported -> UserText.get("hivo_error_tools", error.model)
    is ModelError.ReasoningEffortRejected -> UserText.get("hivo_error_bad", error.message.orEmpty())
    is ModelError.Network -> UserText.get("hivo_error_network")
    is ModelError.StreamCut -> UserText.get("hivo_error_stream")
    is ModelError.Server -> UserText.get("hivo_error_server", error.status)
    is ModelError.ContentRefused -> UserText.get("hivo_error_refused")
    is ModelError.ChatGptLimitReached -> UserText.get("hivo_error_chatgpt_limit")
    is ModelError.ChatGptNotEligible -> UserText.get("hivo_error_chatgpt_access", error.detail)
    is ModelError.SignInExpired -> UserText.get("hivo_error_expired")
    is ModelError.AccountPlanNotAllowed -> UserText.get("hivo_error_plan", error.account)
    is ModelError.Proxy -> if (error.failure == ProxyFailure.Unreachable) UserText.get("hivo_error_proxy") else UserText.get("hivo_error_proxy_auth")
    is ModelError.BadResponse -> UserText.get("hivo_error_bad", modelResponseDetail(error.detail))
}

private fun modelResponseDetail(detail: String): String = when (detail) {
    "换令牌的返回里没有 access_token" -> UserText.get("hivo_error_token")
    "缺少 content" -> UserText.get("hivo_error_content")
    "缺少 candidates" -> UserText.get("hivo_error_candidates")
    "缺少 choices" -> UserText.get("hivo_error_choices")
    "缺少 message" -> UserText.get("hivo_error_message")
    "functionCall 缺少 name" -> UserText.get("hivo_error_function")
    "流式响应不是合法 JSON" -> UserText.get("hivo_error_stream_json")
    "流式响应没有内容" -> UserText.get("hivo_error_stream_empty")
    "工具调用流缺少名称或编号" -> UserText.get("hivo_error_tool_stream")
    "返回内容不是合法 JSON" -> UserText.get("hivo_error_json")
    "工具调用缺少编号" -> UserText.get("hivo_error_call_id")
    "工具调用缺少名称" -> UserText.get("hivo_error_call_name")
    "服务端不支持这个接口" -> UserText.get("hivo_error_api")
    "视觉模型没有给出回答" -> UserText.get("hivo_error_vision")
    else -> when {
        detail.startsWith("返回的不是流式内容(Content-Type: ") && detail.endsWith(")") ->
            UserText.get("hivo_error_not_stream", detail.removePrefix("返回的不是流式内容(Content-Type: ").dropLast(1))
        detail.startsWith("服务端不支持请求里的 ") ->
            UserText.get("hivo_error_unsupported", detail.removePrefix("服务端不支持请求里的 ").let { if (it == "某项内容") UserText.get("hivo_error_unspecified") else it })
        else -> detail // Provider-supplied text is data, not application copy.
    }
}
