package com.cwjitsu.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cwjitsu.app.audio.CwAudioEngine
import com.cwjitsu.app.data.NewsRefreshWorker
import com.cwjitsu.app.data.NewsRepository
import com.cwjitsu.app.data.SettingsRepository
import com.cwjitsu.app.data.UpdateChecker
import com.cwjitsu.app.practice.ContentKind
import com.cwjitsu.app.practice.MixedConfig
import com.cwjitsu.app.practice.NewsSources
import com.cwjitsu.app.service.SessionOrchestrator
import com.cwjitsu.app.service.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application class. Singleton container so we do not have to pull in a heavy DI framework.
 */
class CWJitsuApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(applicationContext) }
    val audioEngine: CwAudioEngine by lazy { CwAudioEngine() }
    val ttsManager: TtsManager by lazy { TtsManager(applicationContext) }
    val news: NewsRepository by lazy { NewsRepository(applicationContext) }
    val updateChecker: UpdateChecker by lazy { UpdateChecker() }

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

        // If the user practises News, warm the headline cache in the background
        // on launch (typically on Wi-Fi before heading underground). The
        // NewsRepository already loaded any saved cache from disk, so this only
        // refreshes it; when offline the refresh fails fast and the cache stays.
        orchestratorScope.launch { refreshNewsIfEnabled() }

        // Once per launch: see if a newer tagged release exists on GitHub.
        // Suppressible from Settings; failures (e.g. offline) are silent.
        orchestratorScope.launch {
            if (settings.updateCheckEnabledFlow.first()) {
                updateChecker.checkNow(BuildConfig.VERSION_NAME)
            }
        }

        scheduleDailyNewsRefresh()
    }

    /**
     * Refresh the news cache if the News category is enabled. The single
     * entry point for every refresh trigger (app launch, the News panel,
     * the daily worker), so the shared rules live in one place: only spend
     * the user's data when they actually practise news, and download EVERY
     * feed - not just the enabled ones - so toggling a source on later
     * (possibly offline by then) already has its headlines cached.
     * Un-forced calls are additionally rate-limited by the repository.
     */
    suspend fun refreshNewsIfEnabled(force: Boolean = false) {
        val cfg = settings.mixedConfigFlow.first() ?: MixedConfig()
        if (ContentKind.NEWS !in cfg.enabledKinds) return
        news.refreshAndAwait(NewsSources.all(cfg.customNewsFeeds), force)
    }

    /**
     * Warm the news cache about once a day in the background (when a network
     * is available) so the first launch of the day already has fresh
     * headlines - even if that launch happens offline, underground. KEEP means
     * we don't reset the schedule on every process start.
     */
    private fun scheduleDailyNewsRefresh() {
        val request = PeriodicWorkRequestBuilder<NewsRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "news-daily-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        @Volatile
        lateinit var instance: CWJitsuApp
            private set
    }
}
