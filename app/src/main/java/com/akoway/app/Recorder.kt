package com.akoway.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/** Records 16 kHz mono audio from the microphone until stopped. */
class Recorder {

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<ShortArray>()

    /** Throws SecurityException without microphone permission, IllegalStateException if the mic is busy. */
    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone isn't available.")
        }
        synchronized(chunks) { chunks.clear() }
        rec.startRecording()
        record = rec
        running = true
        reader = thread(name = "akoway-mic") {
            val buf = ShortArray(SAMPLE_RATE / 10)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(chunks) { chunks.add(buf.copyOf(n)) }
            }
        }
    }

    /** Stops and returns the audio as floats in -1..1. */
    fun stop(): FloatArray {
        running = false
        reader?.join(500)
        record?.run { stop(); release() }
        record = null
        val all = synchronized(chunks) { chunks.toList().also { chunks.clear() } }
        val out = FloatArray(all.sumOf { it.size })
        var i = 0
        for (chunk in all) for (s in chunk) out[i++] = s / 32768f
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
