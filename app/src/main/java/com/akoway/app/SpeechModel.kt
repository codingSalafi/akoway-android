package com.akoway.app

import android.content.Context
import org.brotli.dec.BrotliInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Bark 3.0 Clean, the one model on Android: where it lives on the phone and how to download it.
 *
 * The files are the "fast/" set on Hugging Face: the same weights the Windows app uses, with the
 * encoder's MatMulNBits layers set to accuracy_level 4 (8-bit activations), which is about three
 * times faster on a phone. Every file except the licence is Brotli-compressed.
 */
object SpeechModel {

    private const val REPO_URL = "https://huggingface.co/codingSalafi/akoway-bark-3.0-clean-onnx/resolve/main/fast/"
    private const val VERSION = "bark-3.0-clean-fast-1"  // written to the ready marker once all files check out

    class ModelFile(val name: String, val size: Long, val sha256: String, val brSize: Long? = null, val brSha256: String? = null) {
        val downloadName get() = if (brSize != null) "$name.br" else name
        val downloadSize get() = brSize ?: size
    }

    val FILES = listOf(
        ModelFile("preprocessor.onnx", 1_192_352, "b7e95a3d22e474366f1d79b1c9a20178d57c896d8f9d5c58b2f6bbf5f29ddda0",
            217_903, "34edece7371af6c9eafa7222b4cfdd29cc0811e64c1c22817033d467dee01cb1"),
        ModelFile("encoder.int8.onnx", 633_625_522, "35f5230b459fa79eaeaf844c5af2823732d75cff62ebf709c6a6a33255e0aa97",
            ENCODER_BR_SIZE, ENCODER_BR_SHA256),
        ModelFile("decoder.onnx", 48_874_113, "0fec09f96f6a7a53e6d9c2dd0fd419d8916bcd814b9ae56113b09f5bcccc1043",
            11_408_295, "9bd893964c40c5f6caec6bcd28e00f2790371b7b16f8578cfcc7bc28a8a95f99"),
        ModelFile("joiner.onnx", 21_020_669, "4e82e839deae49c2cb60e275174e3d64d3bad5addc92a906bfb0d1ae1efb3765",
            7_617_892, "39d3cacd5a2eaac6cb0ed24b6ed0d11ee80e7282088774db698fa1e22f5b291a"),
        ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            35_787, "28b1bb53eff2182f80ded65d231ea8bd960e75b92fc154a489eb3865ad8a1d9e"),
        ModelFile("LICENSE-NOTICE.txt", 897, "240be8df9f067be93af0e654cc078e447fbd1861490df6226cd06d55677863b8"),
    )

    // Filled in once the compressed encoder is built (bark-clean-upload-fast-manifest.json)
    private const val ENCODER_BR_SIZE = 0L
    private const val ENCODER_BR_SHA256 = ""

    val downloadBytes get() = FILES.sumOf { it.downloadSize }

    fun folder(context: Context) = File(context.getExternalFilesDir(null), "models/bark-3.0-clean")

    private fun marker(context: Context) = File(folder(context), "ready")

    fun isReady(context: Context) = marker(context).let { it.exists() && it.readText().trim() == VERSION }

    /**
     * Downloads and unpacks the model, resuming a partial download. [progress] gets 0..1.
     * Throws IOException with a readable message on failure; call again to resume.
     */
    fun download(context: Context, progress: (Float) -> Unit, cancelled: () -> Boolean = { false }) {
        val dir = folder(context).apply { mkdirs() }
        marker(context).delete()
        var done = 0L
        for (file in FILES) {
            val target = File(dir, file.name)
            if (target.length() == file.size && sha256(target) == file.sha256) {
                done += file.downloadSize; progress(done.toFloat() / downloadBytes); continue
            }
            val part = File(dir, file.downloadName + ".part")
            fetch(REPO_URL + file.downloadName, part, file.downloadSize) { got ->
                progress((done + got).toFloat() / downloadBytes)
                cancelled()
            }
            if (sha256(part) != (file.brSha256 ?: file.sha256)) {
                part.delete()
                throw IOException("${file.name} was damaged in transit. Tap Download to try again.")
            }
            if (file.brSize != null) {
                val tmp = File(dir, file.name + ".tmp")
                BrotliInputStream(part.inputStream().buffered(1 shl 16)).use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it, 1 shl 16) }
                }
                part.delete()
                if (tmp.length() != file.size) { tmp.delete(); throw IOException("Couldn't unpack ${file.name}.") }
                tmp.renameTo(target)
            } else {
                part.renameTo(target)
            }
            done += file.downloadSize
        }
        marker(context).writeText(VERSION)
    }

    /** Download [url] into [part], resuming from what is already there, with a few retries. */
    private fun fetch(url: String, part: File, size: Long, onProgress: (Long) -> Boolean) {
        var attempt = 0
        while (true) {
            if (part.length() >= size) return
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000   // no data this long counts as a dropped connection
                if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("Server said $code")
                if (code == 200) part.delete()  // server ignored the range: start over
                conn.inputStream.use { input -> append(input, part, onProgress) }
                if (part.length() < size) throw IOException("Connection closed early")
                return
            } catch (e: IOException) {
                if (e is CancelledException || ++attempt >= 6) throw e
                Thread.sleep(2_000L * attempt)
            }
        }
    }

    private fun append(input: InputStream, part: File, onProgress: (Long) -> Boolean) {
        FileOutputStream(part, true).use { out ->
            val buf = ByteArray(1 shl 16)
            var got = part.length()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                got += n
                if (onProgress(got)) throw CancelledException()
            }
        }
    }

    class CancelledException : IOException("Download cancelled.")

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
