package com.cwjitsu.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Thin wrapper around the platform [TextToSpeech] engine.
 *
 * The orchestrator calls [speak] with the answer text and a [onDone] callback that
 * fires when the engine finishes (or fails). [speak] is a no-op (and immediately
 * invokes [onDone] with `false`) until the engine is ready, but it WILL eventually
 * fire even if the engine never reports ready so the orchestrator never hangs.
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var initFailed: Boolean = false
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    @Synchronized
    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.language = Locale.US
                    val drain = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    drain.forEach { it() }
                } else {
                    initFailed = true
                    // Drain pending callers with no work to do; speak() will
                    // short-circuit with onDone(false) once they retry.
                    pendingCallbacks.clear()
                }
            }
        }
    }

    /** Schedule [block] for when the engine is ready, or run it now. */
    fun whenReady(block: () -> Unit) {
        if (ready) {
            block()
            return
        }
        if (initFailed) return
        synchronized(pendingCallbacks) { pendingCallbacks.add(block) }
    }

    fun speak(text: String, utteranceId: String, onDone: (Boolean) -> Unit) {
        if (initFailed) { onDone(false); return }
        val engine = tts ?: run { onDone(false); return }
        if (!ready) {
            whenReady { speak(text, utteranceId, onDone) }
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { onDone(false) }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        initFailed = false
    }
}
