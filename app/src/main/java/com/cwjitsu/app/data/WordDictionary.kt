package com.cwjitsu.app.data

import android.content.Context
import java.io.BufferedReader

/**
 * Loads the bundled English word list (assets/words_alpha.txt).
 */
object WordDictionary {
    // Volatile + double-checked lock: the word list is ~370k entries, so
    // two concurrent first calls each parsing the asset is real wasted
    // work, and without the barrier a later caller on another thread has
    // no guarantee it ever sees the cached value.
    @Volatile
    private var cached: List<String>? = null

    fun get(context: Context): List<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val list = context.assets.open("words_alpha.txt")
                .bufferedReader()
                .use(BufferedReader::readLines)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            cached = list
            return list
        }
    }
}
