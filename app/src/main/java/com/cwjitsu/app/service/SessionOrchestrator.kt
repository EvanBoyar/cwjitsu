package com.cwjitsu.app.service

import com.cwjitsu.app.audio.CwAudioEngine
import com.cwjitsu.app.audio.Schedule
import com.cwjitsu.app.audio.ScheduleBuilder
import com.cwjitsu.app.audio.ToneEvent
import com.cwjitsu.app.practice.ContentItem
import com.cwjitsu.app.practice.Morse
import com.cwjitsu.app.practice.PracticeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

/** Async content provider; called by the orchestrator each round. */
typealias ContentRegenerator = suspend (PracticeConfig) -> List<ContentItem>

/**
 * Drives a continuous practice session. The orchestrator loops forever, calling
 * the [ContentRegenerator] each round to get a fresh batch of [ContentItem]s, then
 * playing them. The user stops by calling [stop].
 *
 * Repetition semantics: with reps = 2 and items = [A, B], the user hears A, A,
 * then B, B. The answer (TTS) is spoken once after each item's reps are sent.
 */
class SessionOrchestrator(
    private val engine: CwAudioEngine,
    private val tts: TtsManager,
    private val scope: CoroutineScope,
) {

    enum class RunnerState { IDLE, RUNNING, FINISHED, STOPPED }

    private val _state = MutableStateFlow(RunnerState.IDLE)
    val runnerState: StateFlow<RunnerState> = _state

    private val _currentBatch = MutableStateFlow<List<ContentItem>>(emptyList())
    val currentBatch: StateFlow<List<ContentItem>> = _currentBatch

    private var job: Job? = null

    fun start(regenerator: ContentRegenerator, config: PracticeConfig) {
        stop()
        job = scope.launch(Dispatchers.Default) {
            _state.value = RunnerState.RUNNING
            try {
            while (isActive) {
                val items = regenerator(config)
                if (items.isEmpty()) {
                    // Nothing to play right now (e.g. all categories are
                    // deselected, or Call Signs is on with no countries).
                    // Clear any stale preview and wait for the user to
                    // enable content. The session stays "armed" in the
                    // RUNNING state and starts playing as soon as the
                    // regenerator returns a non-empty list.
                    _currentBatch.value = emptyList()
                    delay(500)
                    continue
                }
                _currentBatch.value = items
                playBatch(items, config)
            }
                _state.value = RunnerState.FINISHED
            } catch (e: CancellationException) {
                _state.value = RunnerState.STOPPED
                throw e
            }
        }
    }

    fun stop() {
        job?.cancel()
        engine.stop()
        tts.stop()
        _state.value = RunnerState.STOPPED
    }

    private suspend fun playBatch(items: List<ContentItem>, config: PracticeConfig) {
        val sampleRate = engine.sampleRate
        val builder = ScheduleBuilder(sampleRate)
        val reps = config.repetitions.coerceAtLeast(1)

        for (item in items) {
            val morse = item.morseOverride
                ?: item.text.uppercase()
                    .mapNotNull(Morse::codeFor)
                    .joinToString("")
            if (morse.isBlank()) continue

            val batchForRep = List(reps) { item }
            val schedule = builder.build(batchForRep, timesToRepeat = 1, config = config)
            engine.setSchedule(schedule, config)
            engine.play()
            waitForAudioToFinish(schedule.totalSamples)

            delay(config.postSendPauseMs)

            if (config.answerEnabled) {
                delay(config.answerDelayMs)
                val answer = item.spokenAnswer ?: item.text
                awaitTts(answer, item.text)

                // Optional one-shot replay of the same code, never
                // counted as a repetition. Useful for catching a code
                // the listener missed on the first listening pass.
                if (config.replayAfterAnswer) {
                    delay(config.answerDelayMs)
                    val replay = builder.build(listOf(item), timesToRepeat = 1, config = config)
                    engine.setSchedule(replay, config)
                    engine.play()
                    waitForAudioToFinish(replay.totalSamples)
                }
            }
        }

        // Courtesy tone: short tone played once after the whole batch
        // answers have finished, like a real repeater's "dit" at the
        // end of a sequence. Disabled by config. Always uses the
        // currently tuned frequency so it matches the practice tone.
        if (config.courtesyToneEnabled) {
            playCourtesyTone(config)
        }
    }

    private suspend fun playCourtesyTone(config: PracticeConfig) {
        // Two short tones back-to-back with NO gap between them, like the
        // familiar repeater "courtesy" pip that real repeaters emit at
        // the end of a sequence. Fixed at 50 ms per tone and 947 / 1187
        // Hz — short enough to feel like an unobtrusive end-of-batch
        // marker, and the two-tone shape makes it unmistakable so the
        // user can hear the boundary without a spoken announcement.
        val toneSamples = (50L * engine.sampleRate / 1000)
            .toInt()
            .coerceAtLeast(1)
        // Pick the midpoint of the practice-tone amplitude range so the
        // courtesy tone sits at roughly the same level as the rest of
        // the session, not louder. Bypassing Master/volume-variation
        // would otherwise make the courtesy tone pop out awkwardly.
        val courtesyAmp = if (config.volumeVariationEnabled) 0.92f else 1.0f
        val firstEvent = ToneEvent(
            startSample = 0,
            endSample = toneSamples,
            freqHz = 947,
            amplitude = courtesyAmp,
            label = "courtesy1",
        )
        // Second event starts exactly where the first one ends — zero
        // gap. CwAudioEngine mixes overlapping events additively, so
        // we explicitly keep these tones strictly sequential, not
        // overlapping; if they overlapped, it would just sum both
        // frequencies into a buzz instead of a clean two-tone pip.
        val secondEvent = ToneEvent(
            startSample = toneSamples,
            endSample = toneSamples * 2,
            freqHz = 1187,
            amplitude = courtesyAmp,
            label = "courtesy2",
        )
        val courtesy = Schedule(
            events = listOf(firstEvent, secondEvent),
            totalSamples = toneSamples * 2,
            sampleRate = engine.sampleRate,
        )
        engine.setSchedule(courtesy, config)
        engine.play()
        waitForAudioToFinish(courtesy.totalSamples)
        // Brief settling pause so the next batch's first element doesn't
        // butt up against the courtesy tone's envelope tail.
        delay(80)
    }

    private suspend fun waitForAudioToFinish(totalSamples: Int) {
        val budgetSamples = totalSamples + engine.sampleRate
        while (engine.state.value == CwAudioEngine.State.PLAYING ||
            engine.state.value == CwAudioEngine.State.PAUSED
        ) {
            if (engine.elapsedSamples() >= budgetSamples) return
            yield()
        }
    }

    private suspend fun awaitTts(text: String, utteranceId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            tts.speak(text, utteranceId) { ok ->
                if (cont.isActive) cont.resume(ok)
            }
        }
}
