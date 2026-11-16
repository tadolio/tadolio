// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.wangzan101.mika.R
import java.lang.ref.WeakReference

/** In-memory, fixed wording only. Never retains an SMS body or a full sender. */
internal object StandbyAlerts : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var current = WeakReference<Activity>(null)
    private var banner: TextView? = null
    private var message: String? = null
    private var speech: TextToSpeech? = null
    private var speechGeneration = 0

    fun show(context: Context, title: String) {
        val app = context.applicationContext
        main.post {
            message = title + "\n" + app.getString(R.string.standby_advice)
            current.get()?.let(::display)
            if (ElderStore.get(app).enabled.value) runCatching { speak(app, requireNotNull(message)) }
        }
    }

    private fun display(activity: Activity) {
        removeBanner()
        val root = activity.window.decorView as? FrameLayout ?: return
        val view = TextView(activity).apply {
            text = message ?: return
            textSize = if (ElderStore.get(activity).enabled.value) 28f else 22f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(170, 0, 0))
            setPadding(24, 48, 24, 24)
            elevation = 100f
            contentDescription = text
            setOnClickListener {
                message = null
                removeBanner()
                activity.startActivity(Intent(activity, StandbyActivity::class.java))
            }
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        banner = view
        view.announceForAccessibility(view.text)
    }

    private fun removeBanner() {
        banner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        banner = null
    }

    private fun speak(context: Context, text: String) {
        speech?.shutdown()
        val generation = ++speechGeneration
        val engine = TextToSpeech(context) { status ->
            main.post {
                if (generation != speechGeneration) return@post
                val active = speech ?: return@post
                if (status == TextToSpeech.SUCCESS) {
                    active.language = context.resources.configuration.locales[0]
                    active.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) { main.post { if (speech === active) { active.shutdown(); speech = null } } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) = onDone(id)
                    })
                    if (active.speak(text, TextToSpeech.QUEUE_FLUSH, null, "standby") == TextToSpeech.ERROR) {
                        active.shutdown()
                        speech = null
                    }
                } else { active.shutdown(); speech = null }
            }
        }
        speech = engine
        main.postDelayed({
            if (speech === engine) { engine.shutdown(); speech = null }
        }, 30000)
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        if (message != null) display(activity)
    }
    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) { current.clear(); removeBanner() }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
