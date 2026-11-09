package com.offlinemaps.app.speech

import android.content.Context
import android.os.Bundle
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
 *
 * The engine is created once per instance and reused by every navigation session; only [shutdown]
 * releases it (OPTIMIZATION.md F-01). Building a [TextToSpeech] binds a system service and takes
 * hundreds of milliseconds, so paying that on every session start was pure latency.
 *
 * The engine is built through [engineFactory] so the create-once and shutdown rules can be tested
 * without an Android device.
 */
class SpeechGuidance internal constructor(
    context: Context,
    private val engineFactory: (Context, (Int) -> Unit) -> TtsEngine = { ctx, onInit ->
        AndroidTtsEngine(TextToSpeech(ctx, onInit))
    },
) {

    private val appContext = context.applicationContext
    private val lock = Any()

    private var engine: TtsEngine? = null
    private var ready = false
    private var failed = false
    private var initMessage: String? = null
    private var initDelivered = false
    private val pending = mutableListOf<(available: Boolean, message: String?) -> Unit>()

    private var muted = false
    private var volume: Float = 1.0f
    private var languageTag: String? = null
    private var lastSpoken: String? = null
    private var lastSpokenAt = 0L

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = Unit
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = Unit
    }

    val isReady: Boolean get() = synchronized(lock) { ready }

    /**
     * Initialises the engine, or joins the initialisation already running. Every caller is answered
     * once the engine reports back; a call made after that answers immediately without touching the
     * engine again.
     *
     * Callbacks run on the thread that completed the initialisation (the engine's callback thread)
     * and the internal lock is held while they run, so they must not call back into this object.
     */
    fun initialise(onResult: (available: Boolean, message: String?) -> Unit) {
        var immediate: Pair<Boolean, String?>? = null
        synchronized(lock) {
            when {
                ready -> immediate = true to initMessage
                failed -> immediate = false to initMessage
                else -> {
                    pending += onResult
                    if (engine == null) createEngineLocked()
                }
            }
        }
        immediate?.let { onResult(it.first, it.second) }
    }

    private fun createEngineLocked() {
        val box = InitBox()
        val created = engineFactory(appContext) { status -> onInitCallback(box, status) }
        box.engine = created
        engine = created
        // An engine that reported before the factory returned to us is answered now.
        val status = box.status
        if (status != null && !box.delivered) {
            box.delivered = true
            deliverLocked(status)
        }
    }

    private fun onInitCallback(box: InitBox, status: Int) {
        synchronized(lock) {
            if (box.engine == null) {
                // Still inside the factory: remember the status and answer after the assignment.
                box.status = status
                return
            }
            // A late report from an engine that has since been shut down is dropped.
            if (box.delivered || engine !== box.engine) return
            box.delivered = true
            deliverLocked(status)
        }
    }

    private fun deliverLocked(status: Int) {
        if (initDelivered) return
        initDelivered = true
        val current = engine
        if (status == TextToSpeech.SUCCESS && current != null) {
            current.setOnUtteranceProgressListener(progressListener)
            ready = true
            failed = false
            initMessage = applyLanguage(current, languageTag)
        } else {
            ready = false
            failed = true
            initMessage = UNAVAILABLE
        }
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { it(ready, initMessage) }
    }

    /** Voices installed on the device. Offline voices are flagged so the UI can label them. */
    fun availableVoices(): List<VoiceInfo> = synchronized(lock) {
        val current = engine ?: return@synchronized emptyList<VoiceInfo>()
        current.voices()
    }

    fun setLanguage(tag: String?): String? = synchronized(lock) {
        languageTag = tag
        val current = engine ?: return@synchronized "Speech engine not ready"
        applyLanguage(current, tag)
    }

    private fun applyLanguage(current: TtsEngine, tag: String?): String? {
        val locale = tag?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() } ?: Locale.getDefault()
        val result = current.setLanguage(locale)
        return when {
            result == TextToSpeech.LANG_MISSING_DATA ->
                "Voice data for ${locale.displayName} is not installed. Install it in Android settings for offline speech."
            result == TextToSpeech.LANG_NOT_SUPPORTED ->
                "This device's speech engine does not support ${locale.displayName}."
            else -> null
        }
    }

    fun setMuted(value: Boolean) {
        synchronized(lock) {
            muted = value
            if (value) runCatching { engine?.stop() }
        }
    }

    fun setVolume(value: Float) {
        synchronized(lock) { volume = value.coerceIn(0f, 1f) }
    }

    fun speak(text: String) {
        synchronized(lock) {
            if (muted) return
            val current = engine ?: return
            if (!ready) return
            val now = System.currentTimeMillis()
            // Guard against repeating the same sentence in quick succession.
            if (text == lastSpoken && now - lastSpokenAt < REPEAT_WINDOW_MS) return
            lastSpoken = text
            lastSpokenAt = now
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
            }
            current.speak(text, TextToSpeech.QUEUE_FLUSH, params, "nav-$now")
        }
    }

    /** Silences the current utterance but keeps the engine alive for the next session. */
    fun stop() {
        synchronized(lock) { runCatching { engine?.stop() } }
    }

    /** Releases the engine. The next [initialise] builds a fresh one. */
    fun shutdown() {
        synchronized(lock) {
            runCatching {
                engine?.stop()
                engine?.shutdown()
            }
            engine = null
            ready = false
            failed = false
            initDelivered = false
            initMessage = null
            pending.clear()
            lastSpoken = null
            lastSpokenAt = 0L
        }
    }

    data class VoiceInfo(val name: String, val locale: String, val offline: Boolean)

    /** Remembers a status the engine reported while its own reference was still being built. */
    private class InitBox {
        var engine: TtsEngine? = null
        var status: Int? = null
        var delivered = false
    }

    private companion object {
        const val UNAVAILABLE = "Text-to-speech is not available on this device"
        const val REPEAT_WINDOW_MS = 6_000L
    }
}

/**
 * Thin seam over [TextToSpeech] so the create-once and shutdown rules can be unit-tested on the JVM.
 */
internal interface TtsEngine {
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener)
    fun voices(): List<SpeechGuidance.VoiceInfo>
    fun setLanguage(locale: Locale): Int
    fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String)
    fun stop()
    fun shutdown()
}

internal class AndroidTtsEngine(private val tts: TextToSpeech) : TtsEngine {

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        tts.setOnUtteranceProgressListener(listener)
    }

    override fun voices(): List<SpeechGuidance.VoiceInfo> = runCatching {
        tts.voices
            ?.map { voice: Voice ->
                SpeechGuidance.VoiceInfo(
                    voice.name,
                    voice.locale.toLanguageTag(),
                    !voice.isNetworkConnectionRequired,
                )
            }
            ?.sortedWith(compareBy({ it.locale }, { it.name }))
            ?: emptyList()
    }.getOrDefault(emptyList())

    override fun setLanguage(locale: Locale): Int = tts.setLanguage(locale)

    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String) {
        tts.speak(text, queueMode, params, utteranceId)
    }

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() {
        tts.shutdown()
    }
}
