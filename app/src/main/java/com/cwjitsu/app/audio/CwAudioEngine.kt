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
 * Drives an [AudioTrack] that mixes a [Schedule]'s tone events plus background noise,
 * real-time, into PCM 16-bit output.
 */
class CwAudioEngine(
    val sampleRate: Int = 44_100,
    val blockSize: Int = 1024,
) {

    enum class State { IDLE, PLAYING, PAUSED, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private var audioTrack: AudioTrack? = null
    private var workerThread: Thread? = null
    private var schedule: Schedule? = null
    private var config: PracticeConfig = PracticeConfig()
    private var noise: NoiseGenerator = NoiseGenerator(NoiseType.NONE)
    private val envelope = Envelope(sampleRate)

    companion object {
        private const val TAG = "CWJitsu/Audio"
    }

    @Volatile private var writePos: Long = 0L
    @Volatile private var samplesWritten: Long = 0L
    @Volatile private var running: Boolean = false

    fun setSchedule(schedule: Schedule, config: PracticeConfig) {
        stopInternal()
        this.schedule = schedule
        this.config = config
        this.noise = NoiseGenerator(config.noiseType)
    }

    fun play() {
        val sched = schedule ?: return
        if (_state.value == State.PLAYING) {
            // Defensive: stopInternal should already have left us in
            // State.STOPPED, but if a previous worker is still mid-
            // `track.write()` (e.g. an oversized block or a slow device),
            // force-stop here so we never silently skip starting the
            // new playback. Without this guard the courtesy-pip was the
            // most common casualty because the post-batch play() was
            // racing with a still-dying worker.
            Log.w(TAG, "play: stale PLAYING state, force-stopping before new playback")
            stopInternal()
        }

        val track = ensureTrack()
        track.play()
        _state.value = State.PLAYING
        running = true
        writePos = 0L
        samplesWritten = 0L
        // Assign the worker field BEFORE start() so that pumpTo's
        // thread-identity check (see end of pumpTo) can immediately tell
        // whether it is still the current worker when its loop ends.
        val worker = Thread({ pumpTo(track, sched) }, "CW-AudioEngine").also {
            it.isDaemon = true
        }
        workerThread = worker
        worker.start()
    }

    fun pause() {
        if (_state.value != State.PLAYING) return
        audioTrack?.pause()
        _state.value = State.PAUSED
    }

    fun resume() {
        if (_state.value != State.PAUSED) return
        audioTrack?.play()
        _state.value = State.PLAYING
    }

    fun stop() {
        stopInternal()
        _state.value = State.STOPPED
    }

    fun release() {
        stopInternal()
        audioTrack?.release()
        audioTrack = null
    }

    fun elapsedSamples(): Long = samplesWritten

    private fun ensureTrack(): AudioTrack {
        audioTrack?.let { return it }
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
        val track = AudioTrack(
            attrs, format, minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack = track
        return track
    }

    private fun pumpTo(track: AudioTrack, sched: Schedule) {
        val block: FloatArray = FloatArray(blockSize)
        val noiseBuf: FloatArray = FloatArray(blockSize)
        val pcm: ShortArray = ShortArray(blockSize)
        var localWrite: Long = 0L
        val master: Float = config.masterVolume
        // Tone fading is always on; clicks at element boundaries are
        // always smoothed via the envelope. Used to be a user toggle,
        // but in practice no one wanted the clicky variant, so it is
        // not configurable anymore.
        val fading: Boolean = true
        val noiseEnabled: Boolean = config.noiseType != NoiseType.NONE && config.noiseVolume > 0f
        val noiseVol: Float = config.noiseVolume
        val maxSamples: Long = sched.totalSamples.toLong()

        while (running && localWrite <= maxSamples + sampleRate) {
            if (_state.value == State.PAUSED) {
                Thread.sleep(10)
                continue
            }

            // 1) Zero the block.
            block.fill(0f)

            // 2) Sum contributions from every ToneEvent that overlaps this block.
            for (event in sched.events) {
                val blockEnd: Long = localWrite + blockSize
                val evStart: Long = event.startSample.toLong()
                val evEnd: Long = event.endSample.toLong()
                if (evEnd <= localWrite || evStart >= blockEnd) continue

                val startInBlock: Int = (evStart - localWrite).toInt().coerceAtLeast(0)
                val endInBlock: Int = (evEnd - localWrite).toInt().coerceAtMost(blockSize)
                val toneLenSamples: Int = (evEnd - evStart).toInt()
                val freq: Double = event.freqHz.toDouble()
                for (i in startInBlock until endInBlock) {
                    val sampleIdxInEvent: Long = localWrite + i - evStart
                    val phase: Double = 2.0 * PI * freq * sampleIdxInEvent / sampleRate
                    val env: Float = if (fading) envelope.gain(sampleIdxInEvent.toInt(), toneLenSamples) else 1f
                    val tone: Float = sin(phase).toFloat() * event.amplitude * env * 0.5f
                    block[i] += tone
                }
            }

            // 3) Generate noise into a separate buffer and blend it.
            if (noiseEnabled) {
                noise.fill(noiseBuf, 0, blockSize)
                for (i in 0 until blockSize) {
                    block[i] = block[i] * (1f - noiseVol) + noiseBuf[i] * noiseVol
                }
            }

            // 4) Master volume, clamp, and convert to int16.
            for (i in 0 until blockSize) {
                val clipped: Float = (block[i] * master).coerceIn(-1f, 1f)
                block[i] = clipped
                pcm[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
            }

            val written = track.write(pcm, 0, blockSize, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break

            localWrite = localWrite + blockSize.toLong()
            writePos = localWrite
            samplesWritten = localWrite

            if (localWrite > maxSamples + sampleRate) break
        }
        // Only flip state if WE are still the current worker - otherwise
        // a brand-new worker has already taken over and we'd yank its
        // PLAYING state back to STOPPED, briefly silencing the new
        // playback (this is what made the courtesy tone vanish
        // intermittently when the prior item's worker was still exiting
        // while play() raced ahead).
        if (Thread.currentThread() === workerThread) {
            _state.value = State.STOPPED
        }
        running = false
    }

    private fun stopInternal() {
        running = false
        workerThread?.let {
            // Bumped from 200ms to 500ms: a real device can take a few
            // hundred ms to drain a full 1024-sample blocking write on
            // a busy I/O scheduler, and stopping too early left the
            // worker alive with state still PLAYING - which silently
            // skipped the courtesy tone on the next play() call.
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        workerThread = null
        // Explicitly flip state to STOPPED here so a subsequent play()
        // is never confused by a "stale" PLAYING state set by a worker
        // that hasn't yet finished its last write. The dying worker
        // is guarded by a thread-identity check in pumpTo, so it can
        // no longer flip us back into STOPPED once a new worker has
        // taken over.
        _state.value = State.STOPPED
        audioTrack?.let {
            try { it.pause() } catch (_: IllegalStateException) {}
            try { it.flush() } catch (_: IllegalStateException) {}
            try { it.stop() } catch (_: IllegalStateException) {}
            // Release the track entirely. Reusing an AudioTrack across
            // multiple stop()/play() cycles is known to misbehave on
            // some Android OEM builds: after a stop(), the next play()
            // can be silently inaudible because AudioFlinger still has
            // the resampler in a partially-primed state from the prior
            // session. Throwing the track away and letting `ensureTrack`
            // create a fresh one guarantees the next playback starts on
            // a clean write head. AudioTrack creation is cheap (a few ms)
            // and the orchestrator already has a post-pause + TTS settle
            // window between items, so no audible gap is introduced.
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
        writePos = 0L
        samplesWritten = 0L
    }
}
