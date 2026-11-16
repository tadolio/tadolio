// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import android.content.Context

/** Persistent first-use triggers. A notification denial never blocks ordinary tasks. */
internal class DeferredSetup(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("deferred_setup", Context.MODE_PRIVATE))
    fun batteryNeeded() = prefs.getBoolean("battery_needed", false)
    fun needBattery() { prefs.edit().putBoolean("battery_needed", true).apply() }
    fun hasResult() = prefs.getBoolean("first_result", false)
    fun recordResult() { prefs.edit().putBoolean("first_result", true).apply() }
    fun referrerDismissed() = prefs.getBoolean("referrer_dismissed", false)
    fun dismissReferrer() { prefs.edit().putBoolean("referrer_dismissed", true).apply() }
}

internal object DeferredSetupPolicy {
    fun backgroundLoss(wasAlive: Boolean, alive: Boolean, background: Boolean, quit: Boolean) = wasAlive && !alive && background && !quit
    fun battery(scheduled: Boolean, backgroundLoss: Boolean, ignoring: Boolean) = (scheduled || backgroundLoss) && !ignoring
    // canBind is the number service's existing, creation-time-based seven-day check.
    fun referrer(hasResult: Boolean, canBind: Boolean, dismissed: Boolean) = hasResult && canBind && !dismissed
}
