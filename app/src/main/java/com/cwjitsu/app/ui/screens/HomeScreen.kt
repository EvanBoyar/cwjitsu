package com.cwjitsu.app.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwjitsu.app.CWJitsuApp
import com.cwjitsu.app.R
import com.cwjitsu.app.data.WordDictionary
import com.cwjitsu.app.practice.CallsignCountry
import com.cwjitsu.app.practice.CallsignRegistry
import com.cwjitsu.app.practice.ContentKind
import com.cwjitsu.app.practice.ContentMixer
import com.cwjitsu.app.practice.MixedConfig
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.ProsignSpokenMode
import com.cwjitsu.app.service.ContentRegenerator
import com.cwjitsu.app.service.SessionOrchestrator
import com.cwjitsu.app.ui.theme.cwSwitchColors
import com.cwjitsu.app.ui.components.PlaybackControls
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Home screen is the app's only practice surface. It hosts the
 * category toggles (the old "quick practice" tiles are gone — the tiles
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
 * the user stops by tapping Stop.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onPickSettings: () -> Unit) {
    val app = CWJitsuApp.instance
    val config by app.settings.configFlow.collectAsStateWithLifecycle(initialValue = PracticeConfig())
    val orchestrator = app.orchestrator
    val runnerState by orchestrator.runnerState.collectAsStateWithLifecycle()
    val nowPlaying by orchestrator.nowPlaying.collectAsStateWithLifecycle()
    val savedConfig by app.settings.mixedConfigFlow.collectAsStateWithLifecycle(initialValue = null)
    val effectiveConfig: MixedConfig = savedConfig ?: MixedConfig()
    // "Now playing" is hidden by default to avoid spoiling the answer.
    var showNowPlaying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    fun setCallsignRandomSuffix(enabled: Boolean) {
        scope.launch {
            app.settings.updateMixedConfig { it.copy(callsignRandomSuffix = enabled) }
        }
    }

    val regenerator: ContentRegenerator = { cfg ->
        val current = app.settings.mixedConfigFlow.first() ?: MixedConfig()
        val words = WordDictionary.get(app)
        ContentMixer.build(
            enabledKinds = current.enabledKinds,
            words = words,
            prosignMode = cfg.prosignSpokenMode,
            nato = cfg.natoSpokenAnswers,
            callsignCountries = current.callsignCountries,
            textSource = current.textSource,
            callsignRandomPrefix = current.callsignRandomPrefix,
            callsignRandomSuffix = current.callsignRandomSuffix,
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
                                "Morse practice",
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
                PlaybackControls(
                    isRunning = runnerState == SessionOrchestrator.RunnerState.RUNNING,
                    onPlay = { orchestrator.start(regenerator, config) },
                    onStop = { orchestrator.stop() },
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

            // Per-category settings: prosign spoken answer (Literal vs Meaning) for PROSIGNS.
            if (ContentKind.PROSIGNS in effectiveConfig.enabledKinds) {
                Text(
                    "Prosign spoken answer",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "How prosigns are read aloud.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.prosignSpokenMode == ProsignSpokenMode.LITERAL,
                        onClick = {
                            scope.launch {
                                app.settings.save(config.copy(prosignSpokenMode = ProsignSpokenMode.LITERAL))
                            }
                        },
                        label = { Text("Literal (\"A S\")") },
                    )
                    FilterChip(
                        selected = config.prosignSpokenMode == ProsignSpokenMode.MEANING,
                        onClick = {
                            scope.launch {
                                app.settings.save(config.copy(prosignSpokenMode = ProsignSpokenMode.MEANING))
                            }
                        },
                        label = { Text("Meaning (\"wait\")") },
                    )
                }
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
                // is independently rolled at 25% when its toggle is on —
                // the listener hears mostly plain callsigns with the
                // toggled side appearing occasionally. Replaces the
                // previous combined toggle (and the /prefix + /suffix
                // dropdowns before that).
                //
                // Inlined here (rather than reusing ToggleRow from
                // ConfigPanel) so HomeScreen stays self-contained and does
                // not have to know about / import a private composable
                // from a sibling package.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Random /prefix (occasionally)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = effectiveConfig.callsignRandomPrefix,
                        onCheckedChange = { setCallsignRandomPrefix(it) },
                        colors = cwSwitchColors(),
                    )
                }
                Text(
                    "Adds a host-country /prefix (such as W1/, VE3/, JA/) to a random quarter of generated callsigns. Off = no prefix is added.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Random /suffix (occasionally)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = effectiveConfig.callsignRandomSuffix,
                        onCheckedChange = { setCallsignRandomSuffix(it) },
                        colors = cwSwitchColors(),
                    )
                }
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

            // Per-category settings: source text for TEXT.
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
                // Previous item sent, dimmed — shown for context so the
                // listener can confirm what they just copied.
                Text(
                    "Prev · " + (nowPlaying.previous?.text ?: "—"),
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
    ContentKind.PROSIGNS -> "Prosigns"
    ContentKind.QCODES -> "Q-codes"
    ContentKind.WORDS -> "Words"
    ContentKind.TEXT -> "Text"
    ContentKind.CALLSIGNS -> "Call Signs"
}

private fun ContentKind.icon(): ImageVector = when (this) {
    ContentKind.CHARACTERS -> Icons.Filled.TextFields
    ContentKind.PROSIGNS -> Icons.AutoMirrored.Filled.MergeType
    ContentKind.QCODES -> Icons.Filled.QuestionAnswer
    ContentKind.WORDS -> Icons.Filled.Translate
    ContentKind.TEXT -> Icons.AutoMirrored.Filled.Article
    ContentKind.CALLSIGNS -> Icons.Filled.SettingsInputAntenna
}

private fun ContentKind.subtitle(): String = when (this) {
    ContentKind.CHARACTERS -> "A-Z, 0-9"
    ContentKind.PROSIGNS -> "AR, BT, SK..."
    ContentKind.QCODES -> "QTH, QSL..."
    ContentKind.WORDS -> "English"
    ContentKind.TEXT -> "Your text"
    ContentKind.CALLSIGNS -> "By country"
}
