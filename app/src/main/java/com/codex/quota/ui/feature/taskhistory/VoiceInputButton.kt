package com.codex.quota.ui.feature.taskhistory

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codex.quota.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** System dictation where supported; explicit public-model download enables offline Vosk fallback. */
@Composable
internal fun VoiceInputButton(enabled: Boolean, onResult: (String) -> Unit, onError: (Int) -> Unit, onListening: (Boolean) -> Unit, onStage: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resultCallback by rememberUpdatedState(onResult)
    val errorCallback by rememberUpdatedState(onError)
    val listeningCallback by rememberUpdatedState(onListening)
    val stageCallback by rememberUpdatedState(onStage)
    var listening by remember { mutableStateOf(false) }
    var systemSession by remember { mutableStateOf(false) }
    var offlineStarted by remember { mutableStateOf(false) }
    var offerOffline by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var downloadFailed by remember { mutableStateOf(false) }
    var offlineJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val offline = remember(context) { OfflineVoiceInput(context.applicationContext) }
    val recognizer = remember(context) { runCatching {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }.getOrNull() }
    LaunchedEffect(listening) {
        if (listening && systemSession) {
            kotlinx.coroutines.delay(20_000)
            systemSession = false; recognizer?.cancel(); listening = false; listeningCallback(false)
            errorCallback(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }
    }
    DisposableEffect(lifecycleOwner, recognizer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                systemSession = false; recognizer?.cancel(); offline.stop(); offlineJob?.cancel(); listening = false; listeningCallback(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (systemSession) { listening = true; listeningCallback(true) } }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {} // Still busy until the provider returns results/error.
            override fun onError(error: Int) {
                if (!systemSession) return
                systemSession = false; listening = false; listeningCallback(false); errorCallback(error)
                if (error in setOf(SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) offerOffline = true
            }
            override fun onResults(results: Bundle?) {
                if (!systemSession) return
                systemSession = false; listening = false; listeningCallback(false)
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(resultCallback)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { systemSession = false; recognizer?.cancel(); recognizer?.destroy(); offline.stop(); offlineJob?.cancel(); listeningCallback(false) }
    }
    fun start() {
        if (offline.ready()) {
            offlineStarted = false
            stageCallback(R.string.voice_preparing)
            listening = true; listeningCallback(true)
            offlineJob = scope.launch {
                try {
                    val text = offline.recognize(onReady = { offlineStarted = true; stageCallback(R.string.remote_listening); listeningCallback(true) },
                        onProcessing = { offlineStarted = false; stageCallback(R.string.voice_processing) })
                    if (text.isBlank()) errorCallback(SpeechRecognizer.ERROR_NO_MATCH) else resultCallback(text)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { errorCallback(SpeechRecognizer.ERROR_AUDIO) }
                finally { listening = false; listeningCallback(false); offlineJob = null }
            }
            return
        }
        if (recognizer == null) { offerOffline = true; return }
        val locale = context.resources.configuration.locales[0]
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (locale.language == "zh") "zh-CN" else locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try { systemSession = true; listening = true; listeningCallback(true); recognizer.startListening(intent) }
        catch (_: Exception) { systemSession = false; listening = false; listeningCallback(false); offerOffline = true }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) start() else errorCallback(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
    IconButton(enabled = enabled && !downloading, onClick = {
        if (listening) {
            if (systemSession) recognizer?.stopListening()
            else if (offlineStarted) offline.stop() else offlineJob?.cancel()
        }
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) { Icon(if (listening) Icons.Outlined.StopCircle else Icons.Outlined.MicNone,
        stringResource(if (listening) R.string.remote_voice_stop else R.string.remote_voice),
        tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    if (offerOffline) AlertDialog(onDismissRequest = { if (!downloading) offerOffline = false },
        title = { Text(stringResource(R.string.voice_offline_title)) },
        text = { androidx.compose.foundation.layout.Column {
            Text(stringResource(R.string.voice_offline_detail))
            if (downloading) LinearProgressIndicator(progress = { progress })
            if (downloadFailed) Text(stringResource(R.string.voice_download_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !downloading, onClick = {
            downloading = true; downloadFailed = false; progress = 0f
            offlineJob = scope.launch {
                try {
                    offline.download { value -> ContextCompat.getMainExecutor(context).execute { progress = value } }
                    offerOffline = false // Downloading never starts the microphone. Tap it again to dictate.
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { downloadFailed = true }
                finally { downloading = false; offlineJob = null }
            }
        }) { Text(stringResource(R.string.voice_download)) } },
        dismissButton = { TextButton(onClick = { offlineJob?.cancel(); downloading = false; offerOffline = false }) { Text(stringResource(R.string.action_cancel)) } })
}
