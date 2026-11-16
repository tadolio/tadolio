// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TriggerAlarmReceiver

/** Uses the existing alarm receiver. Android may defer background alarms during idle. */
internal object NumberPolling {
    const val ACTION = "com.wangzan101.mika.NUMBER_POLL"
    fun schedule(context: Context) {
        if (!com.wangzan101.mika.about.HivoConfig.communityEnabled || NumberClient.get(context).mine().isEmpty()) return
        val pending = PendingIntent.getBroadcast(context, 3030, Intent(context, TriggerAlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + if (NumberClient.get(context).moves.transferActive()) 60000L else 15 * 60000L, pending)
    }
    fun notifyMoved(context: Context, recovery: Boolean) = notify(context, tr(R.string.number_title),
        tr(if (recovery) R.string.move_recovered_elsewhere else R.string.move_moved),true)
    fun notifyTransfer(context: Context) = notify(context,tr(R.string.number_title),tr(R.string.move_confirmation_waiting),true)
    fun notifyNormal(context: Context, count: Int) = notify(
        context, tr(R.string.number_title), tr(R.string.number_pending_notification, count), false,
    )
    fun notifyUrgent(context: Context, number: String, summary: String) = notify(context, number, summary, true)

    fun notifyGovernance(context: Context, id: String, target: String) = notify(
        context, tr(R.string.governance_enforced, target),
        "${com.wangzan101.mika.about.HivoConfig.HUB_URL}/v1/gov/case/$id", true, id,
    )

    private fun notify(context: Context, title: String, text: String, urgent: Boolean, caseId: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (urgent) "hivo_number_urgent" else "hivo_number"
        manager.createNotificationChannel(NotificationChannel(channel, tr(R.string.number_title), if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
        val destination = Intent(context, com.wangzan101.mika.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (caseId != null) destination.setAction("hivo.gov.$caseId")
            .putExtra(GovernanceNavigation.EXTRA_CASE, caseId)
        val intent = PendingIntent.getActivity(context, 3030, destination, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(title)
            .setContentText(text).setContentIntent(intent)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
        runCatching { manager.notify(caseId?.hashCode() ?: if (urgent) title.hashCode() else 3030, notification) }
    }
}
