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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
    companion object {
        private const val TAG = "CWJitsu/Orch"
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

    // Set by [skip] to abandon the current item and jump to the next one.
    // Cleared by the playback loop once it advances.
    @Volatile
    private var skipRequested = false

    private var job: Job? = null

    fun start(regenerator: ContentRegenerator, config: PracticeConfig) {
        stop()
        Log.d(TAG, "start regenerator")
        _paused.value = false
        skipRequested = false
        job = scope.launch(Dispatchers.Default) {
            _state.value = RunnerState.RUNNING
            // Fresh session starts with a blank now-playing window.
            _nowPlaying.value = NowPlaying()
            try {
            while (isActive) {
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
                playBatch(items, config)
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
        // engine.stop() joins the render worker briefly; do it off the caller's
        // (UI) thread so a tap never janks. Setting the flag first guarantees
        // the loop observes the skip once waitForAudioToFinish returns.
        scope.launch { engine.stop() }
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

    private suspend fun playBatch(items: List<ContentItem>, config: PracticeConfig) {
        val sampleRate = engine.sampleRate
        val builder = ScheduleBuilder(sampleRate)
        val reps = config.repetitions.coerceAtLeast(1)
        Log.d(TAG, "playBatch ENTER items=${items.size} reps=$reps answers=${config.answerEnabled} courtesy=${config.courtesyToneEnabled}")

        for ((idx, item) in items.withIndex()) {
            Log.d(TAG, "playBatch item[$idx] text='${item.text}' spoken='${item.spokenAnswer}'")
            val morse = item.morseOverride
                ?: item.text.uppercase()
                    .mapNotNull(Morse::codeFor)
                    .joinToString("")
            Log.d(TAG, "playBatch item[$idx] morse='${morse.take(80)}' isBlank=${morse.isBlank()}")
            if (morse.isBlank()) continue

            // Park here while paused so we don't advance to the next item
            // (or its now-playing label) until the user resumes.
            awaitResume()

            // Advance the rolling now-playing window: what we sent last
            // becomes "previous", this item becomes "current". Done here,
            // after the blank-morse skip, so skipped items never appear.
            _nowPlaying.value = NowPlaying(
                previous = _nowPlaying.value.current,
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

            // Skip pressed during the tone: engine.stop() unblocked the wait
            // above; jump straight to the next item (no answer, no courtesy).
            if (skipRequested) { skipRequested = false; continue }

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
                Log.d(TAG, "playBatch item[$idx] awaitTts ENTER answer='${answer.take(80)}' id='${item.text}'")
                val ok = awaitTts(answer, item.text)
                Log.d(TAG, "playBatch item[$idx] awaitTts RETURN ok=$ok")

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

            // Skip pressed during the pause/answer: advance without the pip.
            if (skipRequested) { skipRequested = false; continue }

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
                Log.d(TAG, "playCourtesyTone ENTER item[$idx]")
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
                Log.d(TAG, "playCourtesyTone EXIT item[$idx]")
            }
        }
        Log.d(TAG, "playBatch EXIT for-loop, courtesy=${config.courtesyToneEnabled}")
    }

    private suspend fun playCourtesyTone(config: PracticeConfig) {
        // Force-release any prior AudioTrack before we install the
        // courtesy schedule. The previous fix already nulls the
        // audioTrack field on every stopInternal, but being explicit
        // here makes the intent unambiguous and bypasses any rare
        // AudioFlinger state-leakage on extremely short, alternating
        // playbacks ("every other one missing") that we observed in
        // the field start with the first.
        engine.release()

        // Two short tones back-to-back with NO gap between them, like the
        // familiar repeater "courtesy" pip that real repeaters emit at
        // the end of a sequence. Fixed at 50 ms per tone and 947 / 1187
        // Hz - short enough to feel like an unobtrusive end-of-batch
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
        var iters = 0
        while (engine.state.value == CwAudioEngine.State.PLAYING ||
            engine.state.value == CwAudioEngine.State.PAUSED
        ) {
            if (engine.elapsedSamples() >= budgetSamples) return
            if (++iters % 200 == 0) {
                Log.d(TAG, "waitForAudio iters=$iters state=${engine.state.value} elapsed=${engine.elapsedSamples()}/$budgetSamples")
            }
            yield()
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
