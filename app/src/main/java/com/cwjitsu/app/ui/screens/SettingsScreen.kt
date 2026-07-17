package com.cwjitsu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwjitsu.app.BuildConfig
import com.cwjitsu.app.CWJitsuApp
import com.cwjitsu.app.data.CheckResult
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.ui.components.ConfigPanel
import com.cwjitsu.app.ui.theme.cwSwitchColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = CWJitsuApp.instance
    val config by app.settings.configFlow.collectAsStateWithLifecycle(initialValue = PracticeConfig())
    val updateCheckEnabled by app.settings.updateCheckEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Global practice config (WPM, frequency, spoken answers, etc.).
            // Edits arrive as transforms and are applied atomically against
            // the stored config, so a stale UI snapshot can never clobber
            // other settings.
            ConfigPanel(
                config = config,
                onUpdate = { transform ->
                    scope.launch { app.settings.updateConfig(transform) }
                },
            )

            HorizontalDivider()

            // Update notifications: on launch the app compares the on-device
            // version against the latest tagged GitHub release and shows a
            // one-time alert if it's behind. This switch suppresses the
            // check; the button runs one on demand and reports inline.
            Text(
                "Updates",
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Check for updates on launch",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = updateCheckEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { app.settings.setUpdateCheckEnabled(enabled) }
                    },
                    colors = cwSwitchColors(),
                )
            }
            Text(
                "Alerts you when the latest GitHub release is newer than this build.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            UpdateCheckNowRow(
                onCheck = { app.updateChecker.checkNow(BuildConfig.VERSION_NAME) },
            )

            HorizontalDivider()

            // Content disclaimer: the practice material mirrors on-air
            // convention, not the author's views. Wording approved by the
            // author; change only with their sign-off.
            Text(
                "The terminology, abbreviations, countries, and territories " +
                    "in this app are included solely for practice realism. " +
                    "They reflect what is conventionally heard on the air, " +
                    "including some dated expressions, and their inclusion " +
                    "or omission does not represent an endorsement, " +
                    "rejection, or position of any kind by the author. " +
                    "Country entries are organized by radio callsign " +
                    "allocation rather than political geography, so some " +
                    "listings are territories or call areas rather than " +
                    "sovereign states.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/**
 * "Check now" button with an inline outcome ("up to date" / "vX available" /
 * failed). It shares the fetch with the launch-time check, so a found update
 * also arms the Home-screen alert with its download link.
 */
@Composable
private fun UpdateCheckNowRow(onCheck: suspend () -> CheckResult) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CheckResult?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    checking = true
                    result = onCheck()
                    checking = false
                }
            },
            enabled = !checking,
        ) { Text("Check now") }

        val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        if (checking) {
            Text("Checking…", style = MaterialTheme.typography.bodyMedium, color = muted)
        } else when (val r = result) {
            null -> {}
            CheckResult.UpToDate -> Text(
                "Up to date (v${BuildConfig.VERSION_NAME})",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            CheckResult.Failed -> Text(
                "Check failed — are you online?",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            is CheckResult.UpdateAvailable -> {
                Text(
                    "v${r.info.version} available",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, r.info.url.toUri()))
                }) { Text("View release") }
            }
        }
    }
}
