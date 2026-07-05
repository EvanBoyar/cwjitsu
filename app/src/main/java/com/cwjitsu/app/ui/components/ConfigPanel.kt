package com.cwjitsu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cwjitsu.app.practice.NoiseType
import com.cwjitsu.app.ui.theme.cwSwitchColors
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.SloppyMode

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

        // Farnsworth spacing: characters stay at the character speed while
        // the gaps between them are stretched so the OVERALL (effective)
        // speed drops to this value. It therefore ranges from 5 wpm up to
        // the character speed; at the character speed the gaps are standard
        // and Farnsworth has no effect.
        ToggleRow(
            label = "Enable Farnsworth spacing",
            checked = config.farnsworthWpm != null,
            onCheckedChange = { enabled ->
                onConfigChange(
                    config.copy(
                        farnsworthWpm = if (enabled) {
                            // Default a few wpm BELOW the character speed so
                            // the stretched spacing is immediately audible,
                            // floored at the 5 wpm slider minimum.
                            (config.characterWpm - 5).coerceAtLeast(5)
                        } else null,
                    )
                )
            },
        )
        val farnsOn = config.farnsworthWpm != null
        LabeledSlider(
            label = "Effective speed (WPM)",
            value = (config.farnsworthWpm ?: config.characterWpm).toFloat()
                .coerceAtMost(config.characterWpm.toFloat()),
            valueRange = 5f..config.characterWpm.toFloat(),
            steps = (config.characterWpm - 5 - 1).coerceAtLeast(0),
            // Clamp the label too: a stored value above the character speed
            // (e.g. saved by an older build) is effectively "no stretching".
            valueLabel = if (farnsOn) {
                "${(config.farnsworthWpm ?: 5).coerceAtMost(config.characterWpm)}"
            } else "off",
            onValueChange = { v ->
                onConfigChange(config.copy(farnsworthWpm = v.toInt()))
            },
            // Greyed out while Farnsworth is off so the user can still see
            // the layout but cannot accidentally change a disabled value.
            enabled = farnsOn,
        )

        // Shown as a repeat count: 0 means "sent once, not repeated". The
        // stored `repetitions` is the play count (always >= 1), so the slider
        // maps display = plays - 1 and stores value + 1. This keeps existing
        // behaviour unchanged while letting the setting bottom out at 0. At 0
        // it greys out (like Farnsworth) to signal "no repetition", but stays
        // draggable so the user can turn it back up.
        LabeledSlider(
            label = "Repetitions",
            value = (config.repetitions - 1).toFloat(),
            valueRange = 0f..5f,
            steps = 4,
            valueLabel = "${config.repetitions - 1}",
            onValueChange = { onConfigChange(config.copy(repetitions = it.toInt() + 1)) },
            dimmed = config.repetitions - 1 == 0,
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

        // One-shot replay of the code after the spoken answer. The
        // replay is NOT a repetition - it is a single extra send so the
        // listener can catch a code they missed on the first listening
        // pass.
        ToggleRow(
            label = "Replay code after answer",
            checked = config.replayAfterAnswer,
            onCheckedChange = { onConfigChange(config.copy(replayAfterAnswer = it)) },
        )

        ToggleRow(
            label = "Courtesy tone after sequence",
            checked = config.courtesyToneEnabled,
            onCheckedChange = { onConfigChange(config.copy(courtesyToneEnabled = it)) },
        )

        Text(
            "Keying character",
            style = MaterialTheme.typography.titleLarge,
        )
        SloppyModeRow(
            current = config.sloppyMode,
            onSelect = { onConfigChange(config.copy(sloppyMode = it)) },
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
            label = "Volume variation per item",
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
    enabled: Boolean = true,
    // Greyed appearance while STILL interactive (unlike [enabled] = false).
    // Used to signal an "off" value the user can still drag away from, e.g.
    // Repetitions at 0.
    dimmed: Boolean = false,
) {
    val greyed = !enabled || dimmed
    val labelColor = if (greyed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                     else MaterialTheme.colorScheme.onSurface
    val thumb = if (greyed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.primary
    val track = if (greyed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = labelColor,
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = thumb,
                activeTrackColor = track,
                disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            ),
        )
    }
}

/**
 * Two-chip row that picks one of [SloppyMode]'s entries. Picking a chip
 * stores the choice in [PracticeConfig.sloppyMode]; the schedule builder
 * reads it from there.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SloppyModeRow(
    current: SloppyMode,
    onSelect: (SloppyMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SloppyMode.entries.forEach { mode ->
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        when (mode) {
                            SloppyMode.OFF -> "Clean"
                            SloppyMode.STRAIGHT_KEY -> "Straight key"
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = cwSwitchColors())
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
        Switch(checked = selected, onCheckedChange = { onSelect() }, colors = cwSwitchColors())
    }
}
