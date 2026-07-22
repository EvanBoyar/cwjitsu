package com.cwjitsu.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cwjitsu.app.practice.CallsignRegistry
import com.cwjitsu.app.practice.CharFilter
import com.cwjitsu.app.practice.ContentKind
import com.cwjitsu.app.practice.MixedConfig
import com.cwjitsu.app.practice.Morse
import com.cwjitsu.app.practice.NewsSource
import com.cwjitsu.app.practice.NoiseType
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.SpokenAnswerMode
import com.cwjitsu.app.practice.SloppyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "cwjitsu_prefs")

/**
 * Persists [PracticeConfig] and the user's [MixedConfig] to a DataStore and
 * exposes both as Flows so the UI can react to changes.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "CWJitsu/Settings"
    }

    object Keys {
        val CHARACTER_WPM = intPreferencesKey("character_wpm")
        val FARNSWORTH_WPM = intPreferencesKey("farnsworth_wpm")
        val SPEED_VARIABILITY_ENABLED = booleanPreferencesKey("speed_variability_enabled")
        val SPEED_VAR_PLUS_WPM = intPreferencesKey("speed_var_plus_wpm")
        val SPEED_VAR_MINUS_WPM = intPreferencesKey("speed_var_minus_wpm")
        val REPETITIONS = intPreferencesKey("repetitions")
        val POST_SEND_PAUSE_MS = longPreferencesKey("post_send_pause_ms")
        val ANSWER_DELAY_MS = longPreferencesKey("answer_delay_ms")
        val ANSWER_ENABLED = booleanPreferencesKey("answer_enabled")
        val COURTESY_TONE_ENABLED = booleanPreferencesKey("courtesy_tone_enabled")
        val FREQUENCY_HZ = intPreferencesKey("frequency_hz")
        val FREQUENCY_MIN_HZ = intPreferencesKey("frequency_min_hz")
        val FREQUENCY_MAX_HZ = intPreferencesKey("frequency_max_hz")
        val RANDOMIZE_FREQUENCY = booleanPreferencesKey("randomize_frequency")
        val REPLAY_AFTER_ANSWER = booleanPreferencesKey("replay_after_answer")
        val VOLUME_VARIATION_ENABLED = booleanPreferencesKey("volume_variation_enabled")
        val MASTER_VOLUME = floatPreferencesKey("master_volume")
        val TTS_VOLUME = floatPreferencesKey("tts_volume")
        val NOISE_TYPE = stringPreferencesKey("noise_type")
        val NOISE_VOLUME = floatPreferencesKey("noise_volume")
        val SLOPPY_MODE = stringPreferencesKey("sloppy_mode")
        val MIXED_CONFIG_JSON = stringPreferencesKey("mixed_config_json")
        // Legacy key from the previous slot-based model. Kept here only so
        // we can clean it up on the next save. Old data is abandoned.
        val MIXED_SLOTS_JSON_LEGACY = stringPreferencesKey("mixed_slots_json")
        // Replaces the old prosign-only "prosign_spoken_mode" key; the new
        // mode covers all shorthand (prosigns, Q-codes, abbreviations) and
        // defaults to BOTH, so old values are deliberately not migrated.
        val SHORTHAND_SPOKEN_MODE = stringPreferencesKey("shorthand_spoken_mode")
        val NATO_SPOKEN_ANSWERS = booleanPreferencesKey("nato_spoken_answers")
        val UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")
    }

    /** Whether to check GitHub for a newer release on launch. Default on. */
    val updateCheckEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { e -> Log.w(TAG, "updateCheckEnabledFlow read failed", e); emit(emptyPreferences()) }
        .map { p -> p[Keys.UPDATE_CHECK_ENABLED] ?: true }

    suspend fun setUpdateCheckEnabled(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.UPDATE_CHECK_ENABLED] = enabled }
    }

    /**
     * Rebuild a [PracticeConfig] from raw preferences, coercing every value
     * into its valid range. DataStore contents are external input (an older
     * build or interrupted write can leave out-of-range values), and
     * [PracticeConfig]'s init-block invariants would otherwise throw inside
     * the flow and crash every collector.
     */
    private fun configFrom(p: Preferences): PracticeConfig {
        val minHz = (p[Keys.FREQUENCY_MIN_HZ] ?: 500).coerceIn(300, 1500)
        val maxHz = (p[Keys.FREQUENCY_MAX_HZ] ?: 800).coerceIn(300, 1500)
        return PracticeConfig(
            characterWpm = (p[Keys.CHARACTER_WPM] ?: 18).coerceIn(5, 60),
            farnsworthWpm = p[Keys.FARNSWORTH_WPM]?.takeIf { it > 0 },
            speedVariabilityEnabled = p[Keys.SPEED_VARIABILITY_ENABLED] ?: false,
            speedVarPlusWpm = (p[Keys.SPEED_VAR_PLUS_WPM] ?: 3)
                .coerceIn(0, PracticeConfig.MAX_SPEED_VARIATION_WPM),
            speedVarMinusWpm = (p[Keys.SPEED_VAR_MINUS_WPM] ?: 3)
                .coerceIn(0, PracticeConfig.MAX_SPEED_VARIATION_WPM),
            repetitions = (p[Keys.REPETITIONS] ?: 3).coerceIn(1, 20),
            postSendPauseMs = (p[Keys.POST_SEND_PAUSE_MS] ?: 1500L).coerceAtLeast(0L),
            answerDelayMs = (p[Keys.ANSWER_DELAY_MS] ?: 2000L).coerceAtLeast(0L),
            answerEnabled = p[Keys.ANSWER_ENABLED] ?: true,
            courtesyToneEnabled = p[Keys.COURTESY_TONE_ENABLED] ?: true,
            frequencyHz = (p[Keys.FREQUENCY_HZ] ?: 600).coerceIn(300, 1500),
            frequencyMinHz = minOf(minHz, maxHz),
            frequencyMaxHz = maxOf(minHz, maxHz),
            randomizeFrequency = p[Keys.RANDOMIZE_FREQUENCY] ?: false,
            replayAfterAnswer = p[Keys.REPLAY_AFTER_ANSWER] ?: false,
            volumeVariationEnabled = p[Keys.VOLUME_VARIATION_ENABLED] ?: true,
            masterVolume = (p[Keys.MASTER_VOLUME] ?: 0.85f).coerceIn(0f, 1f),
            ttsVolume = (p[Keys.TTS_VOLUME] ?: 1.0f).coerceIn(0f, 1f),
            noiseType = NoiseType.entries.firstOrNull { it.name == p[Keys.NOISE_TYPE] } ?: NoiseType.NONE,
            noiseVolume = (p[Keys.NOISE_VOLUME] ?: 0.0f).coerceIn(0f, 1f),
            sloppyMode = SloppyMode.entries
                .firstOrNull { it.name == p[Keys.SLOPPY_MODE] }
                ?: SloppyMode.OFF,
            shorthandSpokenMode = SpokenAnswerMode.entries
                .firstOrNull { it.name == p[Keys.SHORTHAND_SPOKEN_MODE] }
                ?: SpokenAnswerMode.BOTH,
            natoSpokenAnswers = p[Keys.NATO_SPOKEN_ANSWERS] ?: true,
        )
    }

    private fun writeConfig(p: MutablePreferences, config: PracticeConfig) {
        p[Keys.CHARACTER_WPM] = config.characterWpm
        p[Keys.FARNSWORTH_WPM] = config.farnsworthWpm ?: 0
        p[Keys.SPEED_VARIABILITY_ENABLED] = config.speedVariabilityEnabled
        p[Keys.SPEED_VAR_PLUS_WPM] = config.speedVarPlusWpm
        p[Keys.SPEED_VAR_MINUS_WPM] = config.speedVarMinusWpm
        p[Keys.REPETITIONS] = config.repetitions
        p[Keys.POST_SEND_PAUSE_MS] = config.postSendPauseMs
        p[Keys.ANSWER_DELAY_MS] = config.answerDelayMs
        p[Keys.ANSWER_ENABLED] = config.answerEnabled
        p[Keys.COURTESY_TONE_ENABLED] = config.courtesyToneEnabled
        p[Keys.FREQUENCY_HZ] = config.frequencyHz
        p[Keys.FREQUENCY_MIN_HZ] = config.frequencyMinHz
        p[Keys.FREQUENCY_MAX_HZ] = config.frequencyMaxHz
        p[Keys.RANDOMIZE_FREQUENCY] = config.randomizeFrequency
        p[Keys.REPLAY_AFTER_ANSWER] = config.replayAfterAnswer
        p[Keys.VOLUME_VARIATION_ENABLED] = config.volumeVariationEnabled
        p[Keys.MASTER_VOLUME] = config.masterVolume
        p[Keys.TTS_VOLUME] = config.ttsVolume
        p[Keys.NOISE_TYPE] = config.noiseType.name
        p[Keys.NOISE_VOLUME] = config.noiseVolume
        p[Keys.SLOPPY_MODE] = config.sloppyMode.name
        p[Keys.SHORTHAND_SPOKEN_MODE] = config.shorthandSpokenMode.name
        p[Keys.NATO_SPOKEN_ANSWERS] = config.natoSpokenAnswers
    }

    val configFlow: Flow<PracticeConfig> = context.dataStore.data
        .catch { e ->
            // A failed DataStore read must never crash collectors (the UI
            // and the running session both collect this); log and fall back
            // to defaults instead.
            Log.w(TAG, "configFlow read failed", e)
            emit(emptyPreferences())
        }
        .map { p -> configFrom(p) }

    /**
     * Atomic read-modify-write of the saved [PracticeConfig], mirroring
     * [updateMixedConfig]. Every UI edit goes through here: a full-object
     * save built from a collected UI snapshot could clobber a concurrent
     * edit from another screen - or, before DataStore's first emission,
     * write constructor defaults over every saved setting.
     */
    suspend fun updateConfig(transform: (PracticeConfig) -> PracticeConfig) {
        context.dataStore.edit { p -> writeConfig(p, transform(configFrom(p))) }
    }

    /**
     * The user's mixed-practice config. Emits `null` when nothing is saved
     * yet, so callers can fall back to a sensible default.
     */
    val mixedConfigFlow: Flow<MixedConfig?> = context.dataStore.data
        .catch { e -> Log.w(TAG, "mixedConfigFlow read failed", e); emit(emptyPreferences()) }
        .map { p ->
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
        o.put("textSendWhole", config.textSendWhole)
        o.put("textCharFilter", config.textCharFilter.name)
        o.put("newsCharFilter", config.newsCharFilter.name)
        // Independent toggles for occasional host-country /prefix and
        // portable-status /suffix. Older saved configs predate the
        // split toggle - see the parse side for the migration logic.
        o.put("callsignRandomPrefix", config.callsignRandomPrefix)
        o.put("callsignRandomSuffix", config.callsignRandomSuffix)
        // Core callsign length bounds (prefix + digit + suffix, no '/').
        o.put("callsignMinLength", config.callsignMinLength)
        o.put("callsignMaxLength", config.callsignMaxLength)
        // Selected characters for the Characters category, stored as a plain
        // string in canonical Morse order so the saved value is stable.
        val chars = Morse.characters.keys
            .filter { it in config.characterSet }
            .joinToString("")
        o.put("characterSet", chars)
        // Groups mode for the Characters category + its size bounds.
        o.put("characterGroupsEnabled", config.characterGroupsEnabled)
        o.put("characterGroupMin", config.characterGroupMin)
        o.put("characterGroupMax", config.characterGroupMax)
        // Sub-toggles for the combined Prosigns & Q-codes category.
        o.put("prosignsEnabled", config.prosignsEnabled)
        o.put("qcodesEnabled", config.qcodesEnabled)
        o.put("abbreviationsEnabled", config.abbreviationsEnabled)
        // News source selection.
        val sources = JSONArray()
        for (s in config.enabledNewsSources.sorted()) sources.put(s)
        o.put("enabledNewsSources", sources)
        val feeds = JSONArray()
        for (f in config.customNewsFeeds) feeds.put(f)
        o.put("customNewsFeeds", feeds)
        // Marks that enabledNewsSources also covers custom feeds (by their
        // "custom:<url>" ids). Configs saved before this flag existed treated
        // custom feeds as always-on - see the parse-side migration.
        o.put("newsSelectV2", true)
        o.put("newsNoRepeat", config.newsNoRepeat)
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
        // Absent keys (configs saved before these settings existed) fall back
        // to the previous behavior: word-by-word, no character filtering.
        val textSendWhole = o.optBoolean("textSendWhole", false)
        val textCharFilter = CharFilter.entries
            .firstOrNull { it.name == o.optString("textCharFilter") }
            ?: CharFilter.EVERYTHING
        val newsCharFilter = CharFilter.entries
            .firstOrNull { it.name == o.optString("newsCharFilter") }
            ?: CharFilter.EVERYTHING
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
        // Characters: absent key means a config saved before this feature -
        // fall back to the default (letters + digits) so existing users keep
        // the previous behavior. A present-but-empty string is honored as a
        // deliberate "no characters" choice.
        val characterSet = if (o.has("characterSet")) {
            o.optString("characterSet").toSet()
        } else {
            MixedConfig.DEFAULT_CHARACTER_SET
        }
        // Character groups: absent keys (older configs) fall back to the
        // defaults - groups off, sizes from the shared range. Values are
        // coerced so a bad save can never produce min > max.
        val groupRange = MixedConfig.CHARACTER_GROUP_RANGE
        val characterGroupsEnabled = o.optBoolean("characterGroupsEnabled", false)
        val characterGroupMin = o.optInt("characterGroupMin", MixedConfig().characterGroupMin)
            .coerceIn(groupRange.first, groupRange.last)
        val characterGroupMax = o.optInt("characterGroupMax", MixedConfig().characterGroupMax)
            .coerceIn(characterGroupMin, groupRange.last)
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
        // Abbreviations arrived after the combined card; absent key means a
        // config saved before the feature - default on so it's discoverable.
        val abbreviationsEnabled = o.optBoolean("abbreviationsEnabled", true)
        // News sources: absent means a pre-News config - seed the defaults.
        val enabledNewsSources = if (o.has("enabledNewsSources")) {
            val a = o.optJSONArray("enabledNewsSources") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
        } else {
            MixedConfig.DEFAULT_NEWS_SOURCES
        }
        val customFeedsArr = o.optJSONArray("customNewsFeeds") ?: JSONArray()
        val customNewsFeeds = (0 until customFeedsArr.length())
            .mapNotNull { customFeedsArr.optString(it).takeIf { s -> s.isNotEmpty() } }
        // Migration: before newsSelectV2, custom feeds were always-on and
        // enabledNewsSources only covered built-ins. Seed every existing
        // custom feed's id as enabled so behavior doesn't change on upgrade;
        // the flag is written on every save, so this runs exactly once.
        val migratedNewsSources = if (o.optBoolean("newsSelectV2", false)) {
            enabledNewsSources
        } else {
            enabledNewsSources + customNewsFeeds.map { NewsSource.CUSTOM_PREFIX + it }
        }
        val newsNoRepeat = o.optBoolean("newsNoRepeat", true)
        // Callsign length bounds: absent keys (older configs) fall back to
        // the full range, i.e. the previous unfiltered behavior. Values are
        // coerced so a bad save can never produce min > max.
        val lengthRange = MixedConfig.CALLSIGN_LENGTH_RANGE
        val callsignMinLength = o.optInt("callsignMinLength", lengthRange.first)
            .coerceIn(lengthRange.first, lengthRange.last)
        val callsignMaxLength = o.optInt("callsignMaxLength", lengthRange.last)
            .coerceIn(callsignMinLength, lengthRange.last)
        return MixedConfig(
            // An empty selection is honored as the user's intent. The
            // first-run default (DEFAULT_KINDS / DEFAULT_COUNTRIES) is
            // only used by [MixedConfig] itself when nothing has ever
            // been saved and the flow therefore emits null.
            enabledKinds = kinds,
            callsignCountries = countries,
            textSource = text,
            textSendWhole = textSendWhole,
            textCharFilter = textCharFilter,
            newsCharFilter = newsCharFilter,
            callsignRandomPrefix = if (hasNewPrefix)
                o.optBoolean("callsignRandomPrefix", false)
            else legacy,
            callsignRandomSuffix = if (hasNewSuffix)
                o.optBoolean("callsignRandomSuffix", false)
            else legacy,
            callsignMinLength = callsignMinLength,
            callsignMaxLength = callsignMaxLength,
            characterSet = characterSet,
            characterGroupsEnabled = characterGroupsEnabled,
            characterGroupMin = characterGroupMin,
            characterGroupMax = characterGroupMax,
            prosignsEnabled = prosignsEnabled,
            qcodesEnabled = qcodesEnabled,
            abbreviationsEnabled = abbreviationsEnabled,
            enabledNewsSources = migratedNewsSources,
            customNewsFeeds = customNewsFeeds,
            newsNoRepeat = newsNoRepeat,
        )
    }
}
