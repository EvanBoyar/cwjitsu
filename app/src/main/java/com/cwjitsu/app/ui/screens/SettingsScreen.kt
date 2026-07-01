package com.cwjitsu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwjitsu.app.CWJitsuApp
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.ui.components.ConfigPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = CWJitsuApp.instance
    val config by app.settings.configFlow.collectAsStateWithLifecycle(initialValue = PracticeConfig())
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
