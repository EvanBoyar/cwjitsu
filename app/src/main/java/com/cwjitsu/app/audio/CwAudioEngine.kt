package com.cwjitsu.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.cwjitsu.app.practice.NoiseType
import com.cwjitsu.app.practice.PracticeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Drives an [AudioTrack] that mixes a [Schedule]'s tone events plus background
 * noise, real-time, into PCM 16-bit output.
 *
 * Keep-warm model: a single AudioTrack and a single worker thread stay alive
 * for the whole session. The worker streams the active schedule's tones and
 * fills the gaps between schedules with silence, so the track never stops.
 * This matters because Android's AudioFlinger swallows the first few tens of
 * milliseconds of a *freshly created* track while it spins up — which used to
 * make the very short courtesy pip vanish intermittently. On a warm track
 * there is no cold start, so every sound (long code or short pip) is heard in
 * full, with no sacrificial-silence guesswork.
 */
class CwAudioEngine(
    val sampleRate: Int = 44_100,
    val blockSize: Int = 1024,
) {

    enum class State { IDLE, PLAYING, PAUSED, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val envelope = Envelope(sampleRate)
    private val silencePcm = ShortArray(blockSize)  // reused zero buffer

    companion object {
        private const val TAG = "CWJitsu/Audio"
        // Silence blocks written when a session's track is first created, to
        // absorb the one-time AudioFlinger cold-start before real tones play.
        private const val PREROLL_BLOCKS = 6

        // When a tone is cut off mid-element (Skip/Previous/Restart), fade it
        // out over this many ms instead of dropping to zero instantly, which
        // pops. Slightly longer than the per-element ramp (Envelope.rampMs)
        // since an abort can land at full amplitude; still short enough that
        // the navigation feels immediate. Fits within one render block.
        private const val ABORT_FADE_MS = 6

        // When a paused tone is resumed, fade it back in over this many ms.
        // Pause freezes the sine mid-cycle, so continuing it would snap in
        // from silence at a nonzero amplitude and pop. Fits within one block.
        private const val RESUME_FADE_MS = 8
    }

    // Session-level track + worker. Guarded by [lock] for lifecycle.
    private val lock = Any()
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var sessionRunning = false

    // Active render state, guarded by [lock].
    private var curSchedule: Schedule? = null
    private var curConfig: PracticeConfig = PracticeConfig()
    private var curNoise: NoiseGenerator = NoiseGenerator(NoiseType.NONE)
    private var playPos: Long = 0L

    @Volatile private var samplesElapsed: Long = 0L

    // Set by [abort] when a tone is playing: the worker renders one short
    // fade-to-silence block (declick) before dropping the schedule, then
    // finalizes to STOPPED. Kept separate from a plain stop so an abort with
    // no active tone still cuts instantly.
    @Volatile private var abortRequested = false

    /**
     * Install the schedule to be played by the next [play] call. Deliberately
     * does NOT touch the engine config: [updateConfig] is the single path for
     * that. When schedules also carried a config, a courtesy pip or replay
     * built from an item-start snapshot would re-apply a STALE config
     * mid-item, briefly reverting a settings change (noise, volume) the live
     * collector had already applied.
     */
    fun setSchedule(schedule: Schedule) {
        synchronized(lock) {
            curSchedule = schedule
            playPos = 0L
            samplesElapsed = 0L
        }
    }

    /**
     * The single path for engine-level config (master volume, noise type and
     * volume - everything read per rendered block). Driven by the
     * orchestrator: live on every settings emission, plus once per item so a
     * fresh session is deterministic before the first block renders.
     * Timing/frequency changes are baked into schedules and apply from the
     * next [setSchedule]. Keeps the noise generator across updates of the
     * same noise type so the (stateful) brown-noise filter doesn't reset
     * with an audible discontinuity on every tweak.
     */
    fun updateConfig(config: PracticeConfig) {
        synchronized(lock) {
            if (config.noiseType != curConfig.noiseType) {
                curNoise = NoiseGenerator(config.noiseType)
            }
            curConfig = config
        }
    }

    /** Start playing the installed schedule on the warm session track. */
    fun play() {
        synchronized(lock) {
            if (curSchedule == null) return
            playPos = 0L
            samplesElapsed = 0L
            abortRequested = false
            ensureSessionStartedLocked()
            _state.value = State.PLAYING
        }
    }

    fun pause() {
        if (_state.value == State.PLAYING) _state.value = State.PAUSED
    }

    fun resume() {
        // The worker infers the resume from the PLAYING state and fades the
        // tone back in only if it had actually gone silent (see toneLive in
        // pumpLoop) - so a pause too brief to interrupt the tone doesn't get a
        // spurious fade-in (which would itself click).
        if (_state.value == State.PAUSED) _state.value = State.PLAYING
    }

    /**
     * Abandon the current schedule but keep the session track warm. Used to
     * skip an item without paying a cold-start on the next one.
     *
     * If a tone is actively playing, the cut is deferred by one short fade
     * block: the worker fades the in-flight tone to silence (see
     * [renderDeclickTail]) and only then flips to STOPPED, so cutting mid-
     * element doesn't pop. The engine therefore stays PLAYING for that extra
     * ~one block (a few ms), which is exactly what any [state] waiter should
     * wait for - the sound really is still tailing out. With no active tone
     * there is nothing to declick, so the cut is immediate as before.
     */
    fun abort() {
        val deferToFade = synchronized(lock) {
            if (_state.value == State.PLAYING && curSchedule != null) {
                abortRequested = true
                true
            } else {
                curSchedule = null
                playPos = 0L
                samplesElapsed = 0L
                abortRequested = false
                false
            }
        }
        if (!deferToFade) _state.value = State.STOPPED
    }

    /** End the session: stop the worker and release the track. */
    fun stop() = teardown()

    fun release() = teardown()

    fun elapsedSamples(): Long = samplesElapsed

    private fun teardown() {
        val t: AudioTrack?
        val w: Thread?
        synchronized(lock) {
            sessionRunning = false
            t = track
            w = worker
            track = null
            worker = null
            curSchedule = null
            playPos = 0L
            samplesElapsed = 0L
            abortRequested = false
        }
        // Join outside the lock so the worker (which may briefly take the
        // lock to snapshot state) can finish its current block and exit.
        w?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        t?.let {
            try { it.pause() } catch (_: IllegalStateException) {}
            try { it.flush() } catch (_: IllegalStateException) {}
            try { it.stop() } catch (_: IllegalStateException) {}
            try { it.release() } catch (_: Exception) {}
        }
        _state.value = State.STOPPED
    }

    /** Must hold [lock]. Creates the track + worker if the session isn't up. */
    private fun ensureSessionStartedLocked() {
        if (sessionRunning && worker?.isAlive == true) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(blockSize * 4)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val t = AudioTrack(
            attrs, format, minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        t.play()
        track = t
        sessionRunning = true
        worker = Thread({ pumpLoop(t) }, "CW-AudioEngine").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The single per-session render loop. Renders the active schedule when
     * PLAYING and streams background noise (or silence when noise is off)
     * otherwise, so the track stays warm AND the noise bed is continuous for
     * the whole session - through pauses between items, spoken answers, and
     * courtesy tones - instead of gating harshly on and off around each send.
     */
    private fun pumpLoop(track: AudioTrack) {
        val block = FloatArray(blockSize)
        val noiseBuf = FloatArray(blockSize)
        val pcm = ShortArray(blockSize)

        // One-time cold-start pre-roll so the first real tone isn't clipped.
        repeat(PREROLL_BLOCKS) {
            if (!sessionRunning) return
            track.write(silencePcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
        }

        // Whether the last thing written was a live tone block. Drives the
        // declick fades WITHOUT any caller-set flag, so it can't get out of
        // sync under rapid pause/resume: we only fade a tone OUT when we're
        // actually leaving live tone for silence, and only fade one back IN
        // when we're re-entering tone from silence. A pause/resume so brief
        // that the worker never rendered a silent block leaves toneLive true,
        // so the tone is never interrupted and gets neither fade (a spurious
        // fade-in on an uninterrupted tone would itself click).
        var toneLive = false

        while (sessionRunning) {
            val sched: Schedule?
            val pos: Long
            val cfg: PracticeConfig
            val ns: NoiseGenerator
            synchronized(lock) {
                sched = curSchedule
                pos = playPos
                cfg = curConfig
                ns = curNoise
            }

            // A tone was cut off (Skip/Previous/Restart): fade it out over one
            // short block so it doesn't pop, then drop the schedule and stop.
            if (abortRequested) {
                if (sched != null && pos < sched.totalSamples &&
                    _state.value == State.PLAYING
                ) {
                    renderDeclickTail(block, noiseBuf, pcm, sched, pos, cfg, ns)
                    track.write(pcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
                }
                synchronized(lock) {
                    abortRequested = false
                    curSchedule = null
                    playPos = 0L
                    samplesElapsed = 0L
                }
                toneLive = false
                _state.value = State.STOPPED
                continue
            }

            val playing = _state.value == State.PLAYING
            if (!playing || sched == null || pos >= sched.totalSamples) {
                if (playing) {
                    // Current schedule finished (or none): mark done and idle.
                    synchronized(lock) {
                        if (curSchedule === sched && _state.value == State.PLAYING) {
                            _state.value = State.STOPPED
                        }
                    }
                }
                // Leaving live tone for silence/noise mid-element (a pause):
                // fade the tone out into the stream first so the buffered
                // audio descends to silence instead of stepping to it (a pop),
                // then fall through to the normal idle/noise handling. Skipped
                // at a schedule's natural end (pos >= total), where the last
                // element already ended through its own release ramp.
                if (toneLive) {
                    toneLive = false
                    if (sched != null && pos < sched.totalSamples) {
                        renderDeclickTail(block, noiseBuf, pcm, sched, pos, cfg, ns)
                        track.write(pcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
                        // Leave playPos put: resume re-renders from here with a
                        // fade-in, so the cut element eases out then back in.
                        continue
                    }
                }
                // A user-level pause with no noise bed can last hours; keep
                // mixing zero blocks and the audio pipeline never sleeps.
                // Park the track instead and poll cheaply until resumed.
                // Short between-item idles (TTS, delays) never enter here
                // because their engine state is STOPPED, not PAUSED - so the
                // warm-track guarantee for normal playback is untouched.
                if (_state.value == State.PAUSED && !noiseAudible(cfg)) {
                    idleWhilePaused(track)
                    continue
                }
                renderNoiseOnly(noiseBuf, pcm, cfg, ns)
                track.write(pcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
                continue
            }

            // Re-entering tone from silence (resume, or a fresh item) fades in
            // so the sine - which pause may have frozen mid-cycle - eases up
            // from zero instead of snapping in at full amplitude (a pop). Only
            // when coming from not-live: an uninterrupted tone gets no fade-in,
            // so rapid pause/resume can't inject a click.
            val fadeInLen = if (!toneLive) {
                (RESUME_FADE_MS * sampleRate / 1000).coerceIn(1, blockSize)
            } else {
                0
            }
            renderBlock(block, noiseBuf, pcm, sched, pos, cfg, ns, fadeInLen)
            val written = track.write(pcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                // The track died (device change, mediaserver restart). Flip
                // to STOPPED so waiters (waitForAudioToFinish) are released
                // instead of waiting on a play position that will never
                // advance again.
                toneLive = false
                _state.value = State.STOPPED
                break
            }
            toneLive = true

            synchronized(lock) {
                if (curSchedule === sched) {
                    playPos = pos + blockSize
                    samplesElapsed = playPos
                }
            }
        }
    }

    private fun noiseAudible(cfg: PracticeConfig): Boolean =
        cfg.noiseType != NoiseType.NONE && cfg.noiseVolume > 0f

    /**
     * Pause the AudioTrack for the duration of a noise-free user pause so
     * the audio HAL can sleep, then re-warm it on resume. A 50 ms poll while
     * parked is orders of magnitude cheaper than mixing 43 blocks of zeros
     * per second. Exits early if the session tears down or the user turns
     * a noise bed on mid-pause.
     */
    private fun idleWhilePaused(track: AudioTrack) {
        try { track.pause() } catch (_: IllegalStateException) { return }
        while (sessionRunning && _state.value == State.PAUSED &&
            !synchronized(lock) { noiseAudible(curConfig) }
        ) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
        if (!sessionRunning) return
        try { track.play() } catch (_: IllegalStateException) { return }
        // Two silence blocks (~46 ms) so devices that swallow the first
        // milliseconds after play() don't clip the resumed tone.
        repeat(2) {
            track.write(silencePcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
        }
    }

    /**
     * Render a tone-free block: just the noise bed at exactly the same level
     * [renderBlock] mixes it during a schedule's silent gaps, so there is no
     * audible level step when a schedule starts or ends.
     */
    private fun renderNoiseOnly(
        noiseBuf: FloatArray,
        pcm: ShortArray,
        cfg: PracticeConfig,
        ns: NoiseGenerator,
    ) {
        if (!noiseAudible(cfg)) {
            pcm.fill(0)
            return
        }
        ns.fill(noiseBuf, 0, blockSize)
        val gain = cfg.noiseVolume * cfg.masterVolume
        for (i in 0 until blockSize) {
            val clipped = (noiseBuf[i] * gain).coerceIn(-1f, 1f)
            pcm[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /**
     * Render the declick tail of a tone cut off at [pos]: identical to
     * [renderBlock] except every tone sample is additionally scaled by a
     * fade-out ramp that reaches zero within [ABORT_FADE_MS], so the sudden
     * cut eases to silence instead of stepping to it (which pops). The noise
     * bed is mixed across the whole block unchanged, so only the tone fades -
     * matching what the listener already hears in the schedule's silent gaps.
     * The fade fits inside one block; samples past it carry no tone.
     */
    private fun renderDeclickTail(
        block: FloatArray,
        noiseBuf: FloatArray,
        pcm: ShortArray,
        sched: Schedule,
        pos: Long,
        cfg: PracticeConfig,
        ns: NoiseGenerator,
    ) {
        val master = cfg.masterVolume
        val noiseEnabled = cfg.noiseType != NoiseType.NONE && cfg.noiseVolume > 0f
        val noiseVol = cfg.noiseVolume
        val fadeLen = (ABORT_FADE_MS * sampleRate / 1000).coerceIn(1, blockSize)

        block.fill(0f)

        for (event in sched.events) {
            val blockEnd = pos + blockSize
            val evStart = event.startSample.toLong()
            val evEnd = event.endSample.toLong()
            if (evEnd <= pos || evStart >= blockEnd) continue

            val startInBlock = (evStart - pos).toInt().coerceAtLeast(0)
            val endInBlock = (evEnd - pos).toInt().coerceAtMost(blockSize)
            val toneLenSamples = (evEnd - evStart).toInt()
            val freq = event.freqHz.toDouble()
            for (i in startInBlock until endInBlock) {
                // Position within this block drives the fade: sample 0 of the
                // block is where the tone was cut (gain 1), ramping to 0.
                if (i >= fadeLen) break
                val sampleIdxInEvent = pos + i - evStart
                val phase = 2.0 * PI * freq * sampleIdxInEvent / sampleRate
                val env = envelope.gain(sampleIdxInEvent.toInt(), toneLenSamples)
                val declick = envelope.fadeOutGain(i, fadeLen)
                block[i] += sin(phase).toFloat() * event.amplitude * env * 0.5f * declick
            }
        }

        if (noiseEnabled) {
            ns.fill(noiseBuf, 0, blockSize)
            for (i in 0 until blockSize) {
                block[i] = block[i] * (1f - noiseVol) + noiseBuf[i] * noiseVol
            }
        }

        for (i in 0 until blockSize) {
            val clipped = (block[i] * master).coerceIn(-1f, 1f)
            pcm[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /**
     * Render one [blockSize] block of [sched] starting at absolute [pos].
     * When [fadeInLen] > 0 the tone is additionally scaled by a fade-in ramp
     * over the first [fadeInLen] samples of the block (used on the first block
     * after a resume so the mid-cycle sine eases back in instead of popping).
     */
    private fun renderBlock(
        block: FloatArray,
        noiseBuf: FloatArray,
        pcm: ShortArray,
        sched: Schedule,
        pos: Long,
        cfg: PracticeConfig,
        ns: NoiseGenerator,
        fadeInLen: Int = 0,
    ) {
        val master = cfg.masterVolume
        val noiseEnabled = cfg.noiseType != NoiseType.NONE && cfg.noiseVolume > 0f
        val noiseVol = cfg.noiseVolume

        block.fill(0f)

        // Sum every ToneEvent overlapping this block.
        for (event in sched.events) {
            val blockEnd = pos + blockSize
            val evStart = event.startSample.toLong()
            val evEnd = event.endSample.toLong()
            if (evEnd <= pos || evStart >= blockEnd) continue

            val startInBlock = (evStart - pos).toInt().coerceAtLeast(0)
            val endInBlock = (evEnd - pos).toInt().coerceAtMost(blockSize)
            val toneLenSamples = (evEnd - evStart).toInt()
            val freq = event.freqHz.toDouble()
            for (i in startInBlock until endInBlock) {
                val sampleIdxInEvent = pos + i - evStart
                val phase = 2.0 * PI * freq * sampleIdxInEvent / sampleRate
                val env = envelope.gain(sampleIdxInEvent.toInt(), toneLenSamples)
                var sample = sin(phase).toFloat() * event.amplitude * env * 0.5f
                // Block-relative fade-in (sample 0 = the resume point).
                if (fadeInLen > 0) sample *= envelope.fadeInGain(i, fadeInLen)
                block[i] += sample
            }
        }

        if (noiseEnabled) {
            ns.fill(noiseBuf, 0, blockSize)
            for (i in 0 until blockSize) {
                block[i] = block[i] * (1f - noiseVol) + noiseBuf[i] * noiseVol
            }
        }

        for (i in 0 until blockSize) {
            val clipped = (block[i] * master).coerceIn(-1f, 1f)
            pcm[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
