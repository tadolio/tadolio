// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.i18n.UserText
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CommunityFailureCopyTest {
    @Test fun providerDailyLimitExplainsTomorrowRecovery() {
        val result=StopRules.modelFailed(ModelError.Network(IOException("community_request_failed", IOException("community_operator_daily_limit"))))
        assertEquals(UserText.get("hivo_operator_daily_limit"),result.reason)
    }

    @Test fun communityCauseUsesCommunityCopyAndOwnNetworkCopyStaysUnchanged() {
        val community = StopRules.modelFailed(ModelError.Network(IOException("community_request_failed")))
        assertEquals(UserText.get("hivo_community_unavailable"), community.reason)
        assertFalse(community.reason.contains("Base URL"))
        assertEquals(UserText.get("hivo_core_22"), StopRules.modelFailed(ModelError.Network()).reason)
    }
}
