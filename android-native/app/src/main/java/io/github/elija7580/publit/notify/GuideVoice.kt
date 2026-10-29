package io.github.elija7580.publit.notify

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import io.github.elija7580.publit.core.L10n

/** Spoken directions in the trip guide (GuideVoice on iOS), only while the guide is open. */
object GuideVoice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(ctx: Context, text: String) {
        val t = tts ?: TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) { tts?.language = L10n.locale; pending?.let { speak(it) }; pending = null }
        }.also {
            tts = it
            it.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
        if (ready) speak(text) else pending = text
    }

    /** A newer instruction replaces one still being spoken. */
    private fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide") }

    fun stop() { tts?.stop() }
    fun shutdown() { tts?.shutdown(); tts = null; ready = false }
}
