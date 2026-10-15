package com.gom.camalert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** 시작/재개는 올라가는 두 음, 멈춤/종료는 내려가는 두 음 */
class Chime(context: Context) {

    private val prefs = Prefs(context)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val main = Handler(Looper.getMainLooper())

    fun start() = play(LOW, HIGH)
    fun stop() = play(HIGH, LOW)

    private fun play(vararg notes: Double) {
        val pcm = render(notes)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        val duck = prefs.duckOthers
        if (duck) audio.requestAudioFocus(focus)
        track.play()
        val durationMs = pcm.size * 1000L / SAMPLE_RATE
        main.postDelayed({
            track.release()
            if (duck) audio.abandonAudioFocusRequest(focus)
        }, durationMs + 100)
    }

    private fun render(notes: DoubleArray): ShortArray {
        val perNote = SAMPLE_RATE * NOTE_MS / 1000
        val out = ShortArray(perNote * notes.size)
        notes.forEachIndexed { n, freq ->
            for (i in 0 until perNote) {
                val t = i.toDouble() / SAMPLE_RATE
                // 짧은 어택 뒤 서서히 줄어드는 벨 소리
                val envelope = minOf(1.0, i / (SAMPLE_RATE * 0.005)) * exp(-t * 7)
                val sample = sin(2 * PI * freq * t) + 0.3 * sin(4 * PI * freq * t)
                out[n * perNote + i] = (sample / 1.3 * envelope * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val NOTE_MS = 260
        const val LOW = 783.99   // G5
        const val HIGH = 1046.50 // C6
    }
}
