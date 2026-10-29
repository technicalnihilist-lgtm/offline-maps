package com.offlinemaps.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Spoken turn-by-turn guidance via Android's [TextToSpeech] engine.
 *
 * Everything here works offline as long as the selected voice is an on-device voice - the app never
 * requires a network voice. If no engine or voice is available, navigation continues silently and
 * the UI says so instead of pretending speech is working.
 */
class SpeechGuidance(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var muted = false
    private var volume: Float = 1.0f
    private var languageTag: String? = null
    private var lastSpoken: String? = null
    private var lastSpokenAt = 0L

    val isReady: Boolean get() = ready

    fun initialise(onResult: (available: Boolean, message: String?) -> Unit) {
        if (tts != null) {
            onResult(ready, null)
            return
        }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine == null) {
                    onResult(false, "Speech engine unavailable")
                    return@TextToSpeech
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = Unit
                })
                ready = true
                onResult(true, applyLanguage(engine, languageTag))
            } else {
                ready = false
                onResult(false, "Text-to-speech is not available on this device")
            }
        }
    }

    /** Voices installed on the device. Offline voices are flagged so the UI can label them. */
    fun availableVoices(): List<VoiceInfo> {
        val engine = tts ?: return emptyList()
        return runCatching {
            engine.voices
                ?.map { voice: Voice ->
                    VoiceInfo(voice.name, voice.locale.toLanguageTag(), !voice.isNetworkConnectionRequired)
                }
                ?.sortedWith(compareBy({ it.locale }, { it.name }))
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun setLanguage(tag: String?): String? {
        languageTag = tag
        val engine = tts ?: return "Speech engine not ready"
        return applyLanguage(engine, tag)
    }

    private fun applyLanguage(engine: TextToSpeech, tag: String?): String? {
        val locale = tag?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() } ?: Locale.getDefault()
        val result = engine.setLanguage(locale)
        return when {
            result == TextToSpeech.LANG_MISSING_DATA ->
                "Voice data for ${locale.displayName} is not installed. Install it in Android settings for offline speech."
            result == TextToSpeech.LANG_NOT_SUPPORTED ->
                "This device's speech engine does not support ${locale.displayName}."
            else -> null
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (value) tts?.stop()
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    fun speak(text: String) {
        if (muted) return
        val engine = tts ?: return
        if (!ready) return
        val now = System.currentTimeMillis()
        // Guard against repeating the same sentence in quick succession.
        if (text == lastSpoken && now - lastSpokenAt < 6_000L) return
        lastSpoken = text
        lastSpokenAt = now
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "nav-$now")
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }

    data class VoiceInfo(val name: String, val locale: String, val offline: Boolean)
}
