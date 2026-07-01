package com.cwjitsu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cwjitsu.app.practice.NoiseType
import com.cwjitsu.app.practice.PracticeConfig

/**
 * Reusable Compose panel that exposes the whole [PracticeConfig] to the user.
 * Each practice screen embeds one copy near the top, and the user can adjust the
 * common knobs without leaving the screen.
 */
@Composable
fun ConfigPanel(
    config: PracticeConfig,
    onConfigChange: (PracticeConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Practice Settings", style = MaterialTheme.typography.titleLarge)

        LabeledSlider(
            label = "Character speed (WPM)",
            value = config.characterWpm.toFloat(),
            valueRange = 5f..60f,
            steps = 54,
            valueLabel = "${config.characterWpm}",
            onValueChange = { onConfigChange(config.copy(characterWpm = it.toInt())) },
        )

        LabeledSlider(
            label = "Farnsworth speed (>= char WPM)",
            value = (config.farnsworthWpm ?: config.characterWpm).toFloat(),
            valueRange = config.characterWpm.toFloat()..60f,
            steps = 60 - config.characterWpm,
            valueLabel = config.farnsworthWpm?.toString() ?: "off",
            onValueChange = { onConfigChange(config.copy(farnsworthWpm = it.toInt())) },
        )

        LabeledSlider(
            label = "Repetitions",
            value = config.repetitions.toFloat(),
            valueRange = 1f..20f,
            steps = 18,
            valueLabel = "${config.repetitions}",
            onValueChange = { onConfigChange(config.copy(repetitions = it.toInt())) },
        )

        LabeledSlider(
            label = "Post-send pause (ms)",
            value = config.postSendPauseMs.toFloat(),
            valueRange = 0f..5000f,
            steps = 49,
            valueLabel = "${config.postSendPauseMs}ms",
            onValueChange = { onConfigChange(config.copy(postSendPauseMs = it.toLong())) },
        )

        LabeledSlider(
            label = "Answer delay (ms)",
            value = config.answerDelayMs.toFloat(),
            valueRange = 0f..5000f,
            steps = 49,
            valueLabel = "${config.answerDelayMs}ms",
            onValueChange = { onConfigChange(config.copy(answerDelayMs = it.toLong())) },
        )

        ToggleRow(
            label = "Speak the answer",
            checked = config.answerEnabled,
            onCheckedChange = { onConfigChange(config.copy(answerEnabled = it)) },
        )

        ToggleRow(
            label = "Courtesy tone after sequence",
            checked = config.courtesyToneEnabled,
            onCheckedChange = { onConfigChange(config.copy(courtesyToneEnabled = it)) },
        )

        LabeledSlider(
            label = "Tone frequency (Hz)",
            value = config.frequencyHz.toFloat(),
            valueRange = 300f..1500f,
            steps = 119,
            valueLabel = "${config.frequencyHz}Hz",
            onValueChange = { onConfigChange(config.copy(frequencyHz = it.toInt())) },
        )

        ToggleRow(
            label = "Randomize tone frequency",
            checked = config.randomizeFrequency,
            onCheckedChange = { onConfigChange(config.copy(randomizeFrequency = it)) },
        )

        if (config.randomizeFrequency) {
            LabeledSlider(
                label = "Random frequency min",
                value = config.frequencyMinHz.toFloat(),
                valueRange = 300f..1500f,
                steps = 119,
                valueLabel = "${config.frequencyMinHz}Hz",
                onValueChange = { onConfigChange(config.copy(frequencyMinHz = it.toInt())) },
            )
            LabeledSlider(
                label = "Random frequency max",
                value = config.frequencyMaxHz.toFloat(),
                valueRange = 300f..1500f,
                steps = 119,
                valueLabel = "${config.frequencyMaxHz}Hz",
                onValueChange = { onConfigChange(config.copy(frequencyMaxHz = it.toInt())) },
            )
        }

        LabeledSlider(
            label = "Master volume",
            value = config.masterVolume,
            valueRange = 0f..1f,
            steps = 99,
            valueLabel = "%.0f%%".format(config.masterVolume * 100),
            onValueChange = { onConfigChange(config.copy(masterVolume = it)) },
        )

        ToggleRow(
            label = "Tone fading (smooth dot/dash boundaries)",
            checked = config.toneFadingEnabled,
            onCheckedChange = { onConfigChange(config.copy(toneFadingEnabled = it)) },
        )

        ToggleRow(
            label = "Volume variation per character",
            checked = config.volumeVariationEnabled,
            onCheckedChange = { onConfigChange(config.copy(volumeVariationEnabled = it)) },
        )

        Text("Background noise", style = MaterialTheme.typography.titleLarge)
        for (t in NoiseType.entries) {
            NoiseRow(
                label = t.name,
                selected = config.noiseType == t,
                onSelect = { onConfigChange(config.copy(noiseType = t)) },
            )
        }

        LabeledSlider(
            label = "Noise volume",
            value = config.noiseVolume,
            valueRange = 0f..1f,
            steps = 99,
            valueLabel = "%.0f%%".format(config.noiseVolume * 100),
            onValueChange = { onConfigChange(config.copy(noiseVolume = it)) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NoiseRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = selected, onCheckedChange = { onSelect() })
    }
}
