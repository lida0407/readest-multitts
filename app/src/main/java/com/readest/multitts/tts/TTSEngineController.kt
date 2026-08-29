package com.readest.multitts.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.readest.multitts.model.TTSVoiceInfo
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface TTSPlaybackListener {
    fun onUtteranceStart(utteranceId: String)
    fun onUtteranceDone(utteranceId: String)
    fun onUtteranceError(utteranceId: String, errorMessage: String)
    fun onRangeStart(utteranceId: String, start: Int, end: Int)
}

class TTSEngineController(private val context: Context) {

    companion object {
        private const val TAG = "TTSEngineController"
    }

    private var textToSpeech: TextToSpeech? = null
    var isInitialized = AtomicBoolean(false)
        private set
    var currentEnginePackage: String? = null
        private set

    var currentRate: Float = 1.0f
    var currentPitch: Float = 1.0f
    var currentVoiceId: String? = null

    var listener: TTSPlaybackListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileSynthesisCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()

    fun initEngine(enginePackage: String?, onReady: (Boolean) -> Unit) {
        shutdown()
        currentEnginePackage = enginePackage

        textToSpeech = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized.set(true)
                setupProgressListener()
                textToSpeech?.setSpeechRate(currentRate)
                textToSpeech?.setPitch(currentPitch)
                mainHandler.post { onReady(true) }
            } else {
                Log.e(TAG, "Failed to initialize TTS engine: $enginePackage with status $status")
                isInitialized.set(false)
                mainHandler.post { onReady(false) }
            }
        }, enginePackage)
    }

    private fun setupProgressListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let { id ->
                    mainHandler.post { listener?.onUtteranceStart(id) }
                }
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id ->
                    val callback = fileSynthesisCallbacks.remove(id)
                    if (callback != null) {
                        mainHandler.post { callback(true) }
                    } else {
                        mainHandler.post { listener?.onUtteranceDone(id) }
                    }
                }
            }

            @Deprecated("deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { id ->
                    val callback = fileSynthesisCallbacks.remove(id)
                    if (callback != null) {
                        mainHandler.post { callback(false) }
                    } else {
                        mainHandler.post { listener?.onUtteranceError(id, "Synthesis error") }
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { id ->
                    val callback = fileSynthesisCallbacks.remove(id)
                    if (callback != null) {
                        mainHandler.post { callback(false) }
                    } else {
                        mainHandler.post { listener?.onUtteranceError(id, "Synthesis error code: $errorCode") }
                    }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                utteranceId?.let { id ->
                    mainHandler.post { listener?.onRangeStart(id, start, end) }
                }
            }
        })
    }

    fun setSpeechRate(rate: Float) {
        currentRate = rate
        textToSpeech?.setSpeechRate(rate)
    }

    fun setSpeechPitch(pitch: Float) {
        currentPitch = pitch
        textToSpeech?.setPitch(pitch)
    }

    /**
     * True when the engine actually has this voice; false means we could only match
     * its language, so the audible voice may differ from the label.
     */
    var currentVoiceResolved: Boolean = false
        private set

    fun setVoice(voiceId: String): Boolean {
        currentVoiceId = voiceId
        val voices = textToSpeech?.voices ?: return false
        val target = voices.find { it.name == voiceId }
            ?: voices.find { it.name.equals(voiceId, ignoreCase = true) }
            ?: voices.find { it.name.contains(voiceId, ignoreCase = true) }
        currentVoiceResolved = target != null
        return if (target != null) {
            textToSpeech?.voice = target
            true
        } else {
            // Set language if Chinese or English
            if (voiceId.contains("zh") || voiceId.contains("Xiao") || voiceId.contains("Yun") || voiceId.contains("huidi") || voiceId.contains("yezi")) {
                textToSpeech?.language = Locale.SIMPLIFIED_CHINESE
            } else if (voiceId.contains("en") || voiceId.contains("Kitten")) {
                textToSpeech?.language = Locale.US
            }
            true
        }
    }

    fun getVoices(): List<TTSVoiceInfo> {
        val list = mutableListOf<TTSVoiceInfo>()
        val isMulti = currentEnginePackage != null && MultiTTSManager.KNOWN_MULTITTS_PACKAGES.contains(currentEnginePackage)

        if (isMulti) {
            // Load rich MultiTTS voices
            list.addAll(MultiTTSVoiceParser.parseMultiTTSVoices())
        }

        try {
            val systemVoices = textToSpeech?.voices ?: emptySet()
            for (v in systemVoices) {
                if (v.name != "NOT_SET" && list.none { it.id == v.name }) {
                    list.add(
                        TTSVoiceInfo(
                            id = v.name,
                            name = "${v.name} (${v.locale.displayLanguage})",
                            locale = v.locale,
                            enginePackage = currentEnginePackage ?: "default",
                            isMultiTts = isMulti
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isEmpty()) {
            list.add(TTSVoiceInfo("default", "Default System Voice", Locale.getDefault(), "default", false))
        }

        return list
    }

    fun synthesizeToFile(text: String, outputFile: File, utteranceId: String, callback: (Boolean) -> Unit) {
        if (!isInitialized.get() || textToSpeech == null) {
            callback(false)
            return
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }

        fileSynthesisCallbacks[utteranceId] = callback
        // Always synthesize cache files at neutral rate/pitch; playback speed is applied
        // by MediaPlayer.playbackParams, so one cache serves every speed setting.
        textToSpeech?.setSpeechRate(1.0f)
        textToSpeech?.setPitch(1.0f)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val result = textToSpeech?.synthesizeToFile(text, params, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            fileSynthesisCallbacks.remove(utteranceId)
            callback(false)
        }
    }

    fun speak(text: String, utteranceId: String) {
        if (!isInitialized.get() || textToSpeech == null) return
        textToSpeech?.setSpeechRate(currentRate)
        textToSpeech?.setPitch(currentPitch)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        textToSpeech?.stop()
        fileSynthesisCallbacks.clear()
    }

    fun shutdown() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        textToSpeech = null
        isInitialized.set(false)
        fileSynthesisCallbacks.clear()
    }
}
