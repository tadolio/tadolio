// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.Permit
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.run.RunProvenance

/** 一次动作执行前的审批结论。 */
sealed interface Decision {
    /** 不用判,或判过放行(含付款时用户的同意对上了)。 */
    data object Clear : Decision
    /** 放行,但找不到点在哪个元素上:运行记录里留痕。 */
    data class Traced(val detail: String) : Decision
    /** 找不到点在哪个元素上,这一来源不盲点。 */
    data object Unresolved : Decision
    /**
     * 这一步要用户这一次的同意(花钱,或 [ask] 不为空时是工具声明要问的不在屏上的一步),没有可用的。
     * 有 [ask] 时调用方能问人就当场问(见 [FollowUp.approve])。
     */
    data class Required(val hit: Irreversible, val ask: ConfirmationRequest? = null) : Decision
    /** 有同意,但和这一步对不上;那次同意已作废。 */
    data class Mismatch(val hit: Irreversible, val reason: String) : Decision
    /** 要花钱,本次运行却问不到手机旁的用户,直接拒绝。 */
    data class Denied(val hit: Irreversible) : Decision
    /** 作用在 Mika 自己身上,不做。 */
    data object OwnApp : Decision
}

/**
 * 不可逆判定、审批与同意核对。整个代码库里只有这里调用 [IrreversibleDetector] 的判定和 [ConfirmationGate.permits]
 * (有测试守着),各来源只在 [OriginPolicy] 里写明哪里不同;问不问由 [ApprovalPolicy.decide] 定(付款和工具声明要问的问)。
 */
class ConfirmDecision(
    private val tools: ToolDispatcher,
    confirmations: ConfirmationGate?,
    approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val risks: RiskJudge = ClassifiedRisk,
    private val recorder: ActionRecorder? = null,
    private val provenance: RunProvenance = RunProvenance(),
    /** 开工就是自主执行(没有确认关卡):付款也不问,界面目标猜测也不阻断动作;中途换成自主看 [ApprovalSettings.autonomous]。 */
    private val autonomous: Boolean = false,
    /** Mika 自己的包名:作用在它上面的写操作一律不做(不自己给自己点同意、改设置)。 */
    private val selfPackage: String? = null,
) {
    private val ownConfirmations = confirmations
    private val ownApprovals = approvals
    private val communityConfirmations = confirmations?.takeIf { it.phoneOnly } ?: ConfirmationGate(phoneOnly = true)
    private val confirmations get() = if (tools.community) communityConfirmations else ownConfirmations
    private val approvals get() = if (tools.community && !ownApprovals.familyRequired) ApprovalSettings.ASK else ownApprovals

    /**
     * 刚判过的这一步是对外动作(发送、提交:判定命中、放行后要做的)时它所在的网站或 App(见 [OutwardPacing.siteOf]),
     * 执行入口据此和上一次对外动作隔开;不是为 null。自主执行时也认(按模型手里那一屏判,不另读屏),节奏不因此失效。
     */
    var outwardSite: String? = null
        private set
    private var noticeCategory: String? = null

    /** 判定放在锁屏等待之后:按此刻重新读的一屏判。 */
    suspend fun judge(tool: String, args: JsonObject, origin: Origin): Decision {
        outwardSite = null
        noticeCategory = null
        if (!tools.community && !approvals.familyRequired && (autonomous || approvals.autonomous()) &&
            tools.approvalRisk(tool, args)?.alwaysConfirm != true) {
            val target = tools.gateTarget(tool)
            if (IrreversibleDetector.needsCheck(target, args)) {
                val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
                outwardSite = outward(IrreversibleDetector.classify(target, aim.args, aim.aimed), aim.aimed)
            }
            return Decision.Clear
        }
        val policy = origin.policy
        val target = tools.gateTarget(tool)
        // 这一步落在哪里由界面一侧给出;点看图找到的 @vN 时参数已换成它登记的坐标和找的文字
        val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
        val checked = aim.args
        if (ownApp(tool, args, aim.aimed)) return Decision.OwnApp
        tools.approvalRisk(tool, checked)?.let { declared ->
            return approve(tool, checked, origin, null, declared, null, null, null) ?: Decision.Clear
        }
        if (!tools.community && !approvals.familyRequired && confirmations == null && !policy.judgeWithoutGate) return Decision.Clear
        val verb = tools.verb(tool)
        val typing = if (verb == Verb.TypeText) (args["text"] as? JsonPrimitive)?.contentOrNull else null
        // 没有可判的不可逆字样的动作(一般改动、界面间移动)不花钱,不用问
        if (!IrreversibleDetector.needsCheck(target, args) && !(approvals.familyRequired && typing != null)) return Decision.Clear
        val snap = aim.aimed
        val fresh = aim.now()
        if (ownApp(tool, args, fresh)) return Decision.OwnApp
        // 复看确认过的所见文字(@vN 才有):读屏里没有文字时(自绘界面)付款的同意按它核对象
        val seen = aim.seen
        val communityHit = if (tools.community) IrreversibleDetector.classifyCommunity(target, checked, snap, fresh) else null
        if (communityHit != null) tools.promoteHybrid(com.wangzan101.mika.core.access.HybridReason.IrreversibleAction)
        val hit = if (tools.community) communityHit else IrreversibleDetector.classify(target, checked, snap, fresh)
        var familyHit = if (approvals.familyRequired) IrreversibleDetector.classifyCommunity(target, checked, snap, fresh) else hit
        if (approvals.familyRequired) {
            val current = fresh ?: snap
            val element = current?.let { ClassifiedRisk.target(verb, checked, it) }
            val labels = current?.elements?.joinToString("\n") { it.name }.orEmpty()
            val sensitive = typing != null && (Regex("验证码|校验码|驗證碼|校驗碼|\\botp\\b|verification code", RegexOption.IGNORE_CASE).containsMatchIn(element?.name.orEmpty()) ||
                com.wangzan101.mika.core.access.CommunitySensitive.redact(typing, labels).contains("[redacted:otp]"))
            val securityWords = Regex("账号|账户|密码|安全|验证|手机号|帳號|帳戶|密碼|驗證|電話號碼|account|password|security|verification|two.factor|2fa|authenticator|phone number", RegexOption.IGNORE_CASE)
            val security = element?.name?.let { label ->
                securityWords.containsMatchIn(label) ||
                    (Regex("确定|确认|保存|应用|提交|继续|確定|確認|儲存|套用|繼續|ok|confirm|save|apply|submit|continue", RegexOption.IGNORE_CASE).matches(label.trim()) &&
                        current?.elements?.any { securityWords.containsMatchIn(it.name) } == true)
            } == true
            if (sensitive || security) familyHit = Irreversible(IrrevAction.other, if (sensitive) "verification" else "security")
            val transfer = Regex("转账|轉帳|轉賬|\\btransfer\\b", RegexOption.IGNORE_CASE)
            val generic = Regex("确定|确认|完成|確定|確認|ok|confirm", RegexOption.IGNORE_CASE).matches(element?.name.orEmpty().trim())
            if (transfer.containsMatchIn(element?.name.orEmpty()) || (generic && transfer.containsMatchIn(labels)))
                familyHit = Irreversible(IrrevAction.pay, "transfer")
            if (Regex("安裝|立即安裝|確認安裝|繼續安裝").matches(element?.name.orEmpty().trim()))
                familyHit = Irreversible(IrrevAction.install, "install")
        }
        val risk = risks.assess(verb, hit) { fresh ?: snap }
        outwardSite = outward(hit, fresh ?: snap)
        approve(tool, checked, origin, hit, risk, fresh ?: snap, typing, seen, familyHit)?.let { return it }
        if (hit != null || !IrreversibleDetector.unresolved(target, checked, snap, fresh)) return Decision.Clear
        return if (policy.refuseUnresolved) Decision.Unresolved else Decision.Traced(unresolvedDetail(args, aim.readAgeMs, fresh != null))
    }

    /** 命中的是发送、提交时这一屏的网站或 App。 */
    private fun outward(hit: Irreversible?, screen: ScreenSnapshot?): String? =
        if (hit?.action == IrrevAction.send || hit?.action == IrrevAction.submit) OutwardPacing.siteOf(screen?.packageName, screen?.web != null) else null

    /** 写操作作用在 Mika 自己身上:屏上是 Mika,或要打开 Mika(含它的应用详情页)。 */
    private fun ownApp(tool: String, args: JsonObject, screen: ScreenSnapshot?): Boolean {
        val self = selfPackage ?: return false
        if (!tools.writesScreen(tool)) return false
        if (screen?.packageName == self) return true
        return listOf("package", "uri").any { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.let { self in it } == true }
    }

    /** 按来源判;要花钱时核对用户的一次性同意。放行为 null。 */
    private suspend fun approve(
        tool: String, checked: JsonObject, origin: Origin, hit: Irreversible?, risk: Risk, screen: ScreenSnapshot?,
        typing: String?, seen: String?, familyCandidate: Irreversible? = hit,
    ): Decision? {
        if (hit != null || risk.level.outward || risk.ask != null)
            tools.promoteHybrid(com.wangzan101.mika.core.access.HybridReason.IrreversibleAction)
        val policy = origin.policy
        val familyHit = familyCandidate ?: Irreversible(IrrevAction.other, tool)
        val category = if (familyHit.matched in setOf("verification", "security", "transfer")) familyHit.matched else familyHit.action.name
        val mode = if (approvals.familyRequired && (familyCandidate != null || risk.level.outward)) approvals.familyMode(category) else FamilyMode.Private
        if (mode == FamilyMode.Notify) noticeCategory = category
        if (mode == FamilyMode.Approve) {
            if (provenance.origin != com.wangzan101.mika.core.run.RunOrigin.Ui || provenance.attendance == com.wangzan101.mika.core.run.Attendance.Unattended)
                return Decision.Denied(familyHit)
            // Hash locally: the family receives no parameters, input values or screen text.
            val binding = listOf(tool, checked.toString(), screen?.packageName.orEmpty(), screen?.elements.toString()).joinToString("\n")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(binding.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!approvals.familyApprove(digest, category)) return Decision.Denied(familyHit)
            val now = tools.interfaces.actionTarget(tools.declared(tool), checked).now()
            if (now?.packageName != screen?.packageName || now?.elements != screen?.elements) return Decision.Denied(familyHit)
        }
        if (!risk.alwaysConfirm && !tools.community && (autonomous || approvals.autonomous())) return null
        if (!risk.alwaysConfirm && !tools.community && confirmations == null && !policy.judgeWithoutGate) return null
        // 没命中不可逆判定的(工具自己声明的风险):按动作落在的元素记成「其他」
        val asked = hit ?: risk.ask?.let { Irreversible(it.action, it.content) } ?: Irreversible(IrrevAction.other,
            screen?.let { ClassifiedRisk.target(tools.verb(tool), checked, it) }?.name?.trim()?.takeIf { it.isNotEmpty() } ?: tool)
        // 对外、要花钱和没放行的都记下;界面上的改动不记
        fun audit(decision: String) {
            if (decision != "allowed" || risk.level.outward || risk.payment) recorder?.approval(origin, tool, asked, risk, decision)
        }
        when (if (tools.community && (hit != null || risk.level.outward)) {
            if (provenance.attendance == com.wangzan101.mika.core.run.Attendance.Unattended ||
                provenance.origin != com.wangzan101.mika.core.run.RunOrigin.Ui) Approval.Deny else Approval.Ask
        } else ApprovalPolicy.decide(risk, provenance)) {
            Approval.Allow -> return null.also { audit("allowed") }
            Approval.Deny -> return Decision.Denied(asked).also { audit("denied") }
            Approval.Ask -> Unit
        }
        val required = Decision.Required(asked, risk.ask)
        val gate = confirmations?.takeIf { policy.usesGrant && (!tools.community || it.phoneOnly) } ?: return required.also { audit("required") }
        val permit = risk.ask?.let { gate.permitsExactly(it, origin.step) } ?: gate.permits(asked, screen, origin.step, typing, listOfNotNull(seen))
        return when (val p = permit) {
            Permit.Allowed -> null.also { audit("allowed") }
            Permit.NoGrant -> required.also { audit("required") }
            is Permit.Mismatch -> Decision.Mismatch(asked, p.reason).also { audit("mismatch") }
        }
    }

    /** 执行了一个写操作:同意按它的规则作废或保留。 */
    fun noteWrite(tool: String) {
        approvals.familyWrite()
        confirmations?.noteWrite(tools.verb(tool))
    }

    /** Only an executed, successful write can produce an operation notice. No network wait here. */
    fun takeNotice(): String? = noticeCategory.also { noticeCategory = null }
    fun noteResult(category: String?, success: Boolean) {
        if (success && category != null) approvals.familyNotify(category)
    }

    /** 留痕:点的是哪个编号、那次读屏多久以前,或点的坐标。 */
    private fun unresolvedDetail(args: JsonObject, readAgeMs: Long?, freshRead: Boolean): String {
        fun arg(k: String) = args[k]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        val ref = arg("ref")
        return if (ref != null) {
            val sid = arg("snapshotId")
            "ref=$ref snapshotId=$sid age=${readAgeMs?.let { "${it}ms" } ?: "unknown"}"
        } else "x=${arg("x")},y=${arg("y")} freshRead=$freshRead"
    }

    companion object {
        /**
         * 不读屏的预判(大小模型分工用):按上一次读屏,这一步可能是不可逆的,或不知道点的是什么(看图得来、找不到元素);
         * 这样的一步交主模型做。
         */
        fun mayBeIrreversible(tools: ToolDispatcher, tool: String, args: JsonObject): Boolean {
            val target = tools.gateTarget(tool)
            if (!IrreversibleDetector.needsCheck(target, args)) return false
            val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
            val checked = aim.args
            val snap = aim.aimed
            return aim.bySight || IrreversibleDetector.classify(target, checked, snap) != null ||
                IrreversibleDetector.unresolved(target, checked, snap)
        }
    }
}
