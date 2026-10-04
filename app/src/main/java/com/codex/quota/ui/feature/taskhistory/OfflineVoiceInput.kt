package com.codex.quota.ui.feature.taskhistory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pinned SenseVoice INT8 model. PCM stays in memory; inference is entirely on the phone. */
internal class OfflineVoiceInput(private val context: Context) {
    companion object {
        private const val REVISION = "2365baeacb507f821a0c8120fcee3d484dba7a07"
        private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/$REVISION/"
        private val files = listOf(
            Triple("model.int8.onnx", 239233841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            Triple("tokens.txt", 315894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")
        )
        fun clearModels(context: Context) {
            val directory = File(context.noBackupFilesDir, "voice-models").canonicalFile
            check(directory.parentFile == context.noBackupFilesDir.canonicalFile)
            if (directory.exists()) directory.deleteRecursively()
        }
    }
    private val root = File(context.noBackupFilesDir, "voice-models")
    private val directory = File(root, "sensevoice-int8-$REVISION")
    private val stop = AtomicBoolean(false)
    fun ready() = File(directory, ".ready").isFile && files.all { (name, size, _) -> File(directory, name).length() == size }

    suspend fun download(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(root, "sensevoice.partial")
        check(part.canonicalFile.parentFile == root.canonicalFile)
        if (part.exists()) part.deleteRecursively()
        check(part.mkdirs())
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false).build()
        var completed = 0L
        val total = files.sumOf { it.second }
        try {
            for ((name, size, checksum) in files) {
                ensureActive()
                val call = http.newCall(Request.Builder().url(BASE + name).build())
                val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful && response.request.url.isHttps)
                        val body = requireNotNull(response.body)
                        check(body.contentLength() == -1L || body.contentLength() == size)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L; var previous = -1
                        body.byteStream().use { input -> File(part, name).outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                count += n; check(count <= size)
                                digest.update(buffer, 0, n); out.write(buffer, 0, n)
                                val percent = ((completed + count) * 100 / total).toInt()
                                if (percent != previous) { previous = percent; progress((completed + count).toFloat() / total) }
                            }
                        } }
                        check(count == size && digest.digest().joinToString("") { "%02x".format(it) } == checksum)
                    }
                } finally { cancellation.dispose(); call.cancel() }
                completed += size
            }
            ensureActive()
            if (directory.exists()) directory.deleteRecursively()
            check(part.renameTo(directory))
            File(directory, ".ready").writeText(REVISION)
            progress(1f)
        } finally { if (part.exists()) part.deleteRecursively() }
    }

    fun stop() { stop.set(true) }
    suspend fun recognize(onReady: () -> Unit, onProcessing: () -> Unit): String = withContext(Dispatchers.IO) {
        check(ready() && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        stop.set(false)
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = File(directory, "model.int8.onnx").absolutePath,
                language = "auto", useInverseTextNormalization = true),
            tokens = File(directory, "tokens.txt").absolutePath, numThreads = 2, debug = false, provider = "cpu"
        )))
        try {
            ensureActive()
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400)
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            val samples = FloatArray(16000 * 30)
            var count = 0; var audible = 0
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                ensureActive(); recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                withContext(Dispatchers.Main) { onReady() }
                val started = SystemClock.elapsedRealtime(); val buffer = ShortArray(3200)
                while (isActive && !stop.get() && count < samples.size && SystemClock.elapsedRealtime() - started < 30000) {
                    val n = recorder.read(buffer, 0, minOf(buffer.size, samples.size - count)); check(n > 0)
                    for (i in 0 until n) { val value = buffer[i] / 32768f; samples[count++] = value; if (kotlin.math.abs(value) > 0.01f) audible++ }
                }
            } finally {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder.release()
            }
            ensureActive()
            if (count < 1600 || audible < 320) return@withContext ""
            withContext(Dispatchers.Main) { onProcessing() }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples.copyOf(count), 16000)
                recognizer.decode(stream)
                ensureActive()
                recognizer.getResult(stream).text.trim()
            } finally { stream.release() }
        } finally { recognizer.release() }
    }
}
