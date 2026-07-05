package com.cwjitsu.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cwjitsu.app.CWJitsuApp

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
        // force=true: the daily refresh is the offline-first backstop and
        // should never be skipped by the short-interval rate limit.
        app.refreshNewsIfEnabled(force = true)
        return Result.success()
    }
}
