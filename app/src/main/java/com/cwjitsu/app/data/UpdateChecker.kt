package com.cwjitsu.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release spotted on GitHub. [version] has no leading "v". */
data class UpdateInfo(val version: String, val url: String)

/** Outcome of an explicit [UpdateChecker.checkNow], shown inline in Settings. */
sealed interface CheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : CheckResult
    data object UpToDate : CheckResult
    data object Failed : CheckResult
}

/**
 * Checks GitHub for a newer tagged release than the running build. Runs once
 * per app launch (kicked from [com.cwjitsu.app.CWJitsuApp]) and only when the
 * user hasn't suppressed it in Settings. Failures are silent - the check is
 * best-effort and the app is used offline a lot.
 */
class UpdateChecker {

    companion object {
        private const val TAG = "CWJitsu/Update"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/EvanBoyar/cwjitsu/releases/latest"
        private const val RELEASES_PAGE = "https://github.com/EvanBoyar/cwjitsu/releases"
        private const val TIMEOUT_MS = 8000

        /**
         * True when [remote] is a strictly newer dotted version than
         * [current]. Tolerates a leading "v" and trailing non-numeric junk
         * (e.g. "v0.3.0-rc1" compares as 0.3.0).
         */
        fun isNewer(remote: String, current: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v")
                .substringBefore('-')
                .split('.')
                .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val r = parts(remote)
            val c = parts(current)
            for (i in 0 until maxOf(r.size, c.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = c.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }

    private val _available = MutableStateFlow<UpdateInfo?>(null)

    /** Non-null when a newer release exists; the UI shows a one-time alert. */
    val available: StateFlow<UpdateInfo?> = _available

    /**
     * The single check implementation, shared by the launch-time check
     * (which ignores the result - [available] drives the Home alert) and
     * the Settings "Check now" button (which shows the outcome inline).
     * A newer release always sets [available].
     */
    suspend fun checkNow(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CWJitsu/$currentVersion (Android)")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "update check HTTP ${conn.responseCode}")
                    return@runCatching CheckResult.Failed
                }
                val body = conn.inputStream.use { it.readBytes().decodeToString() }
                val o = JSONObject(body)
                val tag = o.optString("tag_name")
                if (tag.isNotBlank() && isNewer(tag, currentVersion)) {
                    val info = UpdateInfo(
                        version = tag.removePrefix("v"),
                        url = o.optString("html_url").ifBlank { RELEASES_PAGE },
                    )
                    _available.value = info
                    CheckResult.UpdateAvailable(info)
                } else {
                    CheckResult.UpToDate
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Log.w(TAG, "update check failed", it)
            CheckResult.Failed
        }
    }

    /** Clear the pending alert after the user has seen it. */
    fun dismiss() {
        _available.value = null
    }
}
