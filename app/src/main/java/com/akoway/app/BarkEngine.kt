package com.akoway.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Offline speech-to-text with Underdog Bark 3.0 Clean (a Parakeet TDT 0.6B fine-tune),
 * run through ONNX Runtime. A port of the Windows app's Core/BarkEngine.cs; the model
 * files are the same ones (tools/bark-onnx/export_bark_onnx.py in the Akoway repo):
 *
 *   preprocessor.onnx  waveform -> normalized 128-bin log-mel features
 *   encoder.int8.onnx  features -> acoustic frames
 *   decoder.onnx       LSTM prediction network (one token per call)
 *   joiner.onnx        frame + prediction -> token logits + duration logits
 *   tokens.txt         SentencePiece vocabulary, blank last
 */
class BarkEngine(modelDir: File, threads: Int = 0, private val leadInSeconds: Double = 0.0) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val preprocessor: OrtSession
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val joiner: OrtSession
    private val tokens: List<String>
    private val blankId: Int
    private val decoderLayers: Int
    private val decoderHidden: Int

    init {
        val options = OrtSession.SessionOptions().apply {
            if (threads > 0) setIntraOpNumThreads(threads)
        }
        fun open(name: String) = env.createSession(File(modelDir, name).path, options)
        preprocessor = open("preprocessor.onnx")
        encoder = open("encoder.int8.onnx")
        decoder = open("decoder.onnx")
        joiner = open("joiner.onnx")

        tokens = File(modelDir, "tokens.txt").readLines(Charsets.UTF_8)
            .filter { it.isNotEmpty() }
            .map { it.substring(0, it.lastIndexOf(' ')) }
        blankId = tokens.size - 1

        val stateShape = (decoder.inputInfo.getValue("states_h").info as TensorInfo).shape // (layers, 1, hidden)
        decoderLayers = stateShape[0].toInt()
        decoderHidden = stateShape[2].toInt()
    }

    /** Transcribe 16 kHz mono audio in the range -1..1. */
    fun transcribe(samples: FloatArray): String {
        val leadIn = (leadInSeconds * SAMPLE_RATE).toInt()
        val wave = FloatArray(leadIn + samples.size)
        samples.copyInto(wave, leadIn)

        // 1. Waveform -> log-mel features, then 2. features -> acoustic frames, shape (1, hidden, frames)
        val hidden: Int
        val frameCount: Int
        val frames: FloatArray
        tensor(wave, longArrayOf(1, wave.size.toLong())).use { waveforms ->
            tensor(longArrayOf(wave.size.toLong()), longArrayOf(1)).use { lengths ->
                preprocessor.run(mapOf("waveforms" to waveforms, "lengths" to lengths)).use { features ->
                    encoder.run(
                        mapOf(
                            "features" to features.get("features").get() as OnnxTensor,
                            "feature_lengths" to features.get("feature_lengths").get() as OnnxTensor
                        )
                    ).use { encoded ->
                        val out = encoded.get("encoded").get() as OnnxTensor
                        hidden = (out.info as TensorInfo).shape[1].toInt()
                        val total = (out.info as TensorInfo).shape[2].toInt()
                        frameCount = ((encoded.get("encoded_lengths").get() as OnnxTensor).longBuffer.get(0)).toInt()

                        // Re-lay out as one contiguous vector per frame
                        val data = out.floatBuffer
                        frames = FloatArray(frameCount * hidden)
                        for (d in 0 until hidden)
                            for (t in 0 until frameCount)
                                frames[t * hidden + d] = data.get(d * total + t)
                    }
                }
            }
        }

        // 3. Greedy TDT decoding: each step predicts a token and how many frames to skip
        val state = DecoderState(FloatArray(decoderLayers * decoderHidden), FloatArray(decoderLayers * decoderHidden))
        var prediction = runDecoder(blankId, state)

        val ids = ArrayList<Int>()
        var frame = 0
        var emitted = 0
        while (frame < frameCount) {
            val logits = runJoiner(frames, frame * hidden, hidden, prediction)
            val token = argMax(logits, 0, blankId + 1)
            var skip = argMax(logits, blankId + 1, logits.size - blankId - 1)

            if (token != blankId) {
                ids.add(token)
                prediction = runDecoder(token, state)
                emitted++
            }

            if (skip == 0 && (token == blankId || emitted >= MAX_SYMBOLS_PER_FRAME)) skip = 1
            if (skip > 0) emitted = 0
            frame += skip
        }

        return decodeTokens(ids)
    }

    private class DecoderState(var h: FloatArray, var c: FloatArray)

    private fun runDecoder(token: Int, state: DecoderState): FloatArray {
        val stateShape = longArrayOf(decoderLayers.toLong(), 1, decoderHidden.toLong())
        tensor(longArrayOf(token.toLong()), longArrayOf(1, 1)).use { targets ->
            tensor(state.h, stateShape).use { h ->
                tensor(state.c, stateShape).use { c ->
                    decoder.run(mapOf("targets" to targets, "states_h" to h, "states_c" to c)).use { out ->
                        state.h = floats(out.get("next_states_h").get() as OnnxTensor)
                        state.c = floats(out.get("next_states_c").get() as OnnxTensor)
                        return floats(out.get("decoded").get() as OnnxTensor)
                    }
                }
            }
        }
    }

    private fun runJoiner(frames: FloatArray, offset: Int, hidden: Int, prediction: FloatArray): FloatArray {
        val frame = FloatBuffer.wrap(frames, offset, hidden).slice()
        OnnxTensor.createTensor(env, frame, longArrayOf(1, hidden.toLong(), 1)).use { enc ->
            tensor(prediction, longArrayOf(1, prediction.size.toLong(), 1)).use { dec ->
                joiner.run(mapOf("encoder_outputs" to enc, "decoder_outputs" to dec)).use { out ->
                    return floats(out.get(0) as OnnxTensor)
                }
            }
        }
    }

    private fun decodeTokens(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val piece = tokens[id]
            // Skip control tokens such as <unk> and <|endoftext|>
            if (piece.startsWith('<') && piece.endsWith('>')) continue
            sb.append(piece)
        }
        // SentencePiece marks word starts with U+2581
        return sb.toString().replace('▁', ' ').trim()
    }

    private fun tensor(data: FloatArray, shape: LongArray) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    private fun tensor(data: LongArray, shape: LongArray) = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun floats(t: OnnxTensor): FloatArray {
        val buf = t.floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    override fun close() {
        preprocessor.close()
        encoder.close()
        decoder.close()
        joiner.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val MAX_SYMBOLS_PER_FRAME = 10

        private fun argMax(values: FloatArray, start: Int, count: Int): Int {
            var best = start
            for (i in start + 1 until start + count)
                if (values[i] > values[best]) best = i
            return best - start
        }
    }
}
