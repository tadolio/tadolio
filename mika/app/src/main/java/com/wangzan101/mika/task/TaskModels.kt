// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.task

import com.wangzan101.mika.core.access.CommunityModel
import com.wangzan101.mika.core.access.HybridModel
import com.wangzan101.mika.core.access.HybridCompute
import com.wangzan101.mika.core.access.HybridScreenBoundary
import com.wangzan101.mika.settings.ProviderModels
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.core.model.ChatModel

/** The models selected during preparation, retained through cancellation and outcome judgment. */
internal class TaskModels(
    val main: ChatModel,
    light: ChatModel?,
    vision: ChatModel?,
    distinctLight: Boolean,
    private val community: Boolean,
    val hybridBoundary: HybridScreenBoundary? = null,
) {
    val light: ChatModel? = if (main is HybridModel) light?.let(main::auxiliary) else light
    val vision: ChatModel? = if (main is HybridModel) vision?.let(main::auxiliary) else vision
    val routedLight: ChatModel? = this.light.takeIf { distinctLight && (!community || main is HybridModel) }

    fun auxiliary(model: ChatModel?): ChatModel? = when {
        model == null -> null
        main is HybridModel -> main.auxiliary(model)
        community -> CommunityModel(model)
        else -> model
    }
    companion object {
        /** Decorate underlying main transports before binding the run's routing identity. */
        fun fromFrozen(
            own: ProviderModels,
            decorateMain: (ChatModel) -> ChatModel,
            communityModel: ChatModel? = null,
            remote: Boolean = false,
            step: () -> Int = { 1 },
            appAccess: com.wangzan101.mika.core.access.AppAccessPolicy = com.wangzan101.mika.core.access.AppAccessPolicy.ALLOW,
        ): TaskModels? {
            val main = own.main ?: return null
            require(own.mainConfig?.kind != ProviderConfig.Kind.Community) { "hybrid_requires_own_model" }
            require(main !is HybridModel && main !is CommunityModel) { "hybrid_requires_unwrapped_own_model" }
            if (communityModel == null) return TaskModels(
                decorateMain(main), own.light, own.vision, own.distinctLight, community = false)
            // Validate every retained Own role before decorators can hide a foreign routing wrapper.
            require(listOfNotNull(own.light, own.vision).none { it is HybridModel || it is CommunityModel }) {
                "hybrid_requires_unwrapped_own_model"
            }
            val compute = HybridCompute(ownAvailable = true, remote = remote)
            return TaskModels(
                HybridModel(decorateMain(communityModel), decorateMain(main), compute, step),
                own.light, own.vision, own.distinctLight, community = true,
                hybridBoundary = HybridScreenBoundary(compute, appAccess, step),
            )
        }
    }
}
