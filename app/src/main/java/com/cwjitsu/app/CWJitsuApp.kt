package com.cwjitsu.app

import android.app.Application
import com.cwjitsu.app.audio.CwAudioEngine
import com.cwjitsu.app.data.SettingsRepository
import com.cwjitsu.app.service.SessionOrchestrator
import com.cwjitsu.app.service.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application class. Singleton container so we do not have to pull in a heavy DI framework.
 */
class CWJitsuApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(applicationContext) }
    val audioEngine: CwAudioEngine by lazy { CwAudioEngine() }
    val ttsManager: TtsManager by lazy { TtsManager(applicationContext) }

    // Global orchestrator that survives screen navigation as long as the app
    // process is alive. The user can keep a practice session running in the
    // background and switch between screens.
    private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val orchestrator: SessionOrchestrator by lazy {
        SessionOrchestrator(audioEngine, ttsManager, orchestratorScope)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Bring up the TTS engine eagerly so the first practice run doesn't lose its
        // first answer to a still-loading engine.
        ttsManager.init()
    }

    companion object {
        @Volatile
        lateinit var instance: CWJitsuApp
            private set
    }
}
