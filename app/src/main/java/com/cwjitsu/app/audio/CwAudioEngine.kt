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
            ensureSessionStartedLocked()
            _state.value = State.PLAYING
        }
    }

    fun pause() {
        if (_state.value == State.PLAYING) _state.value = State.PAUSED
    }

    fun resume() {
        if (_state.value == State.PAUSED) _state.value = State.PLAYING
    }

    /**
     * Abandon the current schedule but keep the session track warm. Used to
     * skip an item without paying a cold-start on the next one.
     */
    fun abort() {
        synchronized(lock) {
            curSchedule = null
            playPos = 0L
            samplesElapsed = 0L
        }
        _state.value = State.STOPPED
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

            renderBlock(block, noiseBuf, pcm, sched, pos, cfg, ns)
            val written = track.write(pcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                // The track died (device change, mediaserver restart). Flip
                // to STOPPED so waiters (waitForAudioToFinish) are released
                // instead of waiting on a play position that will never
                // advance again.
                _state.value = State.STOPPED
                break
            }

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

    /** Render one [blockSize] block of [sched] starting at absolute [pos]. */
    private fun renderBlock(
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
                block[i] += sin(phase).toFloat() * event.amplitude * env * 0.5f
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
