package com.offlinemaps.app.speech

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Speech used to build a `TextToSpeech` for every navigation session and shut it down again on stop,
 * which paid the engine's bind-and-initialise cost on every start (OPTIMIZATION.md F-01). These
 * tests pin the replacement contract: one engine per [SpeechGuidance] instance, reused across
 * sessions, released only by `shutdown`, and never rebuilt after a failed initialisation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SpeechGuidanceTest {

    private lateinit var context: Context
    private var factoryCalls = 0
    private val engines = mutableListOf<FakeEngine>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factoryCalls = 0
        engines.clear()
    }

    private fun guidance(
        succeed: Boolean = true,
        reportDuringConstruction: Boolean = false,
        throwsWhenConfiguring: Boolean = false,
        throwsOnSpeak: Boolean = false,
    ): SpeechGuidance =
        SpeechGuidance(context) { _, onInit ->
            factoryCalls++
            FakeEngine(onInit, succeed, reportDuringConstruction, throwsWhenConfiguring, throwsOnSpeak)
                .also { engines += it }
        }

    @Test
    fun oneEngineIsBuiltAndReusedForLaterSessions() {
        val guidance = guidance()
        val answers = mutableListOf<Pair<Boolean, String?>>()

        guidance.initialise { available, message -> answers += available to message }
        engines.single().completeInit()
        guidance.initialise { available, message -> answers += available to message }

        assertThat(factoryCalls).isEqualTo(1)
        assertThat(answers).hasSize(2)
        assertThat(answers.map { it.first }).containsExactly(true, true)
        assertThat(guidance.isReady).isTrue()
    }

    @Test
    fun shutdownReleasesTheEngineAndTheNextSessionBuildsANewOne() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.shutdown()
        assertThat(engines.single().shutdowns).isEqualTo(1)
        assertThat(engines.single().stops).isEqualTo(1)
        assertThat(guidance.isReady).isFalse()

        guidance.initialise { _, _ -> }
        engines.last().completeInit()

        assertThat(factoryCalls).isEqualTo(2)
        assertThat(guidance.isReady).isTrue()
    }

    @Test
    fun stoppingASessionKeepsTheEngineReady() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.stop()

        assertThat(engines.single().stops).isEqualTo(1)
        assertThat(engines.single().shutdowns).isEqualTo(0)
        assertThat(guidance.isReady).isTrue()
    }

    @Test
    fun everyCallerWaitingOnTheSameInitIsAnswered() {
        val guidance = guidance()
        val answers = mutableListOf<Boolean>()

        guidance.initialise { available, _ -> answers += available }
        guidance.initialise { available, _ -> answers += available }
        assertThat(answers).isEmpty()

        engines.single().completeInit()

        assertThat(answers).containsExactly(true, true).inOrder()
        assertThat(factoryCalls).isEqualTo(1)
    }

    @Test
    fun anEngineThatReportsWhileStillBeingConstructedIsNotLost() {
        val guidance = guidance(reportDuringConstruction = true)

        guidance.initialise { _, _ -> }

        assertThat(factoryCalls).isEqualTo(1)
        assertThat(guidance.isReady).isTrue()
        guidance.speak("Turn left")
        assertThat(engines.single().spoken).containsExactly("Turn left")
    }

    @Test
    fun aFailedEngineIsReportedAndNeverRebuilt() {
        val guidance = guidance(succeed = false)
        val answers = mutableListOf<Boolean>()

        guidance.initialise { available, _ -> answers += available }
        engines.single().completeInit()
        guidance.initialise { available, _ -> answers += available }

        assertThat(answers).containsExactly(false, false).inOrder()
        assertThat(factoryCalls).isEqualTo(1)
        assertThat(guidance.isReady).isFalse()

        guidance.speak("Turn left")
        assertThat(engines.single().spoken).isEmpty()
    }

    @Test
    fun aLateReportFromAReleasedEngineIsIgnored() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        val released = engines.single()

        guidance.shutdown()
        released.completeInit()

        assertThat(guidance.isReady).isFalse()
    }

    @Test
    fun theSameInstructionIsNotRepeatedImmediately() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.speak("Turn left")
        guidance.speak("Turn left")

        assertThat(engines.single().spoken).containsExactly("Turn left")
    }

    @Test
    fun mutingStopsTheUtteranceInFlight() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.setMuted(true)
        guidance.speak("Turn left")

        assertThat(engines.single().stops).isEqualTo(1)
        assertThat(engines.single().spoken).isEmpty()
    }

    @Test
    fun anInstructionFlushesWhateverIsBeingSaid() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.speak("In 600 metres, turn right onto Carrer Major")
        guidance.speak("In 250 metres, turn right onto Carrer Major")

        val engine = engines.single()
        assertThat(engine.spoken).hasSize(2)
        assertThat(engine.modes).containsExactly(TextToSpeech.QUEUE_FLUSH, TextToSpeech.QUEUE_FLUSH)
    }

    @Test
    fun aNoticeIsQueuedBehindTheInstructionInsteadOfCuttingIt() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.speak("In 250 metres, turn right onto Carrer Major")
        guidance.speak("Route recalculated.", SpeechGuidance.Priority.NOTICE)

        val engine = engines.single()
        assertThat(engine.spoken).containsExactly(
            "In 250 metres, turn right onto Carrer Major",
            "Route recalculated.",
        ).inOrder()
        assertThat(engine.modes).containsExactly(TextToSpeech.QUEUE_FLUSH, TextToSpeech.QUEUE_ADD).inOrder()
    }

    @Test
    fun anInstructionArrivingWhileANoticeWaitsStillFlushes() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.speak("Route recalculated.", SpeechGuidance.Priority.NOTICE)
        guidance.speak("In 80 metres, turn right onto Carrer Major")

        assertThat(engines.single().modes)
            .containsExactly(TextToSpeech.QUEUE_ADD, TextToSpeech.QUEUE_FLUSH).inOrder()
    }

    @Test
    fun queuedUtterancesGetTheirOwnIds() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.speak("In 250 metres, turn right onto Carrer Major")
        guidance.speak("Route recalculated.", SpeechGuidance.Priority.NOTICE)

        val ids = engines.single().ids
        assertThat(ids).hasSize(2)
        assertThat(ids.toSet()).hasSize(2)
    }

    @Test
    fun theRepeatWindowCoversNoticesToo() {
        val guidance = guidance()
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.speak("GPS signal restored. Continuing navigation.", SpeechGuidance.Priority.NOTICE)
        guidance.speak("GPS signal restored. Continuing navigation.", SpeechGuidance.Priority.NOTICE)

        assertThat(engines.single().spoken).hasSize(1)
    }

    @Test
    fun anEngineThatCannotBeBuiltIsReportedInsteadOfThrown() {
        var factoryCallsWithFailure = 0
        val guidance = SpeechGuidance(context) { _, _ ->
            factoryCallsWithFailure++
            throw IllegalStateException("no speech engine installed")
        }
        val answers = mutableListOf<Pair<Boolean, String?>>()

        guidance.initialise { available, message -> answers += available to message }

        assertThat(answers.map { it.first }).containsExactly(false)
        assertThat(answers.single().second).isNotEmpty()
        assertThat(guidance.isReady).isFalse()
        assertThat(guidance.availableVoices()).isEmpty()

        // Voice is optional: nothing the guidance loop does afterwards may throw.
        guidance.speak("Turn left")
        guidance.stop()
        guidance.shutdown()

        // shutdown releases a failed engine, so the next session is allowed to try again.
        guidance.initialise { available, _ -> answers += available to null }

        assertThat(answers.map { it.first }).containsExactly(false, false).inOrder()
        assertThat(factoryCallsWithFailure).isEqualTo(2)
    }

    @Test
    fun anEngineThatReportsSuccessButThrowsWhileBeingConfiguredIsUnavailable() {
        val guidance = guidance(throwsWhenConfiguring = true)
        val answers = mutableListOf<Boolean>()

        guidance.initialise { available, _ -> answers += available }
        engines.single().completeInit()

        assertThat(answers).containsExactly(false)
        assertThat(guidance.isReady).isFalse()

        guidance.speak("Turn left")
        assertThat(engines.single().spoken).isEmpty()
    }

    @Test
    fun anUtteranceTheEngineRefusesDoesNotEscapeTheGuidanceLoop() {
        val guidance = guidance(throwsOnSpeak = true)
        guidance.initialise { _, _ -> }
        engines.single().completeInit()

        guidance.speak("Turn left")

        assertThat(guidance.isReady).isTrue()
    }

    private class FakeEngine(
        private val onInit: (Int) -> Unit,
        private val succeed: Boolean,
        reportDuringConstruction: Boolean,
        private val throwsWhenConfiguring: Boolean = false,
        private val throwsOnSpeak: Boolean = false,
    ) : TtsEngine {

        val spoken = mutableListOf<String>()
        val modes = mutableListOf<Int>()
        val ids = mutableListOf<String>()
        var stops = 0
        var shutdowns = 0

        init {
            if (reportDuringConstruction) onInit(if (succeed) TextToSpeech.SUCCESS else TextToSpeech.ERROR)
        }

        fun completeInit() = onInit(if (succeed) TextToSpeech.SUCCESS else TextToSpeech.ERROR)

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
            if (throwsWhenConfiguring) throw IllegalStateException("engine is gone")
        }

        override fun voices(): List<SpeechGuidance.VoiceInfo> =
            listOf(SpeechGuidance.VoiceInfo("offline-en", "en-IN", true))

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String) {
            if (throwsOnSpeak) throw IllegalStateException("engine is gone")
            spoken += text
            modes += queueMode
            ids += utteranceId
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutdowns++
        }
    }
}
