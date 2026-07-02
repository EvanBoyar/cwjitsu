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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwjitsu.app.CWJitsuApp
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
            // Spoken answers (global NATO toggle).
            Text(
                "Spoken answers",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Pick how the TTS announces characters and callsigns.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.natoSpokenAnswers,
                    onClick = {
                        scope.launch { app.settings.save(config.copy(natoSpokenAnswers = true)) }
                    },
                    label = { Text("NATO phonetics") },
                )
                FilterChip(
                    selected = !config.natoSpokenAnswers,
                    onClick = {
                        scope.launch { app.settings.save(config.copy(natoSpokenAnswers = false)) }
                    },
                    label = { Text("Letter's spoken name") },
                )
            }

            HorizontalDivider()

            // Update notifications: on launch the app compares the on-device
            // version against the latest tagged GitHub release and shows a
            // one-time alert if it's behind. This switch suppresses the check.
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

            HorizontalDivider()

            // Global practice config (WPM, frequency, repetitions, etc.).
            ConfigPanel(
                config = config,
                onConfigChange = { updated ->
                    scope.launch { app.settings.save(updated) }
                },
            )

            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
