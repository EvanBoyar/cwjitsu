package com.cwjitsu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cwjitsu.app.practice.NoiseType
import com.cwjitsu.app.ui.theme.cwSwitchColors
import com.cwjitsu.app.practice.PracticeConfig
import com.cwjitsu.app.practice.SloppyMode
import kotlin.math.roundToInt

/**
 * Reusable Compose panel that exposes the whole [PracticeConfig] to the user,
 * grouped into titled sections (Speed / Flow / Spoken answers / Keying /
 * Tone / Background noise) separated by dividers.
 *
 * Edits are emitted as TRANSFORMS ([onUpdate] receives a `(config) -> config`
 * lambda) rather than full replacement objects, so the caller can apply them
 * atomically against the freshly-read stored config. Building a replacement
 * from the collected [config] snapshot would race concurrent edits - and,
 * before DataStore's first emission, would overwrite every saved setting
 * with constructor defaults.
 */
@Composable
fun ConfigPanel(
    config: PracticeConfig,
    onUpdate: ((PracticeConfig) -> PracticeConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ---------- Speed ----------
        Text("Speed", style = MaterialTheme.typography.titleLarge)

        LabeledSlider(
            label = "Character speed (WPM)",
            value = config.characterWpm.toFloat(),
            valueRange = 5f..60f,
            steps = 54,
            valueLabel = "${config.characterWpm}",
            onValueChange = { v -> onUpdate { it.copy(characterWpm = v.toInt()) } },
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
                onUpdate {
                    it.copy(
                        farnsworthWpm = if (enabled) {
                            // Default a few wpm BELOW the character speed so
                            // the stretched spacing is immediately audible,
                            // floored at the 5 wpm slider minimum.
                            (it.characterWpm - 5).coerceAtLeast(5)
                        } else null,
                    )
                }
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
            onValueChange = { v -> onUpdate { it.copy(farnsworthWpm = v.toInt()) } },
            // Greyed out while Farnsworth is off so the user can still see
            // the layout but cannot accidentally change a disabled value.
            enabled = farnsOn,
        )

        // Speed variability: each sent item is rendered at a random character
        // speed within [base - subtracted, base + added]. One two-thumb
        // slider sets both offsets: the left thumb (<= 0) is the subtracted
        // WPM, the right thumb (>= 0) the added WPM, so 0 (the base speed)
        // always stays inside the window.
        ToggleRow(
            label = "Speed variability",
            checked = config.speedVariabilityEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(speedVariabilityEnabled = on) } },
        )
        if (config.speedVariabilityEnabled) {
            val maxVar = PracticeConfig.MAX_SPEED_VARIATION_WPM
            LabeledRangeSlider(
                label = "Variation (WPM)",
                value = -config.speedVarMinusWpm.toFloat()..config.speedVarPlusWpm.toFloat(),
                valueRange = -maxVar.toFloat()..maxVar.toFloat(),
                steps = 2 * maxVar - 1,
                valueLabel = "-${config.speedVarMinusWpm} / +${config.speedVarPlusWpm}",
                onValueChange = { range ->
                    onUpdate {
                        it.copy(
                            // Each thumb is pinned to its own side of 0 so the
                            // stored offsets are always non-negative.
                            speedVarMinusWpm = (-range.start.roundToInt()).coerceIn(0, maxVar),
                            speedVarPlusWpm = range.endInclusive.roundToInt().coerceIn(0, maxVar),
                        )
                    }
                },
            )
        }

        // ---------- Flow ----------
        HorizontalDivider()
        Text("Flow", style = MaterialTheme.typography.titleLarge)

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
            onValueChange = { v -> onUpdate { it.copy(repetitions = v.toInt() + 1) } },
            dimmed = config.repetitions - 1 == 0,
        )

        LabeledSlider(
            label = "Post-send pause (ms)",
            value = config.postSendPauseMs.toFloat(),
            valueRange = 0f..5000f,
            steps = 49,
            valueLabel = "${config.postSendPauseMs}ms",
            onValueChange = { v -> onUpdate { it.copy(postSendPauseMs = v.toLong()) } },
        )

        ToggleRow(
            label = "Courtesy tone after sequence",
            checked = config.courtesyToneEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(courtesyToneEnabled = on) } },
        )

        // ---------- Spoken answers ----------
        HorizontalDivider()
        Text("Spoken answers", style = MaterialTheme.typography.titleLarge)

        ToggleRow(
            label = "Speak the answer",
            checked = config.answerEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(answerEnabled = on) } },
        )

        // Everything below follows the master toggle and greys out with it:
        // how the answer is pronounced, how loud, how soon, and whether the
        // code replays afterwards.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.natoSpokenAnswers,
                onClick = { onUpdate { it.copy(natoSpokenAnswers = true) } },
                label = { Text("NATO phonetics") },
                enabled = config.answerEnabled,
            )
            FilterChip(
                selected = !config.natoSpokenAnswers,
                onClick = { onUpdate { it.copy(natoSpokenAnswers = false) } },
                label = { Text("Letter's spoken name") },
                enabled = config.answerEnabled,
            )
        }

        LabeledSlider(
            label = "Spoken answer volume",
            value = config.ttsVolume,
            valueRange = 0f..1f,
            steps = 99,
            valueLabel = "%.0f%%".format(config.ttsVolume * 100),
            onValueChange = { v -> onUpdate { it.copy(ttsVolume = v) } },
            enabled = config.answerEnabled,
        )

        LabeledSlider(
            label = "Answer delay (ms)",
            value = config.answerDelayMs.toFloat(),
            valueRange = 0f..5000f,
            steps = 49,
            valueLabel = "${config.answerDelayMs}ms",
            onValueChange = { v -> onUpdate { it.copy(answerDelayMs = v.toLong()) } },
            enabled = config.answerEnabled,
        )

        // One-shot replay of the code after the spoken answer. The
        // replay is NOT a repetition - it is a single extra send so the
        // listener can catch a code they missed on the first listening
        // pass.
        ToggleRow(
            label = "Replay code after answer",
            checked = config.replayAfterAnswer,
            onCheckedChange = { on -> onUpdate { it.copy(replayAfterAnswer = on) } },
            enabled = config.answerEnabled,
        )

        // ---------- Keying ----------
        HorizontalDivider()
        Text("Keying character", style = MaterialTheme.typography.titleLarge)

        SloppyModeRow(
            current = config.sloppyMode,
            onSelect = { mode -> onUpdate { it.copy(sloppyMode = mode) } },
        )

        // ---------- Tone ----------
        HorizontalDivider()
        Text("Tone", style = MaterialTheme.typography.titleLarge)

        LabeledSlider(
            label = "Tone frequency (Hz)",
            value = config.frequencyHz.toFloat(),
            valueRange = 300f..1500f,
            steps = 119,
            valueLabel = "${config.frequencyHz}Hz",
            onValueChange = { v -> onUpdate { it.copy(frequencyHz = v.toInt()) } },
        )

        ToggleRow(
            label = "Randomize tone frequency",
            checked = config.randomizeFrequency,
            onCheckedChange = { on -> onUpdate { it.copy(randomizeFrequency = on) } },
        )

        if (config.randomizeFrequency) {
            // Each bound clamps against the other so min can never cross
            // above max: PracticeConfig requires min <= max, and an
            // unconstrained pair of sliders used to make that invariant
            // trivially violable (crashing in the copy() call).
            LabeledSlider(
                label = "Random frequency min",
                value = config.frequencyMinHz.toFloat(),
                valueRange = 300f..1500f,
                steps = 119,
                valueLabel = "${config.frequencyMinHz}Hz",
                onValueChange = { v ->
                    onUpdate { it.copy(frequencyMinHz = v.toInt().coerceAtMost(it.frequencyMaxHz)) }
                },
            )
            LabeledSlider(
                label = "Random frequency max",
                value = config.frequencyMaxHz.toFloat(),
                valueRange = 300f..1500f,
                steps = 119,
                valueLabel = "${config.frequencyMaxHz}Hz",
                onValueChange = { v ->
                    onUpdate { it.copy(frequencyMaxHz = v.toInt().coerceAtLeast(it.frequencyMinHz)) }
                },
            )
        }

        LabeledSlider(
            label = "Master volume",
            value = config.masterVolume,
            valueRange = 0f..1f,
            steps = 99,
            valueLabel = "%.0f%%".format(config.masterVolume * 100),
            onValueChange = { v -> onUpdate { it.copy(masterVolume = v) } },
        )

        ToggleRow(
            label = "Volume variation per item",
            checked = config.volumeVariationEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(volumeVariationEnabled = on) } },
        )

        // ---------- Background noise ----------
        HorizontalDivider()
        Text("Background noise", style = MaterialTheme.typography.titleLarge)

        for (t in NoiseType.entries) {
            NoiseRow(
                label = t.name,
                selected = config.noiseType == t,
                onSelect = { onUpdate { it.copy(noiseType = t) } },
            )
        }

        LabeledSlider(
            label = "Noise volume",
            value = config.noiseVolume,
            valueRange = 0f..1f,
            steps = 99,
            valueLabel = "%.0f%%".format(config.noiseVolume * 100),
            onValueChange = { v -> onUpdate { it.copy(noiseVolume = v) } },
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
        // Drag-only on purpose: the standard Slider moves on a bare track
        // press, so scrolling the settings page used to change values.
        DragOnlySlider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            thumbColor = thumb,
            trackColor = track,
        )
    }
}

/**
 * Two-thumb sibling of [LabeledSlider]: same label/value header, but the
 * track carries a [DragOnlyRangeSlider] so one row can set a pair of
 * bounds (e.g. the speed-variability -/+ offsets).
 */
@Composable
private fun LabeledRangeSlider(
    label: String,
    value: ClosedFloatingPointRange<Float>,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DragOnlyRangeSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
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

/**
 * Shared label + switch row. Public so per-category settings on the Home
 * screen use the same row instead of re-inlining it.
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val labelColor = if (enabled) MaterialTheme.colorScheme.onSurface
                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = labelColor)
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = cwSwitchColors(), enabled = enabled)
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
