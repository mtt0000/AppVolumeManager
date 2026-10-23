package com.mattia.appvolume.testtone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.mattia.appvolume.AudioNames.TAG
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tono di prova a 440 Hz via AudioTrack, per testare lo spike senza app esterne.
 * In modalità "ricrea" chiude e ricrea il player ogni [RECREATE_MS] ms: serve a
 * sentire se c'è un salto di volume prima che il servizio applichi il livello.
 */
class TestTonePlayer {

    @Volatile private var running = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    fun start(recreate: Boolean) {
        stop()
        running = true
        worker = thread(name = "AppVolTestTone") { loop(recreate) }
    }

    fun stop() {
        running = false
        worker?.join(1000)
        worker = null
    }

    private fun loop(recreate: Boolean) {
        val buffer = sineBuffer()
        var trackIndex = 0
        while (running) {
            val track = createTrack()
            trackIndex++
            val createdAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "Tono di prova: creato AudioTrack #$trackIndex (sessione ${track.audioSessionId})")
            track.play()
            while (running && (!recreate || SystemClock.elapsedRealtime() - createdAt < RECREATE_MS)) {
                track.write(buffer, 0, buffer.size)
            }
            track.stop()
            track.release()
            Log.i(TAG, "Tono di prova: rilasciato AudioTrack #$trackIndex")
        }
    }

    private fun createTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    /** 100 ms di sinusoide: 44 periodi esatti a 440 Hz, quindi si ripete senza click. */
    private fun sineBuffer(): ShortArray {
        val samples = SAMPLE_RATE / 10
        return ShortArray(samples) { i ->
            (sin(2 * PI * FREQUENCY * i / SAMPLE_RATE) * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FREQUENCY = 440.0
        const val AMPLITUDE = 0.3
        const val RECREATE_MS = 2_000L
    }
}
