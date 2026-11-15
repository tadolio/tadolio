// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.app.*
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wangzan101.mika.R
import com.wangzan101.mika.community.*
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Only newly posted default-SMS notifications. Never enumerate active notifications on connection. */
class StandbyService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<Long, StandbySms>>(16)
    private var connectedAt = Long.MAX_VALUE
    /** Notification key -> hash of the content last handed to triggers; chat apps update one key per conversation. */
    private val notificationKeys = mutableMapOf<String, Int>()
    override fun onCreate() {
        super.onCreate()
        scope.launch { for ((epoch, sms) in queue) process(sms, epoch) }
    }
    override fun onListenerConnected() { notificationKeys.clear(); connectedAt = System.currentTimeMillis() }
    override fun onListenerDisconnected() { notificationKeys.clear(); connectedAt = Long.MAX_VALUE }
    // Diagnostics log only stage names and counts, never SMS content or senders.
    private fun stage(s: String) { android.util.Log.i("HivoStandby", s) }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Tadolio's own notifications (trigger results, reminders) must never wake a trigger: that would loop.
        if (sbn.postTime >= connectedAt && sbn.packageName != packageName) {
            val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            // Same key with unchanged content is a refresh (progress, re-post); same key with new content is a new message.
            val hash = (title + "\u0000" + text).hashCode()
            if (notificationKeys.put(sbn.key, hash) != hash) {
                runCatching { com.wangzan101.mika.task.TriggerRuntime.notificationPosted(this, sbn.packageName, title, text, sbn.postTime, connectedAt) }
            }
        }
        val prefs = StandbyStore.get(this)
        if (!prefs.enabled() || !prefs.sms()) return
        if (sbn.postTime < connectedAt) return stage("skip: posted before listener connected")
        // Check package before touching notification extras.
        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(this) ?: return stage("skip: no default sms app")
        if (sbn.packageName != defaultPackage) return
        val since = maxOf(connectedAt, prefs.enabledSince())
        if (sbn.postTime < since || (sbn.notification.`when` > 0 && sbn.notification.`when` < since)) return stage("skip: older than enable time")
        val found = StandbyNotifications.parse(sbn.packageName, defaultPackage, sbn.notification, since)
        stage("sms notification: ${found.size} message(s)")
        found.forEach { queue.trySend(since to it) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { notificationKeys.remove(sbn.key) }

    private suspend fun process(sms: StandbySms, epoch: Long) {
        var communityModel: CommunityModel? = null
        try {
            val prefs = StandbyStore.get(this)
            if (!prefs.enabled() || !prefs.sms() || prefs.enabledSince() > epoch) return stage("skip: disabled meanwhile")
            if (!StandbyRules.candidate(sms.text)) return stage("skip: local prefilter says not a candidate")
            val store = ProviderStore.get(this)
            val cfg = store.configFor(ModelRole.Main)
            val route = StandbyRules.route(store.hybridSelected.value, cfg?.kind == ProviderConfig.Kind.Community, prefs.consent())
            val cached = prefs.cached(sms.text)
            if (cached == null && !prefs.reserve(sms.text)) return stage("skip: daily budget or duplicate reservation")
            stage(if (cached != null) "replay cached verdict" else "judging with route $route")
            val safe = StandbyRules.safe(sms.text)
            val local = cached?.second ?: (route == StandbyRules.Route.Local)
            val result = if (cached != null) {
                ScamResult(cached.first, "", "")
            } else if (route == StandbyRules.Route.Local) {
                ScamResult(ScamVerdict.Suspicious, getString(R.string.standby_local_reason), getString(R.string.standby_advice))
            } else {
                val model = when (route) {
                    StandbyRules.Route.Own -> if (store.hybridSelected.value) store.freezeHybridModels(ModelSession.fresh()).own.main
                        else store.modelFor(ModelRole.Main)
                    StandbyRules.Route.Community -> CommunityModel(CommunityClients.get(this), requireNotNull(cfg).model).also { communityModel = it }
                    else -> null
                } ?: error("no_model")
                when (val state = ScamCheck.state(model, safe, getString(R.string.scam_prompt))) {
                    is ScamState.Result -> state.result
                    else -> ScamResult(ScamVerdict.Uncertain, getString(R.string.standby_failed), "")
                }
            }
            if (!prefs.enabled() || !prefs.sms() || prefs.enabledSince() > epoch) return
            // Model explanations may quote the input. Keep only a fixed category sentence in durable records/notices.
            val saved = result.copy(reason = getString(if (result.verdict == ScamVerdict.Suspicious) when (StandbyRules.category(sms.text)) {
                "code" -> R.string.standby_reason_code
                "authority" -> R.string.standby_reason_authority
                "refund" -> R.string.standby_reason_refund
                "prize" -> R.string.standby_reason_prize
                "money" -> R.string.standby_reason_money
                else -> R.string.standby_reason_contact
            } else R.string.standby_uncertain_reason))
            if (cached == null) prefs.cache(sms.text, saved.verdict, local)
            prefs.record(sms.sender, saved, local)
            stage("verdict ${saved.verdict}")
            if (saved.verdict == ScamVerdict.Suspicious) {
                // Notification permission denial and family delivery failure do not change the verdict.
                try { alert(local, sms.sender) } catch (_: SecurityException) { }
                try { if (com.wangzan101.mika.about.HivoConfig.communityEnabled) {
                    val family = FamilyClient.get(this)
                    when (family.policy().mode("scamsms")) {
                        com.wangzan101.mika.core.action.FamilyMode.Private -> Unit
                        com.wangzan101.mika.core.action.FamilyMode.Notify -> {
                            family.elderPair()?.let { family.queueNotice(it.id, "scamsms") }
                            family.flushNotices()
                        }
                        com.wangzan101.mika.core.action.FamilyMode.Approve -> {
                            // A fixed-category request for family review; no reply can trigger an SMS action.
                            family.elderPair()?.let { pair -> family.send(FamilyRequest(java.util.UUID.randomUUID().toString(),
                                pair.id, StandbyRules.fingerprint("scamsms"), "scamsms", System.currentTimeMillis() + 600000)) }
                        }
                    }
                } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // No logs or exception messages containing SMS content. Failure is never a Normal verdict.
            val prefs = StandbyStore.get(this)
            if (prefs.enabled() && prefs.sms() && prefs.enabledSince() <= epoch) prefs.record(sms.sender, ScamResult(ScamVerdict.Uncertain,
                getString(R.string.standby_failed), ""))
        } finally { communityModel?.close() }
    }
    private fun alert(local: Boolean, sender: String) {
        val suffix = StandbyRules.suffix(sender)
        val conclusion = getString(if (local) R.string.standby_local_reason else R.string.standby_alert)
        val text = if (suffix.isEmpty()) conclusion else getString(R.string.standby_alert_sender, conclusion, suffix)
        StandbyAlerts.show(this, text)
        StandbyOverlay.show(this, conclusion, suffix)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("standby_sms", getString(R.string.standby_title), NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val pending = PendingIntent.getActivity(this, 21, Intent(this, StandbyActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val large = android.widget.RemoteViews(packageName, android.R.layout.simple_list_item_2).apply {
            setTextViewText(android.R.id.text1, text)
            setTextViewText(android.R.id.text2, getString(R.string.standby_advice))
            val size = if (ElderStore.get(this@StandbyService).enabled.value) 28f else 22f
            setTextViewTextSize(android.R.id.text1, android.util.TypedValue.COMPLEX_UNIT_SP, size)
            setTextViewTextSize(android.R.id.text2, android.util.TypedValue.COMPLEX_UNIT_SP, size)
        }
        manager.notify(2100, Notification.Builder(this, "standby_sms").setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setGroup("hivo.standby.scam.alerts").setCategory(Notification.CATEGORY_ALARM)
            .setContentTitle(text).setContentText(getString(R.string.standby_advice)).setContentIntent(pending)
            .setStyle(Notification.BigTextStyle().bigText(text + "\n" + getString(R.string.standby_advice)))
            .setCustomBigContentView(large)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
    }
    override fun onDestroy() { queue.close(); scope.cancel(); super.onDestroy() }
}
