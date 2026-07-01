package com.cwjitsu.app.data

import android.content.Context
import java.io.BufferedReader

/**
 * Loads the bundled English word list (assets/words_alpha.txt).
 */
object WordDictionary {
    private var cached: List<String>? = null

    fun get(context: Context): List<String> {
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
