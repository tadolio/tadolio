// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.app.Notification
import android.os.Bundle

internal data class StandbySms(val sender: String, val text: String)

internal object StandbyNotifications {
    fun parse(pkg: String, defaultPackage: String?, notification: Notification, since: Long = Long.MIN_VALUE): List<StandbySms> {
        if (defaultPackage.isNullOrBlank() || pkg != defaultPackage ||
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        val extras = notification.extras ?: return emptyList()
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val parsed = if (!messages.isNullOrEmpty()) messages.mapNotNull { item ->
            val b = item as? Bundle ?: return@mapNotNull null
            if (b.getLong("time", 0) < since) return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val person = if (android.os.Build.VERSION.SDK_INT >= 28) b.getParcelable<android.app.Person>("sender_person") else null
            StandbySms(person?.name?.toString() ?: b.getCharSequence("sender")?.toString().orEmpty(), text)
        } else null
        return fields(pkg, defaultPackage, false, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(), extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(), parsed)
    }
    /** Notification field selection is pure so Samsung/Google formats can be tested offline. */
    fun fields(pkg: String, defaultPackage: String?, summary: Boolean, title: String?, text: String?, bigText: String?, messages: List<StandbySms>?): List<StandbySms> {
        if (defaultPackage.isNullOrBlank() || pkg != defaultPackage || summary) return emptyList()
        if (messages != null) return messages.filter { it.text.isNotBlank() && it.text.length <= 20000 }.takeLast(1)
        val body = bigText?.takeIf { it.isNotBlank() } ?: text ?: return emptyList()
        return if (body.isBlank() || body.length > 20000) emptyList() else listOf(StandbySms(title.orEmpty(), body))
    }
}
