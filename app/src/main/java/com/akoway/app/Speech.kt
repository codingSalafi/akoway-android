package com.akoway.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * The one speech engine in the process, shared by everything that listens so the 1.3 GB
 * model is only ever loaded once. All model work runs on a single background thread.
 */
object Speech {

    private const val TAG = "Akoway"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: BarkEngine? = null   // only touched on the worker thread
    private var users = 0                    // main thread only

    /** Called by the tab service and the keyboard when they start; loads the model in the background. */
    fun acquire(context: Context) {
        users++
        warmUp(context)
    }

    /** Called when they stop; the model is freed once neither is running. */
    fun release() {
        if (--users <= 0) { users = 0; free() }
    }

    fun warmUp(context: Context) {
        val app = context.applicationContext
        worker.execute { load(app) }
    }

    /** Frees the model's memory; it reloads in about 3 s on next use. */
    fun free() = worker.execute { engine?.close(); engine = null }

    /** Turns [audio] into text in the background; [done] runs on the main thread, with null on failure. */
    fun transcribe(context: Context, audio: FloatArray, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val text = try {
                load(app)!!.transcribe(audio)
            } catch (e: Throwable) {
                Log.e(TAG, "Transcription failed", e)
                null
            }
            main.post { done(text) }
        }
    }

    private fun load(context: Context): BarkEngine? {
        if (engine == null && SpeechModel.isReady(context)) {
            val start = System.nanoTime()
            engine = BarkEngine(SpeechModel.folder(context)).also { it.transcribe(FloatArray(8000)) }  // warm-up
            Log.i(TAG, "Model loaded in ${(System.nanoTime() - start) / 1_000_000} ms")
        }
        return engine
    }
}
