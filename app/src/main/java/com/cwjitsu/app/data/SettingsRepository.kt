package com.cwjitsu.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cwjitsu.app.practice.CallsignRegistry
import com.cwjitsu.app.practice.ContentKind
import com.cwjitsu.app.practice.MixedConfig
import com.cwjitsu.app.practice.Morse
import com.cwjitsu.app.practice.NoiseType
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.ProsignSpokenMode
import com.cwjitsu.app.practice.SloppyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "cwjitsu_prefs")

/**
 * Persists [PracticeConfig] and the user's [MixedConfig] to a DataStore and
 * exposes both as Flows so the UI can react to changes.
 */
class SettingsRepository(private val context: Context) {

    object Keys {
        val CHARACTER_WPM = intPreferencesKey("character_wpm")
        val FARNSWORTH_WPM = intPreferencesKey("farnsworth_wpm")
        val REPETITIONS = intPreferencesKey("repetitions")
        val POST_SEND_PAUSE_MS = longPreferencesKey("post_send_pause_ms")
        val ANSWER_DELAY_MS = longPreferencesKey("answer_delay_ms")
        val ANSWER_ENABLED = booleanPreferencesKey("answer_enabled")
        val COURTESY_TONE_ENABLED = booleanPreferencesKey("courtesy_tone_enabled")
        val COURTESY_TONE_MS = longPreferencesKey("courtesy_tone_ms")
        val FREQUENCY_HZ = intPreferencesKey("frequency_hz")
        val FREQUENCY_MIN_HZ = intPreferencesKey("frequency_min_hz")
        val FREQUENCY_MAX_HZ = intPreferencesKey("frequency_max_hz")
        val RANDOMIZE_FREQUENCY = booleanPreferencesKey("randomize_frequency")
        val REPLAY_AFTER_ANSWER = booleanPreferencesKey("replay_after_answer")
        val VOLUME_VARIATION_ENABLED = booleanPreferencesKey("volume_variation_enabled")
        val MASTER_VOLUME = floatPreferencesKey("master_volume")
        val NOISE_TYPE = stringPreferencesKey("noise_type")
        val NOISE_VOLUME = floatPreferencesKey("noise_volume")
        val SLOPPY_MODE = stringPreferencesKey("sloppy_mode")
        val MIXED_CONFIG_JSON = stringPreferencesKey("mixed_config_json")
        // Legacy key from the previous slot-based model. Kept here only so
        // we can clean it up on the next save. Old data is abandoned.
        val MIXED_SLOTS_JSON_LEGACY = stringPreferencesKey("mixed_slots_json")
        val PROSIGN_SPOKEN_MODE = stringPreferencesKey("prosign_spoken_mode")
        val NATO_SPOKEN_ANSWERS = booleanPreferencesKey("nato_spoken_answers")
    }

    val configFlow: Flow<PracticeConfig> = context.dataStore.data.map { p ->
        PracticeConfig(
            characterWpm = p[Keys.CHARACTER_WPM] ?: 18,
            farnsworthWpm = p[Keys.FARNSWORTH_WPM]?.takeIf { it > 0 },
            repetitions = p[Keys.REPETITIONS] ?: 3,
            postSendPauseMs = p[Keys.POST_SEND_PAUSE_MS] ?: 1500L,
            answerDelayMs = p[Keys.ANSWER_DELAY_MS] ?: 2000L,
            answerEnabled = p[Keys.ANSWER_ENABLED] ?: true,
            courtesyToneEnabled = p[Keys.COURTESY_TONE_ENABLED] ?: true,
            courtesyToneMs = p[Keys.COURTESY_TONE_MS] ?: 400L,
            frequencyHz = p[Keys.FREQUENCY_HZ] ?: 600,
            frequencyMinHz = p[Keys.FREQUENCY_MIN_HZ] ?: 500,
            frequencyMaxHz = p[Keys.FREQUENCY_MAX_HZ] ?: 800,
            randomizeFrequency = p[Keys.RANDOMIZE_FREQUENCY] ?: false,
            replayAfterAnswer = p[Keys.REPLAY_AFTER_ANSWER] ?: false,
            volumeVariationEnabled = p[Keys.VOLUME_VARIATION_ENABLED] ?: true,
            masterVolume = p[Keys.MASTER_VOLUME] ?: 0.85f,
            noiseType = NoiseType.entries.firstOrNull { it.name == p[Keys.NOISE_TYPE] } ?: NoiseType.NONE,
            noiseVolume = p[Keys.NOISE_VOLUME] ?: 0.0f,
            sloppyMode = SloppyMode.entries
                .firstOrNull { it.name == p[Keys.SLOPPY_MODE] }
                ?: SloppyMode.OFF,
            prosignSpokenMode = ProsignSpokenMode.entries
                .firstOrNull { it.name == p[Keys.PROSIGN_SPOKEN_MODE] }
                ?: ProsignSpokenMode.LITERAL,
            natoSpokenAnswers = p[Keys.NATO_SPOKEN_ANSWERS] ?: true,
        )
    }

    suspend fun save(config: PracticeConfig) {
        context.dataStore.edit { p ->
            p[Keys.CHARACTER_WPM] = config.characterWpm
            p[Keys.FARNSWORTH_WPM] = config.farnsworthWpm ?: 0
            p[Keys.REPETITIONS] = config.repetitions
            p[Keys.POST_SEND_PAUSE_MS] = config.postSendPauseMs
            p[Keys.ANSWER_DELAY_MS] = config.answerDelayMs
            p[Keys.ANSWER_ENABLED] = config.answerEnabled
            p[Keys.COURTESY_TONE_ENABLED] = config.courtesyToneEnabled
            p[Keys.COURTESY_TONE_MS] = config.courtesyToneMs
            p[Keys.FREQUENCY_HZ] = config.frequencyHz
            p[Keys.FREQUENCY_MIN_HZ] = config.frequencyMinHz
            p[Keys.FREQUENCY_MAX_HZ] = config.frequencyMaxHz
            p[Keys.RANDOMIZE_FREQUENCY] = config.randomizeFrequency
            p[Keys.REPLAY_AFTER_ANSWER] = config.replayAfterAnswer
            p[Keys.VOLUME_VARIATION_ENABLED] = config.volumeVariationEnabled
            p[Keys.MASTER_VOLUME] = config.masterVolume
            p[Keys.NOISE_TYPE] = config.noiseType.name
            p[Keys.NOISE_VOLUME] = config.noiseVolume
            p[Keys.SLOPPY_MODE] = config.sloppyMode.name
            p[Keys.PROSIGN_SPOKEN_MODE] = config.prosignSpokenMode.name
            p[Keys.NATO_SPOKEN_ANSWERS] = config.natoSpokenAnswers
        }
    }

    /**
     * The user's mixed-practice config. Emits `null` when nothing is saved
     * yet, so callers can fall back to a sensible default.
     */
    val mixedConfigFlow: Flow<MixedConfig?> = context.dataStore.data.map { p ->
        val json = p[Keys.MIXED_CONFIG_JSON] ?: return@map null
        runCatching { parseMixedConfig(json) }.getOrNull()
    }

    suspend fun saveMixedConfig(config: MixedConfig) {
        context.dataStore.edit { p ->
            p[Keys.MIXED_CONFIG_JSON] = serializeMixedConfig(config)
            // Drop the legacy slot data so we don't carry it forever.
            p.remove(Keys.MIXED_SLOTS_JSON_LEGACY)
        }
    }

    /**
     * Atomic read-modify-write of the saved [MixedConfig]. The [transform]
     * runs inside the [dataStore.edit] transaction, so concurrent callers
     * serialise correctly and never observe each other's stale snapshots.
     * Use this for every chip toggle so that a fast double-tap on two
     * different chips composes into the final state the user actually
     * requested, and so that an empty selection (e.g. deselecting the
     * last enabled category or country) is preserved verbatim instead of
     * being silently re-mapped to a default.
     */
    suspend fun updateMixedConfig(transform: (MixedConfig) -> MixedConfig) {
        context.dataStore.edit { p ->
            val json = p[Keys.MIXED_CONFIG_JSON]
            val current = if (json != null) {
                runCatching { parseMixedConfig(json) }.getOrNull() ?: MixedConfig()
            } else {
                MixedConfig()
            }
            p[Keys.MIXED_CONFIG_JSON] = serializeMixedConfig(transform(current))
            p.remove(Keys.MIXED_SLOTS_JSON_LEGACY)
        }
    }

    private fun serializeMixedConfig(config: MixedConfig): String {
        val o = JSONObject()
        val kinds = JSONArray()
        // Persist kinds in declaration order so the saved order is stable.
        for (kind in ContentKind.entries) {
            if (kind in config.enabledKinds) kinds.put(kind.name)
        }
        o.put("enabledKinds", kinds)
        // Countries: sorted so the saved order is stable.
        val countries = JSONArray()
        for (country in config.callsignCountries.sorted()) {
            countries.put(country)
        }
        o.put("callsignCountries", countries)
        o.put("textSource", config.textSource)
        // Independent toggles for occasional host-country /prefix and
        // portable-status /suffix. Older saved configs predate the
        // split toggle — see the parse side for the migration logic.
        o.put("callsignRandomPrefix", config.callsignRandomPrefix)
        o.put("callsignRandomSuffix", config.callsignRandomSuffix)
        // Selected characters for the Characters category, stored as a plain
        // string in canonical Morse order so the saved value is stable.
        val chars = Morse.characters.keys
            .filter { it in config.characterSet }
            .joinToString("")
        o.put("characterSet", chars)
        // Sub-toggles for the combined Prosigns & Q-codes category.
        o.put("prosignsEnabled", config.prosignsEnabled)
        o.put("qcodesEnabled", config.qcodesEnabled)
        return o.toString()
    }

    private fun parseMixedConfig(json: String): MixedConfig {
        val o = JSONObject(json)
        val kindsArr = o.optJSONArray("enabledKinds") ?: JSONArray()
        val rawKindNames = (0 until kindsArr.length()).map { kindsArr.optString(it) }
        // Legacy migration: PROSIGNS and QCODES used to be two separate
        // categories. They are now one combined PROSIGNS_QCODES card with
        // per-side sub-toggles, so map either legacy name onto the combined
        // kind (and, below, seed the sub-toggles from which ones were on).
        val hasLegacyProsigns = "PROSIGNS" in rawKindNames
        val hasLegacyQcodes = "QCODES" in rawKindNames
        val kinds = rawKindNames
            .mapNotNull { runCatching { ContentKind.valueOf(it) }.getOrNull() }
            .toMutableSet()
        if (hasLegacyProsigns || hasLegacyQcodes) kinds.add(ContentKind.PROSIGNS_QCODES)
        val countriesArr = o.optJSONArray("callsignCountries") ?: JSONArray()
        val countries = (0 until countriesArr.length())
            .mapNotNull { i -> countriesArr.optString(i).takeIf { it.isNotEmpty() } }
            .toSet()
        val text = o.optString("textSource")
        // Migration: a previous "combined" toggle stored decoration
        // choices under the single key `callsignRandomDecoration`.
        // If BOTH new per-side keys are absent, fall back to the
        // legacy value so users who toggled the old switch still see
        // decoration after the upgrade. As soon as the user touches
        // either new toggle, the legacy value is no longer read.
        val hasNewPrefix = o.has("callsignRandomPrefix")
        val hasNewSuffix = o.has("callsignRandomSuffix")
        val legacy = if (!hasNewPrefix && !hasNewSuffix) {
            o.optBoolean("callsignRandomDecoration", false)
        } else false
        // Characters: absent key means a config saved before this feature —
        // fall back to the default (letters + digits) so existing users keep
        // the previous behavior. A present-but-empty string is honored as a
        // deliberate "no characters" choice.
        val characterSet = if (o.has("characterSet")) {
            o.optString("characterSet").toSet()
        } else {
            MixedConfig.DEFAULT_CHARACTER_SET
        }
        // Prosigns/Q-codes sub-toggles. When the new keys are absent, seed
        // from the legacy category names if present (so a user who had only
        // Q-codes keeps only Q-codes), otherwise default both on.
        val prosignsEnabled = when {
            o.has("prosignsEnabled") -> o.optBoolean("prosignsEnabled", true)
            hasLegacyProsigns || hasLegacyQcodes -> hasLegacyProsigns
            else -> true
        }
        val qcodesEnabled = when {
            o.has("qcodesEnabled") -> o.optBoolean("qcodesEnabled", true)
            hasLegacyProsigns || hasLegacyQcodes -> hasLegacyQcodes
            else -> true
        }
        return MixedConfig(
            // An empty selection is honored as the user's intent. The
            // first-run default (DEFAULT_KINDS / DEFAULT_COUNTRIES) is
            // only used by [MixedConfig] itself when nothing has ever
            // been saved and the flow therefore emits null.
            enabledKinds = kinds,
            callsignCountries = countries,
            textSource = text,
            callsignRandomPrefix = if (hasNewPrefix)
                o.optBoolean("callsignRandomPrefix", false)
            else legacy,
            callsignRandomSuffix = if (hasNewSuffix)
                o.optBoolean("callsignRandomSuffix", false)
            else legacy,
            characterSet = characterSet,
            prosignsEnabled = prosignsEnabled,
            qcodesEnabled = qcodesEnabled,
        )
    }
}
