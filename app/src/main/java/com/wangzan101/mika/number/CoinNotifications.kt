// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.wangzan101.mika.core.coins.CoinEntry
import android.content.Context
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

internal object CoinNotifications {
    fun escrow(context: Context, entry: CoinEntry, reminder: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "hivo_coin_incoming"
        manager.createNotificationChannel(NotificationChannel(channel, tr(R.string.coins_wallet),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val receipt = entry.receipt ?: return
        val date = entry.releaseAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }.orEmpty()
        val text = if (reminder) tr(R.string.coins_reminder, entry.counterparty.orEmpty(), entry.title.orEmpty(), date) + " / " +
            tr(R.string.coins_due_rule, date, tr(if (entry.milestones.firstOrNull()?.atDue == "release")
                R.string.coins_due_release else R.string.coins_due_refund))
            else tr(R.string.coins_proposed, entry.counterparty.orEmpty(), entry.milestones.size, entry.heldAmount ?: 0)
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, CoinEscrowActivity::class.java).setData(Uri.parse("hivo-escrow://receipt/$receipt"))
                .putExtra("receipt", receipt), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("escrow:${if (reminder) entry.id else receipt}", 3037, Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(tr(R.string.coins_wallet))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build())
    }

    fun show(context: Context, amount: Long, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "hivo_coin_incoming"
        manager.createNotificationChannel(NotificationChannel(channel, tr(R.string.coins_wallet),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        manager.notify(3035, Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(tr(R.string.coins_wallet))
            .setContentText(tr(R.string.coins_credited, amount, count))
            .setOnlyAlertOnce(true).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
    }
}
