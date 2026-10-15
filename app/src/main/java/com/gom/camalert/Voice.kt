package com.gom.camalert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** TTS 안내. 말하는 동안만 오디오 포커스를 잡아 음악 소리를 잠깐 줄인다. */
class Voice(context: Context) : TextToSpeech.OnInitListener {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val tts = TextToSpeech(context.applicationContext, this)
    private val speaking = AtomicInteger()
    private val pending = mutableListOf<String>()
    private var ready = false
    private var nextId = 0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.KOREAN
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
        ready = true
        pending.forEach(::say)
        pending.clear()
    }

    fun say(text: String) {
        if (!ready) {
            pending += text
            return
        }
        if (speaking.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "camalert-${nextId++}")
    }

    fun stop() {
        pending.clear()
        tts.stop()
        speaking.set(0)
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun finished() {
        if (speaking.decrementAndGet() <= 0) {
            speaking.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
