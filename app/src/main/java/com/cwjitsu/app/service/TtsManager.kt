package com.cwjitsu.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around the platform [TextToSpeech] engine.
 *
 * The orchestrator calls [speak] with the answer text and a [onDone] callback that
 * fires when the engine finishes (or fails). [speak] is a no-op (and immediately
 * invokes [onDone] with `false`) until the engine is ready, but it WILL eventually
 * fire even if the engine never reports ready so the orchestrator never hangs.
 *
 * # Listener ownership
 *
 * A single global [UtteranceProgressListener] is installed exactly once during
 * [init]. It dispatches the engine's callback to whatever continuation is
 * currently registered as [activeCallback]. Earlier versions installed a fresh
 * listener on every [speak] call; that combined with [TextToSpeech.QUEUE_FLUSH]
 * produced a deadlock where the listener installed for utterance N was
 * overwritten before N's [onDone] fired, so the [cont.resume] closure captured
 * for N never got called. The orchestrator would then hang in
 * `SessionOrchestrator.awaitTts` for the lifetime of the practice session —
 * no answer would be spoken, and the courtesy-tone that runs *after* the
 * per-item awaitTts would never be reached.
 *
 * # Utterance-id uniqueness
 *
 * Each [speak] call generates a synthetic utterance-id by appending a
 * monotonic suffix to the caller-provided id. Two consecutive items can
 * legitimately produce identical user-facing text (e.g. the same callsign
 * back-to-back); the platform engine on some devices will merge progress
 * events across identical ids and only fire [onDone] once, which would
 * otherwise deadlock the second item.
 *
 * # Boundary-character splitting
 *
 * Some platform engines split an utterance on boundary characters such as `/`
 * and fire [onDone] per chunk, all with the same utteranceId. The active-id
 * check in the listener guarantees we only resolve the actively-awaited
 * utterance and ignore partial / late callbacks.
 *
 * # Cancellation / stop
 *
 * [stop] and [release] drain the active callback with `ok = false` so an
 * orchestrator coroutine awaiting this utterance is released instead of
 * hanging indefinitely.
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "CWJitsu/TtsManager"
    }

    private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var initFailed: Boolean = false
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    // The currently-awaited utterance: the listener routes every callback
    // through [resolveIfActive], which fires the captured continuation only
    // when the engine-supplied utteranceId matches [activeId]. Stale
    // callbacks from prior / split utterances fall on the floor harmlessly.
    private var activeId: String? = null
    private var activeCallback: ((Boolean) -> Unit)? = null

    @Synchronized
    fun init() {
        if (tts != null) return
        Log.d(TAG, "init() creating TextToSpeech")
        tts = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.language = Locale.US
                    Log.d(TAG, "init TextToSpeech SUCCESS")
                    // Install the listener EXACTLY once. Earlier versions
                    // installed a fresh listener inside speak(); that made
                    // every queued continuation race against the listener
                    // for the *next* utterance, eventually orphaning
                    // callback closures and deadlocking the orchestrator.
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "onStart id=$utteranceId activeId=$activeId")
                        }
                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "onDone id=$utteranceId activeId=$activeId")
                            resolveIfActive(utteranceId, ok = true)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.d(TAG, "onError id=$utteranceId activeId=$activeId")
                            resolveIfActive(utteranceId, ok = false)
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.d(TAG, "onError id=$utteranceId code=$errorCode activeId=$activeId")
                            resolveIfActive(utteranceId, ok = false)
                        }
                        private fun resolveIfActive(id: String?, ok: Boolean) {
                            synchronized(this@TtsManager) {
                                if (id == activeId) {
                                    Log.d(TAG, "resolveIfActive MATCH id=$id ok=$ok")
                                    val cb = activeCallback
                                    activeCallback = null
                                    activeId = null
                                    cb?.invoke(ok)
                                } else {
                                    Log.d(TAG, "resolveIfActive SKIP id=$id activeId=$activeId")
                                }
                            }
                        }
                    })
                    val drain = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    drain.forEach { it() }
                } else {
                    initFailed = true
                    Log.w(TAG, "init TextToSpeech FAIL status=$status")
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
        if (initFailed) { Log.w(TAG, "speak DROP initFailed text=$text id=$utteranceId"); onDone(false); return }
        val engine = tts ?: run { Log.w(TAG, "speak DROP tts==null text=$text id=$utteranceId"); onDone(false); return }
        if (!ready) {
            Log.d(TAG, "speak QUEUED not-ready text=$text id=$utteranceId")
            whenReady { speak(text, utteranceId, onDone) }
            return
        }
        // Sanitize the caller-supplied prefix so it is safe to embed in
        // the synthetic utterance-id. Some platform engines (notably
        // Samsung's baked-in TTS, and several Google TTS builds on
        // older Android) refuse to fire any progress callbacks when
        // the utteranceId contains a `/` or other punctuation, which
        // would silently dead-lock `SessionOrchestrator.awaitTts`. The
        // UUID uniqueness guarantee does not depend on the prefix
        // being intact, so we just replace any suspicious characters
        // with an underscore.
        val safePrefix = utteranceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        // Unique synthetic id so two consecutive identical user-facing
        // items (e.g. the same callsign back-to-back) get progress
        // events delivered independently — some engines merge progress
        // across identical ids and only fire onDone once. The UUID
        // suffix is the actual uniqueness guarantee; the caller-supplied
        // id is kept purely as a debugging breadcrumb in case we ever
        // need to grep logs for which item triggered a deadlock.
        val uniqueId = "$safePrefix\u00b7${java.util.UUID.randomUUID()}"
        Log.d(TAG, "speak ENTER text='${text.take(80)}' id=$uniqueId (orig=$utteranceId)")
        synchronized(this) {
            activeCallback = onDone
            activeId = uniqueId
        }
        // engine.speak() can return TextToSpeech.ERROR synchronously
        // (for example if the engine language is missing for the
        // supplied text, or the engine is mid-shutdown). When that
        // happens the UtteranceProgressListener never fires any
        // callback, so the captured continuation would suspend
        // forever. Resolve the callback ourselves so the orchestrator
        // progresses even in the synchronous-error case.
        val status = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, uniqueId)
        Log.d(TAG, "speak engine.speak returned $status id=$uniqueId")
        if (status == TextToSpeech.ERROR) {
            Log.w(TAG, "speak synchronous ERROR, resolving callback id=$uniqueId")
            synchronized(this) {
                if (uniqueId == activeId) {
                    val cb = activeCallback
                    activeCallback = null
                    activeId = null
                    cb?.invoke(false)
                }
            }
        }
    }

    fun stop() {
        // Drain any in-flight callback so a coroutine awaiting this
        // utterance is released (with ok=false) rather than hanging.
        Log.d(TAG, "stop draining activeCallback activeId=$activeId")
        synchronized(this) {
            val cb = activeCallback
            activeCallback = null
            activeId = null
            cb?.invoke(false)
        }
        tts?.stop()
    }

    fun release() {
        synchronized(this) {
            activeCallback = null
            activeId = null
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        initFailed = false
    }
}
