package com.cwjitsu.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
        if (_state.value == State.PLAYING) return

        val track = ensureTrack()
        track.play()
        _state.value = State.PLAYING
        running = true
        writePos = 0L
        samplesWritten = 0L
        workerThread = Thread({ pumpTo(track, sched) }, "CW-AudioEngine").also {
            it.isDaemon = true
            it.start()
        }
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
        val fading: Boolean = config.toneFadingEnabled
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
        _state.value = State.STOPPED
        running = false
    }

    private fun stopInternal() {
        running = false
        workerThread?.let {
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        workerThread = null
        audioTrack?.let {
            try { it.pause() } catch (_: IllegalStateException) {}
            try { it.flush() } catch (_: IllegalStateException) {}
            try { it.stop() } catch (_: IllegalStateException) {}
        }
        writePos = 0L
        samplesWritten = 0L
    }
}
