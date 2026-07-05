package com.cwjitsu.app.service

import android.util.Log
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
    companion object {
        private const val TAG = "CWJitsu/Orch"

        // The playback queue keeps already-played items so Previous can step
        // back into them. To keep an all-day session from growing the list
        // forever, once the play position is past HISTORY_TRIM_AT the head
        // is trimmed down to HISTORY_KEEP items behind the position.
        private const val HISTORY_TRIM_AT = 100
        private const val HISTORY_KEEP = 50
    }

    enum class RunnerState { IDLE, RUNNING, FINISHED, STOPPED }

    /**
     * Rolling "now playing" window: the item currently being sent
     * ([current]) and the one sent immediately before it ([previous]).
     * Advances one step each time a new item starts playing, spanning
     * batch boundaries, so the UI shows only these two at a time rather
     * than the whole upcoming batch (which would spoil the answers).
     */
    data class NowPlaying(
        val previous: ContentItem? = null,
        val current: ContentItem? = null,
    )

    private val _state = MutableStateFlow(RunnerState.IDLE)
    val runnerState: StateFlow<RunnerState> = _state

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying

    // Whether a RUNNING session is paused in place (via the media
    // notification, a Bluetooth/headset button, or the in-app control).
    // Distinct from STOPPED: a paused session keeps its position and
    // resumes where it left off; a stopped session is torn down.
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    // Set by [skip] / [previous] to abandon the current item and jump to the
    // next / previous one. Cleared by the playback loop once it advances.
    @Volatile
    private var skipRequested = false
    @Volatile
    private var previousRequested = false

    private var job: Job? = null

    /**
     * Start a session. [configFlow] is the live settings flow rather than a
     * one-shot snapshot: the loop re-reads it before every item, so settings
     * changes apply from the very next send, and a collector pushes each
     * emission straight into the engine so audio-level knobs (master volume,
     * noise) change in real time - no stop/start required.
     */
    fun start(regenerator: ContentRegenerator, configFlow: Flow<PracticeConfig>) {
        stop()
        Log.d(TAG, "start regenerator")
        _paused.value = false
        skipRequested = false
        previousRequested = false
        job = scope.launch(Dispatchers.Default) {
            _state.value = RunnerState.RUNNING
            // Fresh session starts with a blank now-playing window.
            _nowPlaying.value = NowPlaying()
            // Live-apply settings to the engine for the whole session.
            launch { configFlow.collect { engine.updateConfig(it) } }
            // The queue holds every item of the session (bounded by the
            // history trim) with an explicit play position, so Previous can
            // step back across batch boundaries and Next/normal advance is
            // just position + 1. The regenerator refills the tail whenever
            // the position runs off the end.
            val queue = mutableListOf<ContentItem>()
            var position = 0
            try {
            while (isActive) {
                // Fresh config every round so edits apply on the next send.
                val config = configFlow.first()
                if (position >= queue.size) {
                    val items = regenerator(config)
                    Log.d(TAG, "round items.size=${items.size}")
                    if (items.isEmpty()) {
                        // Nothing to play right now (e.g. all categories are
                        // deselected, or Call Signs is on with no countries).
                        // Clear any stale preview and wait for the user to
                        // enable content. The session stays "armed" in the
                        // RUNNING state and starts playing as soon as the
                        // regenerator returns a non-empty list.
                        _nowPlaying.value = NowPlaying()
                        delay(500)
                        continue
                    }
                    queue += items
                    if (position > HISTORY_TRIM_AT) {
                        val drop = position - HISTORY_KEEP
                        queue.subList(0, drop).clear()
                        position -= drop
                    }
                }
                playItem(
                    item = queue[position],
                    previousItem = queue.getOrNull(position - 1),
                    config = config,
                )
                if (previousRequested) {
                    previousRequested = false
                    skipRequested = false
                    // At the head of the (trimmed) history, Previous simply
                    // replays the current item.
                    position = (position - 1).coerceAtLeast(0)
                } else {
                    skipRequested = false
                    position++
                }
            }
                _state.value = RunnerState.FINISHED
            } catch (e: CancellationException) {
                _state.value = RunnerState.STOPPED
                Log.d(TAG, "start CANCELLED")
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "start FATAL uncaught", t)
                _state.value = RunnerState.STOPPED
            }
        }
    }

    fun stop() {
        job?.cancel()
        engine.stop()
        tts.stop()
        _paused.value = false
        skipRequested = false
        previousRequested = false
        _state.value = RunnerState.STOPPED
    }

    /**
     * Skip the item currently playing and move straight to the next one.
     * Aborts the in-flight tone and any spoken answer; the playback loop sees
     * [skipRequested] at its next checkpoint and advances. If the session was
     * paused, skipping also resumes it. No-op unless a session is RUNNING.
     */
    fun skip() {
        if (_state.value != RunnerState.RUNNING) return
        Log.d(TAG, "skip")
        skipRequested = true
        _paused.value = false
        // Abort just the current schedule (not the whole session), so the
        // engine's track stays warm for the next item. Setting the flag first
        // guarantees the loop observes the skip once waitForAudioToFinish
        // returns (abort() flips the engine out of PLAYING).
        engine.abort()
        tts.stop()
    }

    /**
     * Jump back to the item played before the current one (or replay the
     * current item when there is no earlier history). Aborts the in-flight
     * tone and any spoken answer the same way [skip] does; the playback loop
     * sees [previousRequested] and steps its queue position back. If the
     * session was paused, going back also resumes it. No-op unless a session
     * is RUNNING.
     */
    fun previous() {
        if (_state.value != RunnerState.RUNNING) return
        Log.d(TAG, "previous")
        previousRequested = true
        _paused.value = false
        engine.abort()
        tts.stop()
    }

    /**
     * Pause a running session in place. The currently sounding element is
     * frozen (the audio engine pauses its track) and any in-flight spoken
     * answer is cut, since the platform TTS engine cannot be paused. The
     * orchestrator loop parks at its next checkpoint and does not advance
     * until [resume]. No-op unless a session is RUNNING and not already
     * paused.
     */
    fun pause() {
        if (_state.value != RunnerState.RUNNING || _paused.value) return
        Log.d(TAG, "pause")
        _paused.value = true
        engine.pause()
        tts.stop()
    }

    /** Resume a paused session from where it stopped. No-op if not paused. */
    fun resume() {
        if (_state.value != RunnerState.RUNNING || !_paused.value) return
        Log.d(TAG, "resume")
        _paused.value = false
        engine.resume()
    }

    /**
     * Suspend while the session is paused. Every audio-producing step in
     * [playBatch] passes through here first so nothing sounds - and the
     * rolling now-playing window doesn't advance - while paused.
     */
    private suspend fun awaitResume() {
        while (_paused.value && coroutineContext.isActive) {
            delay(50)
        }
    }

    /**
     * Play one queue item end to end: code, optional spoken answer +
     * replay, and the courtesy tone. Returns early (without clearing the
     * flags - the caller's queue loop does that) when Skip or Previous
     * aborts the item mid-flight. [previousItem] is only used for the
     * rolling now-playing window.
     */
    private suspend fun playItem(
        item: ContentItem,
        previousItem: ContentItem?,
        config: PracticeConfig,
    ) {
        val builder = ScheduleBuilder(engine.sampleRate)
        val reps = config.repetitions.coerceAtLeast(1)
        Log.d(TAG, "playItem ENTER text='${item.text}' spoken='${item.spokenAnswer}' reps=$reps answers=${config.answerEnabled} courtesy=${config.courtesyToneEnabled}")
        val morse = item.morseOverride
            ?: item.text.uppercase()
                .mapNotNull(Morse::codeFor)
                .joinToString("")
        Log.d(TAG, "playItem morse='${morse.take(80)}' isBlank=${morse.isBlank()}")
        if (morse.isBlank()) return

        // Park here while paused so we don't advance to the next item
        // (or its now-playing label) until the user resumes.
        awaitResume()

        // Advance the rolling now-playing window. Done here, after the
        // blank-morse return, so unplayable items never appear.
        _nowPlaying.value = NowPlaying(
            previous = previousItem,
            current = item,
        )

        // Single-shot items (e.g. a news headline) are sent once; hearing
        // a long headline repeated N times would be tedious.
        val effectiveReps = if (item.singleShot) 1 else reps
        val batchForRep = List(effectiveReps) { item }
        val schedule = builder.build(batchForRep, timesToRepeat = 1, config = config)
        engine.setSchedule(schedule, config)
        engine.play()
        waitForAudioToFinish(schedule.totalSamples)

        // Skip/Previous pressed during the tone: engine.abort() unblocked the
        // wait above; bail out now (no answer, no courtesy).
        if (skipRequested || previousRequested) return

        delay(config.postSendPauseMs)

        if (config.answerEnabled) {
            delay(config.answerDelayMs)
            // Sanitize the spoken text before it reaches the TTS
            // engine. The platform TextToSpeech implementation on
            // many devices (Google TTS on stock Android, Samsung
            // TTS, eSpeak) treats `/` as a sentence boundary - it
            // silences the character entirely AND splits the
            // utterance into multiple chunks, firing an early
            // `onDone` for the FIRST prefix chunk. The orchestrator
            // would then resume `awaitTts` and immediately move on
            // to the post-batch courtesy tone while the TTS engine
            // was still speaking the suffix of the callsign;
            // Android's audio-focus ducking suppressed that 100 ms
            // pip entirely, making the courtesy tone vanish for
            // every decorated callsign. Replacing `/` with the
            // spoken word "stroke" wraps the boundary in plain
            // English so the engine treats it as a single spoken
            // sentence and fires `onDone` only when the whole
            // answer is finished.
            val rawAnswer = item.spokenAnswer ?: item.text
            val answer = rawAnswer.replace("/", " stroke ")
            Log.d(TAG, "playItem awaitTts ENTER answer='${answer.take(80)}' id='${item.text}'")
            val ok = awaitTts(answer, item.text)
            Log.d(TAG, "playItem awaitTts RETURN ok=$ok")

            // Optional one-shot replay of the same code, never
            // counted as a repetition. Useful for catching a code
            // the listener missed on the first listening pass.
            // Single-shot items (news headlines) are never replayed -
            // "play each headline only once" means exactly once.
            if (config.replayAfterAnswer && !item.singleShot) {
                awaitResume()
                delay(config.answerDelayMs)
                val replay = builder.build(listOf(item), timesToRepeat = 1, config = config)
                engine.setSchedule(replay, config)
                engine.play()
                waitForAudioToFinish(replay.totalSamples)
            }
        }

        // Skip/Previous pressed during the pause/answer: bail without the pip.
        if (skipRequested || previousRequested) return

        // Courtesy tone: a short pip played after EACH item (once its
        // code and spoken answer have finished), like a real repeater's
        // courtesy tone after every over. This marks the boundary
        // between transmissions so the listener hears the end of each
        // item, not just the end of the whole batch. Disabled by
        // config. Always uses the currently tuned frequency so it
        // matches the practice tone.
        if (config.courtesyToneEnabled) {
            // Don't let the end-of-item pip sound while paused.
            awaitResume()
            Log.d(TAG, "playCourtesyTone ENTER")
            // Let the platform TTS engine release audio focus and drain
            // its final phoneme before our pip plays. The previous "/"-
            // sanitization fix addressed the wider audible bug for
            // decorated callsigns (commit 65f2b0e). On the FIRST item
            // of a freshly-pressed Play session, the TTS engine is
            // still warming up its speech model and holds USAGE_MEDIA
            // audio focus for noticeably longer than on subsequent
            // utterances: stock Android pipes duck our courtesy pip into
            // inaudibility for that window. 400 ms is comfortably
            // longer than the worst observed first TTS focus-release
            // latency (~250 ms on a Pixel 7) and short enough that the
            // listener still feels the pip as part of the same
            // sequence. Only needed when an answer (TTS) was actually
            // spoken for this item; otherwise skip the wait.
            if (config.answerEnabled) delay(400)
            playCourtesyTone(config)
            Log.d(TAG, "playCourtesyTone EXIT")

            // Pause AFTER the courtesy tone too, so the pip reads as the end
            // of this item rather than the start of the next one. This
            // deliberately reuses the answer-delay setting instead of adding
            // another knob: the answer delay is already the user's "breathing
            // room around the answer" value, the pip belongs to that same
            // end-of-item sequence, and when answers are disabled the setting
            // would otherwise be dead weight - here it still shapes the gap
            // between items.
            if (skipRequested || previousRequested) return
            awaitResume()
            delay(config.answerDelayMs)
        }
        Log.d(TAG, "playItem EXIT text='${item.text}'")
    }

    private suspend fun playCourtesyTone(config: PracticeConfig) {
        // The pip plays on the engine's warm, always-running session track,
        // so there is no cold-start swallow to guard against - we just
        // install the pip schedule and play it like any other item.

        // Two short tones back-to-back with NO gap between them, like the
        // familiar repeater "courtesy" pip that real repeaters emit at
        // the end of a sequence. Fixed at 50 ms per tone and 947 / 1187
        // Hz - short enough to feel like an unobtrusive end-of-batch
        // marker, and the two-tone shape makes it unmistakable so the
        // user can hear the boundary without a spoken announcement.
        val toneSamples = (50L * engine.sampleRate / 1000)
            .toInt()
            .coerceAtLeast(1)
        // Pick the midpoint of the practice-tone amplitude range (now
        // 0.35..1.0 per item) so the courtesy tone sits at roughly the same
        // level as the rest of the session, not louder. Bypassing
        // Master/volume-variation would otherwise make it pop out awkwardly.
        val courtesyAmp = if (config.volumeVariationEnabled) 0.7f else 1.0f
        // 30 ms silent warm-up. Android's native AudioFlinger mixer
        // routinely swallows the first ~10-40 ms of a freshly created
        // AudioTrack while its resampler and output stage spin up. For
        // a 50 ms pip, losing a quarter of the symbol is enough to
        // make the whole courtesy tone perceptually vanish on some
        // devices - the alternating-batch symptom we observed before
        // this fix. Prepending a short zero-amplitude event gives the
        // mixer time to consume/flush that warm-up window so the next
        // two events are heard at full amplitude.
        val warmupSamples = (30L * engine.sampleRate / 1000)
            .toInt()
            .coerceAtLeast(1)
        val warmupEvent = ToneEvent(
            startSample = 0,
            endSample = warmupSamples,
            freqHz = 0,
            amplitude = 0f,
            label = "warmup",
        )
        val firstEvent = ToneEvent(
            startSample = warmupSamples,
            endSample = warmupSamples + toneSamples,
            freqHz = 947,
            amplitude = courtesyAmp,
            label = "courtesy1",
        )
        // Second event starts exactly where the first one ends - zero
        // gap. CwAudioEngine mixes overlapping events additively, so
        // we explicitly keep these tones strictly sequential, not
        // overlapping; if they overlapped, it would just sum both
        // frequencies into a buzz instead of a clean two-tone pip.
        val secondEvent = ToneEvent(
            startSample = warmupSamples + toneSamples,
            endSample = warmupSamples + toneSamples * 2,
            freqHz = 1187,
            amplitude = courtesyAmp,
            label = "courtesy2",
        )
        val courtesy = Schedule(
            events = listOf(warmupEvent, firstEvent, secondEvent),
            totalSamples = warmupSamples + toneSamples * 2,
            sampleRate = engine.sampleRate,
        )
        engine.setSchedule(courtesy, config)
        engine.play()
        Log.d(TAG, "playCourtesyTone waitForAudio ENTER totalSamples=${courtesy.totalSamples}")
        waitForAudioToFinish(courtesy.totalSamples)
        Log.d(TAG, "playCourtesyTone waitForAudio RETURN")
        // Brief settling pause so the next batch's first element doesn't
        // butt up against the courtesy tone's envelope tail.
        delay(80)
    }

    private suspend fun waitForAudioToFinish(totalSamples: Int) {
        val budgetSamples = totalSamples + engine.sampleRate
        // Poll on a short delay rather than a tight yield loop: one audio
        // block is ~23 ms, so 10 ms granularity detects completion promptly
        // without burning a core for the length of the item (which matters
        // for long news headlines).
        while (engine.state.value == CwAudioEngine.State.PLAYING ||
            engine.state.value == CwAudioEngine.State.PAUSED
        ) {
            if (engine.elapsedSamples() >= budgetSamples) return
            delay(10)
        }
    }

    private suspend fun awaitTts(text: String, utteranceId: String): Boolean =
        // Bound every per-item TTS wait so a flaky platform engine that
        // never fires `onDone` for some pathological utterance cannot
        // wedge the orchestrator before the post-batch courtesy tone
        // plays. 10 seconds is well above any utterance length a user
        // will hear at any reasonable WPM, and short enough that a wedged
        // answer recovers within one batch rather than stalling the whole
        // session.
        withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                tts.speak(text, utteranceId) { ok ->
                    if (cont.isActive) cont.resume(ok)
                }
            }
        } ?: run {
            Log.w(TAG, "awaitTts TIMEOUT after 10s id='$utteranceId' - proceeding to next item so courtesy tone still plays")
            false
        }
}
