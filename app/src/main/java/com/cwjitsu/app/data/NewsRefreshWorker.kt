package com.cwjitsu.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cwjitsu.app.CWJitsuApp
import com.cwjitsu.app.practice.ContentKind
import com.cwjitsu.app.practice.MixedConfig
import com.cwjitsu.app.practice.NewsSources
import kotlinx.coroutines.flow.first

/**
 * Daily background refresh of the news cache so the first launch of the day -
 * often underground with no connection - already has fresh headlines waiting.
 *
 * Scheduled by [CWJitsuApp] with a CONNECTED network constraint, so it only
 * runs when there's a working connection and quietly does nothing otherwise.
 */
class NewsRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CWJitsuApp ?: return Result.success()
        val cfg = app.settings.mixedConfigFlow.first() ?: MixedConfig()
        // Only spend the user's data if they actually practise news.
        if (ContentKind.NEWS !in cfg.enabledKinds) return Result.success()
        // Download every feed, not just the enabled ones, so toggling a
        // source on later (possibly offline) already has content cached.
        app.news.refreshAndAwait(NewsSources.all(cfg.customNewsFeeds))
        return Result.success()
    }
}
