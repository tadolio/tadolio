// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

internal object PostNotifications {
    private const val CHANNEL = "hivo_post_standby"
    fun cosign(context: Context, post: com.wangzan101.mika.core.post.Post, stance: String, reason: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.post_suggestions),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val withdraw = PendingIntent.getActivity(context, 0,
            Intent(context, PostCardsActivity::class.java)
                .setData(android.net.Uri.parse("hivo-cosign://withdraw/${post.id}"))
                .putExtra("withdrawCosign", post.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publisher = com.wangzan101.mika.core.number.OfficialNumber.display(post.number, post.official)
        val text = publisher + "\n" + tr(if (stance == "sign") R.string.post_cosigned else R.string.post_declined, post.title, reason)
        val open = PendingIntent.getActivity(context, 3043, Intent(context, com.wangzan101.mika.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("cosign:${post.id}", 3043, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(tr(R.string.post_position))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(Notification.Action.Builder(null, tr(R.string.post_cosign_withdraw), withdraw).build())
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setAutoCancel(false).build())
    }
    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(context: Context, examined: Int, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.post_suggestions),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(context, 3034, Intent(context, PostCardsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(3034, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(tr(R.string.post_suggestions))
            .setContentText(tr(R.string.post_review_summary, examined, count))
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setAutoCancel(true).build())
    }
}
