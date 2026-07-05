package com.cwjitsu.app.ui.screens

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwjitsu.app.CWJitsuApp
import com.cwjitsu.app.R
import com.cwjitsu.app.data.NewsStatus
import com.cwjitsu.app.data.WordDictionary
import com.cwjitsu.app.practice.CallsignCountry
import com.cwjitsu.app.practice.CallsignRegistry
import com.cwjitsu.app.practice.CharFilter
import com.cwjitsu.app.practice.ContentKind
import com.cwjitsu.app.practice.ContentMixer
import com.cwjitsu.app.practice.MixedConfig
import com.cwjitsu.app.practice.ContentItem
import com.cwjitsu.app.practice.Morse
import com.cwjitsu.app.practice.NewsSource
import com.cwjitsu.app.practice.NewsSources
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.ProsignSpokenMode
import com.cwjitsu.app.service.ContentRegenerator
import com.cwjitsu.app.service.SessionOrchestrator
import com.cwjitsu.app.ui.components.PlaybackControls
import com.cwjitsu.app.ui.components.ToggleRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Home screen is the app's only practice surface. It hosts the
 * category toggles (the old "quick practice" tiles are gone - the tiles
 * were the same thing as the mixed-practice toggles) and the per-category
 * settings that only apply when a given category is enabled:
 *
 *  - **Call Signs** -> multi-select country chips
 *  - **Text**       -> a debounced, focus-loss-flushed source text field
 *
 * Categories with no per-category settings (Characters, Prosigns, Q-codes,
 * Words) are still togglable, and the global NATO / prosign mode toggles
 * live in the Settings screen.
 *
 * The session is continuous: the regenerator is called every round, and
 * the user pauses/resumes with the Play-Pause button (also available in
 * the media notification, alongside Previous and Next).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onPickSettings: () -> Unit) {
    val app = CWJitsuApp.instance
    val config by app.settings.configFlow.collectAsStateWithLifecycle(initialValue = PracticeConfig())
    val orchestrator = app.orchestrator
    val runnerState by orchestrator.runnerState.collectAsStateWithLifecycle()
    val isPaused by orchestrator.paused.collectAsStateWithLifecycle()
    val nowPlaying by orchestrator.nowPlaying.collectAsStateWithLifecycle()
    val newsStatus by app.news.status.collectAsStateWithLifecycle()
    val savedConfig by app.settings.mixedConfigFlow.collectAsStateWithLifecycle(initialValue = null)
    val effectiveConfig: MixedConfig = savedConfig ?: MixedConfig()
    // "Now playing" is hidden by default to avoid spoiling the answer.
    var showNowPlaying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // One-time alert when the launch-time GitHub check found a newer tagged
    // release than this build. Dismissing clears the checker's state, so it
    // won't re-appear until the next launch finds an update again. The check
    // itself can be turned off entirely in Settings.
    val updateInfo by app.updateChecker.available.collectAsStateWithLifecycle()
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { app.updateChecker.dismiss() },
            title = { Text("Update available") },
            text = {
                Text(
                    "CW Jitsu v${info.version} is out - this device is running " +
                        "v${com.cwjitsu.app.BuildConfig.VERSION_NAME}. " +
                        "You can turn this check off in Settings.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, info.url.toUri()))
                    app.updateChecker.dismiss()
                }) { Text("View release") }
            },
            dismissButton = {
                TextButton(onClick = { app.updateChecker.dismiss() }) { Text("Not now") }
            },
        )
    }

    // Toggles use the repository's atomic updateMixedConfig so that fast
    // taps on different chips compose correctly (no read-then-write race)
    // and so that an empty selection (e.g. deselecting the last category)
    // is preserved rather than being silently re-mapped to defaults.
    fun toggleKind(kind: ContentKind) {
        scope.launch {
            app.settings.updateMixedConfig { current ->
                val newKinds = if (kind in current.enabledKinds) {
                    current.enabledKinds - kind
                } else {
                    current.enabledKinds + kind
                }
                current.copy(enabledKinds = newKinds)
            }
        }
    }

    fun toggleCountry(country: String) {
        scope.launch {
            app.settings.updateMixedConfig { current ->
                val newCountries = if (country in current.callsignCountries) {
                    current.callsignCountries - country
                } else {
                    current.callsignCountries + country
                }
                current.copy(callsignCountries = newCountries)
            }
        }
    }

    fun setCountries(countries: Set<String>) {
        scope.launch {
            app.settings.updateMixedConfig { it.copy(callsignCountries = countries) }
        }
    }

    fun setCallsignRandomPrefix(enabled: Boolean) {
        scope.launch {
            app.settings.updateMixedConfig { it.copy(callsignRandomPrefix = enabled) }
        }
    }

    fun updateCharacters(transform: (Set<Char>) -> Set<Char>) {
        scope.launch {
            app.settings.updateMixedConfig { it.copy(characterSet = transform(it.characterSet)) }
        }
    }

    // The shared refresh rules (enabled-check, all-feeds download, rate
    // limit) live in CWJitsuApp.refreshNewsIfEnabled. force=true is for the
    // explicit Refresh button and newly-added feeds; automatic triggers
    // (panel shown) leave it false so they reuse a recent cache.
    fun refreshNews(force: Boolean = false) {
        scope.launch { app.refreshNewsIfEnabled(force) }
    }

    fun toggleNewsSource(id: String) {
        scope.launch {
            app.settings.updateMixedConfig { c ->
                val ns = if (id in c.enabledNewsSources) c.enabledNewsSources - id
                         else c.enabledNewsSources + id
                c.copy(enabledNewsSources = ns)
            }
            // No refresh needed: everything is already downloaded, and the
            // toggle takes effect at playback time.
        }
    }

    fun addCustomFeed(url: String) {
        val normalized = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (normalized.length <= "https://".length) return
        scope.launch {
            app.settings.updateMixedConfig { c ->
                if (normalized in c.customNewsFeeds) c
                else c.copy(
                    customNewsFeeds = c.customNewsFeeds + normalized,
                    // A freshly added feed starts enabled - adding it is the
                    // clearest possible signal the user wants to hear it.
                    enabledNewsSources = c.enabledNewsSources +
                        (NewsSource.CUSTOM_PREFIX + normalized),
                )
            }
            refreshNews(force = true)
        }
    }

    fun removeCustomFeed(url: String) {
        scope.launch {
            app.settings.updateMixedConfig { c ->
                c.copy(
                    customNewsFeeds = c.customNewsFeeds - url,
                    enabledNewsSources = c.enabledNewsSources -
                        (NewsSource.CUSTOM_PREFIX + url),
                )
            }
        }
    }

    fun setNewsNoRepeat(enabled: Boolean) {
        scope.launch { app.settings.updateMixedConfig { it.copy(newsNoRepeat = enabled) } }
    }

    fun setNewsCharFilter(filter: CharFilter) {
        scope.launch { app.settings.updateMixedConfig { it.copy(newsCharFilter = filter) } }
    }

    fun setTextSendWhole(whole: Boolean) {
        scope.launch { app.settings.updateMixedConfig { it.copy(textSendWhole = whole) } }
    }

    fun setTextCharFilter(filter: CharFilter) {
        scope.launch { app.settings.updateMixedConfig { it.copy(textCharFilter = filter) } }
    }

    fun setProsignsEnabled(enabled: Boolean) {
        scope.launch { app.settings.updateMixedConfig { it.copy(prosignsEnabled = enabled) } }
    }

    fun setQcodesEnabled(enabled: Boolean) {
        scope.launch { app.settings.updateMixedConfig { it.copy(qcodesEnabled = enabled) } }
    }

    fun setCallsignRandomSuffix(enabled: Boolean) {
        scope.launch {
            app.settings.updateMixedConfig { it.copy(callsignRandomSuffix = enabled) }
        }
    }

    val regenerator: ContentRegenerator = { cfg ->
        val current = app.settings.mixedConfigFlow.first() ?: MixedConfig()
        val words = WordDictionary.get(app)
        // Pull one headline per round from the offline-first news cache. The
        // shuffle-bag inside the repository guarantees no near-term repeats;
        // it returns null when nothing is cached (e.g. offline first run).
        val newsItem = if (ContentKind.NEWS in current.enabledKinds) {
            // Only the user's selected sources are eligible for playback,
            // even though the cache holds headlines from every feed.
            // Keyed by stable source id, not display name.
            val allowedIds = NewsSources
                .active(current.enabledNewsSources, current.customNewsFeeds)
                .mapTo(mutableSetOf()) { it.id }
            app.news.nextHeadline(allowedIds)?.let { h ->
                ContentItem(
                    // The character filter trims what gets keyed; the spoken
                    // answer keeps the original headline wording.
                    text = current.newsCharFilter.apply(sanitizeHeadline(h.title)),
                    spokenAnswer = h.title,
                    singleShot = current.newsNoRepeat,
                )
            }
        } else null
        ContentMixer.build(
            enabledKinds = current.enabledKinds,
            words = words,
            prosignMode = cfg.prosignSpokenMode,
            nato = cfg.natoSpokenAnswers,
            callsignCountries = current.callsignCountries,
            textSource = current.textSource,
            textSendWhole = current.textSendWhole,
            textCharFilter = current.textCharFilter,
            callsignRandomPrefix = current.callsignRandomPrefix,
            callsignRandomSuffix = current.callsignRandomSuffix,
            characterPool = current.characterSet,
            prosignsEnabled = current.prosignsEnabled,
            qcodesEnabled = current.qcodesEnabled,
            newsItem = newsItem,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.cwjicon),
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .padding(end = 8.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CW Jitsu", fontWeight = FontWeight.SemiBold)
                            Text(
                                "by NR8E",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        // Small build version to the right of the app
                        // name so the user can verify which build is
                        // running at a glance. Style kept deliberately
                        // tiny and low-contrast so it doesn't fight for
                        // attention with the title itself.
                        Text(
                            text = "v" + com.cwjitsu.app.BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                actions = {
                    // Heart -> the developer's Buy Me a Coffee page.
                    IconButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://buymeacoffee.com/elbow".toUri()),
                        )
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Support the developer",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    IconButton(onClick = { showNowPlaying = !showNowPlaying }) {
                        Icon(
                            imageVector = if (showNowPlaying) Icons.Filled.Visibility
                                          else Icons.Filled.VisibilityOff,
                            contentDescription = if (showNowPlaying) "Hide now playing"
                                                 else "Show now playing",
                            tint = if (showNowPlaying) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    IconButton(onClick = onPickSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            // Wrap the playback controls with the navigation-bars inset so
            // the Play/Stop button clears the Android home gesture pill.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                // "Now playing" is pinned directly above the controls so
                // it stays visible during a session instead of scrolling
                // off the bottom of the content column.
                if (showNowPlaying) {
                    PreviewPane(
                        nowPlaying = nowPlaying,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 12.dp,
                        ),
                    )
                }
                HorizontalDivider()
                val isRunning = runnerState == SessionOrchestrator.RunnerState.RUNNING
                PlaybackControls(
                    isRunning = isRunning,
                    isPaused = isPaused,
                    onPlayPause = {
                        when {
                            // Hand the orchestrator the live settings flow
                            // (not a snapshot) so mid-session edits apply
                            // without a stop/start.
                            !isRunning -> orchestrator.start(regenerator, app.settings.configFlow)
                            isPaused -> orchestrator.resume()
                            else -> orchestrator.pause()
                        }
                    },
                    onStop = { orchestrator.stop() },
                    onPrevious = { orchestrator.previous() },
                    onNext = { orchestrator.skip() },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Choose what to practise",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Tap a category to toggle. The session keeps going until you stop it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            // 2-column grid of large category cards. Cards are chunked
            // into pairs and laid out in Rows so this works inside the
            // parent's verticalScroll (a LazyVerticalGrid inside a
            // verticalScroll would need extra height accounting).
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ContentKind.entries.chunked(2).forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowItems.forEach { kind ->
                            CategoryCard(
                                kind = kind,
                                selected = kind in effectiveConfig.enabledKinds,
                                onToggle = { toggleKind(kind) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad the last row if it only has one item so the
                        // single card stays the same width as the others.
                        if (rowItems.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Per-category settings: which characters CHARACTERS draws from,
            // chosen on a keyboard-style grid of toggle keys.
            if (ContentKind.CHARACTERS in effectiveConfig.enabledKinds) {
                CharacterPicker(
                    selected = effectiveConfig.characterSet,
                    onToggle = { ch ->
                        updateCharacters { if (ch in it) it - ch else it + ch }
                    },
                    onSelectGroup = { group -> updateCharacters { it + group } },
                    onClearGroup = { group -> updateCharacters { it - group } },
                )
            }

            // Per-category settings for the combined Prosigns & Q-codes card:
            // choose which of the two to drill, plus the prosign spoken-answer
            // style (only relevant while prosigns are on).
            if (ContentKind.PROSIGNS_QCODES in effectiveConfig.enabledKinds) {
                Text(
                    "Prosigns & Q-codes",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Pick which of the two this category sends.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                if (!effectiveConfig.prosignsEnabled && !effectiveConfig.qcodesEnabled) {
                    Text(
                        "Both are off - this category will stay silent until you enable one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ToggleRow(
                    label = "Prosigns (AR, BT, SK...)",
                    checked = effectiveConfig.prosignsEnabled,
                    onCheckedChange = { setProsignsEnabled(it) },
                )
                ToggleRow(
                    label = "Q-codes (QTH, QSL...)",
                    checked = effectiveConfig.qcodesEnabled,
                    onCheckedChange = { setQcodesEnabled(it) },
                )

                // Prosign spoken-answer style, shown only while prosigns are on.
                if (effectiveConfig.prosignsEnabled) {
                    Text(
                        "Prosign spoken answer",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.prosignSpokenMode == ProsignSpokenMode.LITERAL,
                            onClick = {
                                scope.launch {
                                    app.settings.updateConfig { it.copy(prosignSpokenMode = ProsignSpokenMode.LITERAL) }
                                }
                            },
                            label = { Text("Literal (\"A S\")") },
                        )
                        FilterChip(
                            selected = config.prosignSpokenMode == ProsignSpokenMode.MEANING,
                            onClick = {
                                scope.launch {
                                    app.settings.updateConfig { it.copy(prosignSpokenMode = ProsignSpokenMode.MEANING) }
                                }
                            },
                            label = { Text("Meaning (\"wait\")") },
                        )
                    }
                }
            }

            // Per-category settings: News sources + offline cache status.
            if (ContentKind.NEWS in effectiveConfig.enabledKinds) {
                NewsSettings(
                    status = newsStatus,
                    enabledSources = effectiveConfig.enabledNewsSources,
                    customFeeds = effectiveConfig.customNewsFeeds,
                    noRepeat = effectiveConfig.newsNoRepeat,
                    charFilter = effectiveConfig.newsCharFilter,
                    onToggleSource = { toggleNewsSource(it) },
                    onAddFeed = { addCustomFeed(it) },
                    onRemoveFeed = { removeCustomFeed(it) },
                    onSetNoRepeat = { setNewsNoRepeat(it) },
                    onSetCharFilter = { setNewsCharFilter(it) },
                    onRefresh = { force -> refreshNews(force) },
                )
            }

            // Per-category settings: regions + /prefix + /suffix for CALLSIGNS.
            // With ~130 countries in the registry the full chip list is too
            // long to render inline, so the inline area shows a compact
            // summary + quick-pick chips for the most common choices
            // (US and Canada) + a Manage button that opens a full-screen
            // dialog with search and continent grouping.
            if (ContentKind.CALLSIGNS in effectiveConfig.enabledKinds) {
                var showCountryDialog by remember { mutableStateOf(false) }

                Text(
                    "Callsigns regions",
                    style = MaterialTheme.typography.titleMedium,
                )

                // Summary + Manage button. We also surface the first few selected
                // country names here so the user can see at a glance which
                // regions are active without opening the dialog.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val count = effectiveConfig.callsignCountries.size
                    Text(
                        text = "$count ${if (count == 1) "country" else "countries"} selected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    TextButton(onClick = { showCountryDialog = true }) {
                        Text("Manage all")
                    }
                }
                if (effectiveConfig.callsignCountries.isNotEmpty()) {
                    val names = effectiveConfig.callsignCountries.sorted()
                    val previewCount = 3
                    val preview = names.take(previewCount)
                    val overflow = names.size - previewCount
                    Text(
                        text = buildString {
                            append(preview.joinToString(", "))
                            if (overflow > 0) append("  +$overflow more")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }

                // Quick-pick chips for the most common regions so the user
                // can drill on USA / Canada with one tap without opening
                // the dialog.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = "United States" in effectiveConfig.callsignCountries,
                        onClick = { toggleCountry("United States") },
                        label = { Text("United States") },
                    )
                    FilterChip(
                        selected = "Canada" in effectiveConfig.callsignCountries,
                        onClick = { toggleCountry("Canada") },
                        label = { Text("Canada") },
                    )
                }

                // Decoration is two separate toggles so the user can pick
                // exactly which side is occasionally present. Each side
                // is independently rolled at 25% when its toggle is on -
                // the listener hears mostly plain callsigns with the
                // toggled side appearing occasionally.
                ToggleRow(
                    label = "Random /prefix (occasionally)",
                    checked = effectiveConfig.callsignRandomPrefix,
                    onCheckedChange = { setCallsignRandomPrefix(it) },
                )
                Text(
                    "Adds a host-country /prefix (such as W1/, VE3/, JA/) to a random quarter of generated callsigns. Off = no prefix is added.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                ToggleRow(
                    label = "Random /suffix (occasionally)",
                    checked = effectiveConfig.callsignRandomSuffix,
                    onCheckedChange = { setCallsignRandomSuffix(it) },
                )
                Text(
                    "Adds a portable-status /suffix (such as /M, /P, /QRP) to a random quarter of generated callsigns. Off = no suffix is added.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (showCountryDialog) {
                    CountryManagerDialog(
                        selectedCountries = effectiveConfig.callsignCountries,
                        onToggleCountry = { toggleCountry(it) },
                        onClear = { setCountries(emptySet()) },
                        onSelectAll = { setCountries(CallsignRegistry.names().toSet()) },
                        onDismiss = { showCountryDialog = false },
                    )
                }
            }

            // Per-category settings: source text for TEXT, plus how it is
            // sent (word-by-word vs whole) and which characters are keyed.
            if (ContentKind.TEXT in effectiveConfig.enabledKinds) {
                Text(
                    "Text source",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextSourceField(
                    savedConfig = savedConfig,
                    effectiveText = effectiveConfig.textSource,
                    app = app,
                )

                Text("Sending", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !effectiveConfig.textSendWhole,
                        onClick = { setTextSendWhole(false) },
                        label = { Text("Word by word") },
                    )
                    FilterChip(
                        selected = effectiveConfig.textSendWhole,
                        onClick = { setTextSendWhole(true) },
                        label = { Text("Whole text at once") },
                    )
                }
                Text(
                    "Word by word treats each word as its own item (answered and " +
                        "repeated individually). Whole text sends everything as one item.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )

                Text("Characters to send", style = MaterialTheme.typography.titleSmall)
                CharFilterChips(
                    selected = effectiveConfig.textCharFilter,
                    onSelect = { setTextCharFilter(it) },
                )
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryCard(
    kind: ContentKind,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                         else MaterialTheme.colorScheme.surface
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
    Card(
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        border = if (selected) null
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onToggle,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp),
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = kind.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = kind.label(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = kind.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun TextSourceField(
    savedConfig: MixedConfig?,
    effectiveText: String,
    app: CWJitsuApp,
) {
    // Local mirror of the saved text so typing is smooth and we don't
    // write to DataStore on every keystroke. The actual save is debounced
    // (250 ms) and also flushed on focus loss.
    //
    // The remember key (`savedConfig == null`) is `true` only on the very
    // first composition, while DataStore is still loading. Once it emits a
    // real value, the key becomes `false` and stays `false`, so subsequent
    // DataStore emissions cannot clobber in-flight typing.
    var localText by remember(savedConfig == null) {
        mutableStateOf(effectiveText)
    }
    val saveScope = rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun commitText(text: String, debounceMs: Long = 250) {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(debounceMs)
            // Atomic read-modify-write: a chip toggle (which uses the
            // same updateMixedConfig) running in between would otherwise
            // race with a saveMixedConfig that wrote the full old config
            // and clobbered the toggle.
            app.settings.updateMixedConfig { current ->
                if (text == current.textSource) current
                else current.copy(textSource = text)
            }
        }
    }

    OutlinedTextField(
        value = localText,
        onValueChange = { newValue ->
            localText = newValue
            commitText(newValue)
        },
        placeholder = { Text("Type or paste text to practise") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) {
                    commitText(localText, debounceMs = 0)
                }
            },
    )
}

/**
 * Keyboard-style character selector for the Characters category. Numbers,
 * letters (QWERTY), and punctuation/symbols each get a labeled section with
 * All / None shortcuts and a grid of toggle keys. A lit (amber) key is
 * included in practice; a hollow key is excluded.
 */
@Composable
private fun CharacterPicker(
    selected: Set<Char>,
    onToggle: (Char) -> Unit,
    onSelectGroup: (List<Char>) -> Unit,
    onClearGroup: (List<Char>) -> Unit,
) {
    // A 10-wide grid; shorter rows are centered so letters keep the familiar
    // staggered keyboard shape.
    val columns = 10
    val numberRow = ('1'..'9').toList() + '0'
    val letterRows = listOf(
        "QWERTYUIOP".toList(),
        "ASDFGHJKL".toList(),
        "ZXCVBNM".toList(),
    )
    val specialRows = Morse.specials.chunked(columns)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Characters", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Tap keys to choose what this category sends. ${selected.size} selected.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (selected.isEmpty()) {
            Text(
                "No characters selected - this category will stay silent until you pick some.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        CharGroupHeader("Numbers", Morse.digits, selected, onSelectGroup, onClearGroup)
        KeyRow(numberRow, columns, selected, onToggle)

        CharGroupHeader("Letters", Morse.letters, selected, onSelectGroup, onClearGroup)
        letterRows.forEach { KeyRow(it, columns, selected, onToggle) }

        CharGroupHeader("Punctuation & symbols", Morse.specials, selected, onSelectGroup, onClearGroup)
        specialRows.forEach { KeyRow(it, columns, selected, onToggle) }
    }
}

@Composable
private fun CharGroupHeader(
    title: String,
    group: List<Char>,
    selected: Set<Char>,
    onAll: (List<Char>) -> Unit,
    onNone: (List<Char>) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onAll(group) }, enabled = !group.all { it in selected }) {
            Text("All")
        }
        TextButton(onClick = { onNone(group) }, enabled = group.any { it in selected }) {
            Text("None")
        }
    }
}

/**
 * One row of character keys laid out on a [columns]-wide grid. Rows with
 * fewer than [columns] keys are centered with half-key padding so the
 * layout keeps a keyboard-like stagger.
 */
@Composable
private fun KeyRow(
    chars: List<Char>,
    columns: Int,
    selected: Set<Char>,
    onToggle: (Char) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val pad = (columns - chars.size) / 2f
        if (pad > 0f) Spacer(Modifier.weight(pad))
        chars.forEach { ch ->
            CharKey(
                label = ch.toString(),
                selected = ch in selected,
                onClick = { onToggle(ch) },
                modifier = Modifier.weight(1f),
            )
        }
        if (pad > 0f) Spacer(Modifier.weight(pad))
    }
}

@Composable
private fun CharKey(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * News category settings: offline-cache status + a refresh, the built-in
 * source toggles, and add/remove for custom RSS/Atom feeds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsSettings(
    status: NewsStatus,
    enabledSources: Set<String>,
    customFeeds: List<String>,
    noRepeat: Boolean,
    charFilter: CharFilter,
    onToggleSource: (String) -> Unit,
    onAddFeed: (String) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onSetNoRepeat: (Boolean) -> Unit,
    onSetCharFilter: (CharFilter) -> Unit,
    onRefresh: (force: Boolean) -> Unit,
) {
    // Warm the cache when the panel first appears (e.g. just after the user
    // enables News). Not forced: the repository skips it when the cache is
    // fresh, so screen-hopping doesn't re-download every feed. Fails fast
    // and harmlessly when offline.
    LaunchedEffect(Unit) { onRefresh(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("News", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (status.refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            TextButton(onClick = { onRefresh(true) }, enabled = !status.refreshing) { Text("Refresh") }
        }
        Text(
            text = newsStatusText(status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        // Headlines are long, so by default each one is sent once regardless
        // of the global repeat count. This switch overrides that just for news.
        ToggleRow(
            label = "Play each headline only once",
            checked = noRepeat,
            onCheckedChange = onSetNoRepeat,
        )
        Text(
            "Ignore the global repeat count for news as headlines are long.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Text("Characters to send", style = MaterialTheme.typography.titleSmall)
        CharFilterChips(selected = charFilter, onSelect = onSetCharFilter)
        Text(
            "Filtered-out characters are dropped from the code; the spoken " +
                "headline is unchanged.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Text("Sources", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NewsSources.BUILT_IN.forEach { src ->
                FilterChip(
                    selected = src.id in enabledSources,
                    onClick = { onToggleSource(src.id) },
                    label = { Text(src.name) },
                )
            }
        }

        Text("Custom feeds", style = MaterialTheme.typography.titleSmall)
        if (customFeeds.isEmpty()) {
            Text(
                "Add any RSS or Atom feed URL below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        } else {
            // Each custom feed gets the same select/deselect chip as the
            // built-in sources (keyed by its "custom:<url>" id), plus a
            // remove button to delete it entirely.
            customFeeds.forEach { feed ->
                val src = NewsSource.custom(feed)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = src.id in enabledSources,
                        onClick = { onToggleSource(src.id) },
                        label = { Text(src.name, maxLines = 1) },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveFeed(feed) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove feed")
                    }
                }
            }
        }
        var newFeed by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newFeed,
                onValueChange = { newFeed = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Add feed URL") },
                placeholder = { Text("example.com/rss") },
            )
            TextButton(
                onClick = { onAddFeed(newFeed); newFeed = "" },
                enabled = newFeed.isNotBlank(),
            ) { Text("Add") }
        }
    }
}

/**
 * Three-way chip row picking a [CharFilter]. Shared by the Text and News
 * per-category settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharFilterChips(
    selected: CharFilter,
    onSelect: (CharFilter) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CharFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        when (filter) {
                            CharFilter.EVERYTHING -> "Everything"
                            CharFilter.LETTERS_NUMBERS -> "A-Z 0-9"
                            CharFilter.LETTERS_NUMBERS_COMMON -> "A-Z 0-9 . , ? /"
                        }
                    )
                },
            )
        }
    }
}

private fun newsStatusText(s: NewsStatus): String {
    val updated = s.updatedAtMillis?.let { " · updated ${relativeTime(it)}" } ?: ""
    return when {
        s.refreshing -> "Refreshing…"
        s.message != null -> s.message + updated
        s.headlineCount == 0 -> "No headlines cached yet."
        else -> "${s.headlineCount} headlines cached$updated"
    }
}

private fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} h ago"
        else -> "${diff / 86_400_000L} d ago"
    }
}

/**
 * Normalize a headline's fancy punctuation to the ASCII forms the Morse table
 * knows, so smart quotes and em-dashes are sent rather than silently dropped.
 * Anything still unsupported is dropped later by the schedule builder.
 */
private fun sanitizeHeadline(title: String): String = title
    .replace('‘', '\'').replace('’', '\'')  // curly single quotes
    .replace('“', '"').replace('”', '"')    // curly double quotes
    .replace('\u2014', '-').replace('\u2013', '-')  // em / en dash
    .replace('…', ' ')                           // ellipsis
    .replace(Regex("\\s+"), " ")
    .trim()

@Composable
private fun PreviewPane(
    nowPlaying: SessionOrchestrator.NowPlaying,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp, max = 200.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Now playing",
                style = MaterialTheme.typography.titleLarge,
            )
            val current = nowPlaying.current
            if (current == null) {
                Text(
                    "Toggle a category and press Play.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                // Previous item sent, dimmed - shown for context so the
                // listener can confirm what they just copied.
                Text(
                    "Prev · " + (nowPlaying.previous?.text ?: "-"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 6.dp),
                )
                // Current item being sent, prominent.
                Text(
                    current.text,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Full-screen dialog that lets the user search, browse, and toggle
 * countries. Grouped by region (continent) to make scanning easy, with
 * a quick search field at the top that matches country name or any of
 * its prefix patterns. A "Clear" action wipes the selection; "All"
 * selects every country in the registry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryManagerDialog(
    selectedCountries: Set<String>,
    onToggleCountry: (String) -> Unit,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Group filtered results by region, keeping the registry's original
    // region order so continents don't reshuffle while the user types.
    // The build uses a mutable map for ergonomic appends and then widens
    // it to a read-only view; the cast is safe because we never mutate
    // the lists after this point.
    @Suppress("UNCHECKED_CAST")
    val groups: LinkedHashMap<String, List<CallsignCountry>> = remember(searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            CallsignRegistry.namesByRegion()
        } else {
            val out = LinkedHashMap<String, MutableList<CallsignCountry>>()
            for (c in CallsignRegistry.countries) {
                if (c.name.contains(q, ignoreCase = true) ||
                    c.templates.any { it.prefix.contains(q, ignoreCase = true) }
                ) {
                    out.getOrPut(c.region) { mutableListOf() }.add(c)
                }
            }
            out as LinkedHashMap<String, List<CallsignCountry>>
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Inside a Dialog the system insets are not always passed
        // through to the Scaffold's default contentWindowInsets, so we
        // zero it out and apply systemBarsPadding on the content
        // ourselves. This avoids double-padding at the bottom and
        // ensures the top bar clears the status bar.
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("Select regions") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        TextButton(onClick = onClear) { Text("Clear") }
                        TextButton(onClick = onSelectAll) { Text("All") }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by country or prefix") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (groups.isEmpty()) {
                    Text(
                        text = "No countries match \"$searchQuery\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(16.dp),
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    for ((region, countries) in groups) {
                        item(key = "header-$region") {
                            Text(
                                text = region,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items(
                            items = countries,
                            key = { country -> "country-$region-${country.name}" },
                        ) { country ->
                            val isSelected = country.name in selectedCountries
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleCountry(country.name) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = null,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = country.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    val prefixes = country.templates
                                        .joinToString(" \u00b7 ") {
                                            it.prefix.ifEmpty { "\u2014" }
                                        }
                                    Text(
                                        text = prefixes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

private fun ContentKind.label(): String = when (this) {
    ContentKind.CHARACTERS -> "Characters"
    ContentKind.PROSIGNS_QCODES -> "Prosigns & Q-codes"
    ContentKind.WORDS -> "Words"
    ContentKind.TEXT -> "Text"
    ContentKind.CALLSIGNS -> "Call Signs"
    ContentKind.NEWS -> "News"
}

private fun ContentKind.icon(): ImageVector = when (this) {
    ContentKind.CHARACTERS -> Icons.Filled.Translate
    ContentKind.PROSIGNS_QCODES -> Icons.Filled.Sos
    ContentKind.WORDS -> Icons.AutoMirrored.Filled.MenuBook
    ContentKind.TEXT -> Icons.AutoMirrored.Filled.Article
    ContentKind.CALLSIGNS -> Icons.Filled.Public
    ContentKind.NEWS -> Icons.Filled.Newspaper
}

private fun ContentKind.subtitle(): String = when (this) {
    ContentKind.CHARACTERS -> "A-Z, 0-9"
    ContentKind.PROSIGNS_QCODES -> "AR, QSL..."
    ContentKind.WORDS -> "English"
    ContentKind.TEXT -> "Your text"
    ContentKind.CALLSIGNS -> "By country"
    ContentKind.NEWS -> "Headlines"
}
