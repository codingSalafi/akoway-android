package com.akoway.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speed + accuracy test for Bark 3.0 Clean on this phone. Copy in with adb (see README):
 *   <external files>/bark-3.0-clean/   the model files
 *   <external files>/clips/          16 kHz mono .wav test clips
 *   <external files>/clips/reference.tsv  name<TAB>text from the Windows app
 * Writes results.tsv and summary.txt next to them, and logs to logcat tag "AkowayBench".
 * Optional extras: --ei threads N, --ei limit N (first N clips; long runs heat the phone and slow it down),
 *   --es model FOLDER (another model folder next to bark-3.0-clean, to compare variants)
 */
class BenchActivity : Activity() {

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { setPadding(32, 48, 32, 32); textSize = 13f; setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(log) })

        val threads = intent.getIntExtra("threads", 0)
        val limit = intent.getIntExtra("limit", Int.MAX_VALUE)
        val model = intent.getStringExtra("model") ?: "bark-3.0-clean"
        Thread { runBench(threads, limit, model) }.start()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        runOnUiThread { log.append(line + "\n") }
    }

    private fun runBench(threads: Int, limit: Int, model: String) {
        try {
            val root = getExternalFilesDir(null)!!
            val modelDir = File(root, model)
            val clipsDir = File(root, "clips")
            say("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Runtime.getRuntime().availableProcessors()} cores, threads=${if (threads > 0) threads else "default"}, model $model")

            var start = System.nanoTime()
            BarkEngine(modelDir, threads).use { engine ->
                say("Model loaded in %.1f s, memory %d MB".format(secondsSince(start), pssMb()))

                engine.transcribe(FloatArray(8000)) // warm-up, as on Windows

                val reference = File(clipsDir, "reference.tsv").takeIf { it.exists() }?.readLines()
                    ?.filter { '\t' in it }?.associate { it.substringBefore('\t') to it.substringAfter('\t') } ?: emptyMap()

                val clips = clipsDir.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }.take(limit)
                var audioSec = 0.0
                var procSec = 0.0
                var same = 0
                val lines = ArrayList<String>()
                for (clip in clips) {
                    val samples = readWav(clip)
                    start = System.nanoTime()
                    val text = engine.transcribe(samples)
                    val took = secondsSince(start)
                    val seconds = samples.size / 16000.0
                    procSec += took
                    audioSec += seconds
                    val match = reference[clip.name] == text
                    if (match) same++
                    lines.add("${clip.name}\t$text")
                    say("%s %.1fs audio in %.2fs [%s]%s".format(clip.name, seconds, took, engine.lastTimings, if (match) "" else "  DIFFERS: $text"))
                }
                File(root, "results.tsv").writeText(lines.joinToString("\n", postfix = "\n"))

                val summary = "%d clips, %.1fs audio in %.1fs, RTF %.3f (%.1fs per 10s of speech), same as Windows: %d/%d, memory %d MB"
                    .format(clips.size, audioSec, procSec, procSec / audioSec, 10 * procSec / audioSec, same, clips.count { it.name in reference }, pssMb())
                File(root, "summary.txt").writeText(summary + "\n")
                say("DONE " + summary)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Bench failed", e)
            say("FAILED: $e")
        }
    }

    private fun secondsSince(start: Long) = (System.nanoTime() - start) / 1e9

    private fun pssMb(): Long = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024L

    companion object {
        private const val TAG = "AkowayBench"

        /** 16-bit PCM wav -> floats in -1..1 (same scaling as NAudio on Windows). */
        fun readWav(file: File): FloatArray {
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            while (pos + 8 <= bytes.limit()) {
                val id = String(ByteArray(4) { bytes.get(pos + it) }, Charsets.US_ASCII)
                val size = bytes.getInt(pos + 4)
                if (id == "data") {
                    val n = minOf(size, bytes.limit() - pos - 8) / 2
                    return FloatArray(n) { bytes.getShort(pos + 8 + it * 2) / 32768f }
                }
                pos += 8 + size + (size and 1)
            }
            error("No audio data in ${file.name}")
        }
    }
}
