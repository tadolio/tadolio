// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.task

import com.wangzan101.mika.core.access.CommunityModel
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
) {
    val light: ChatModel? = light
    val vision: ChatModel? = vision
    val routedLight: ChatModel? = this.light.takeIf { distinctLight && !community }

    fun auxiliary(model: ChatModel?): ChatModel? = when {
        model == null -> null
        community -> CommunityModel(model)
        else -> model
    }
    companion object {
        /** Decorate underlying main transports before binding the run's routing identity. */
        fun fromFrozen(
            own: ProviderModels,
            decorateMain: (ChatModel) -> ChatModel,
        ): TaskModels? {
            val main = own.main ?: return null
            return TaskModels(decorateMain(main), own.light, own.vision, own.distinctLight, community = false)
        }
    }
}
