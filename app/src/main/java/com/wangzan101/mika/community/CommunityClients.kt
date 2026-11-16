// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import android.content.Context
import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.io.File

object CommunityClients {
    private var cached: CommunityClient? = null
    @Synchronized fun get(context: Context): CommunityClient {
        check(HivoConfig.communityEnabled)
        return cached?.takeIf { it.url == HivoConfig.HUB_URL.trimEnd('/') } ?: CommunityClient(
            HivoConfig.HUB_URL, File(context.applicationContext.filesDir, "community"), KeystoreKeyCipher("hivo-hub-client"), useCommunityOnce = { OperatorOfflineActivity.ask(context.applicationContext) }
        ).also { cached = it }
    }
}
