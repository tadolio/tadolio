// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole

/** Community selection shared by welcome and model settings. */
internal fun ProviderStore.chooseCommunity(kind: String = "compute") {
    val provider = connect("Tadolio Community", ProviderConfig.Kind.Community, HivoConfig.HUB_URL, null,
        models = listOf(ListedModel("compute", toolCalls = true, vision = true), ListedModel("operator", toolCalls = true, vision = true)))
    use(ModelRole.Main, provider.id, kind, Capabilities(toolCalls = true, vision = true, contextTokens = 32000, reasoning = false))
}
