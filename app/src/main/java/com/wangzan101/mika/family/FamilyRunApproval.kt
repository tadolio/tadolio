// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.core.action.ApprovalSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class FamilyRunApproval(private val client: FamilyClient, private val pair: String, private val runId: String,
    private val policy: com.wangzan101.mika.core.action.FamilyPolicy, private val local: ApprovalSettings) : ApprovalSettings {
    private var granted: Pair<String, Long>? = null
    private val sharing = FamilySharing(policy, FamilyApproval(client), pair) { category -> client.queueNotice(pair, category) }
    override fun autonomous() = local.autonomous()
    override val familyRequired = policy.active
    override fun familyMode(category: String) = sharing.mode(category)
    override fun familyNotify(category: String) { runCatching { sharing.completed(category) } }
    override suspend fun familyApprove(digest: String, category: String): Boolean {
        if (client.elderPair()?.id != pair) return false
        if (granted?.first == digest && android.os.SystemClock.elapsedRealtime() < granted!!.second) return true
        _waiting.value = true
        return try {
            val started = android.os.SystemClock.elapsedRealtime()
            val approved = sharing.authorize(FamilyCrypto.hash("$runId\n$digest"), category) && client.elderPair()?.id == pair
            if (approved) granted = digest to (started + 600000)
            approved
        } finally { _waiting.value = false }
    }
    override fun familyWrite() { granted = null }
    companion object {
        private val _waiting = MutableStateFlow(false)
        val waiting = _waiting.asStateFlow()
    }
}
