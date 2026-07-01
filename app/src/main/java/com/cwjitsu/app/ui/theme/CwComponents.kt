package com.cwjitsu.app.ui.theme

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable

/**
 * Switch colors tuned for the dial-glow palette. Material 3's defaults put
 * the off-state thumb on `outline` and the track on `surfaceVariant`, which
 * in this dark palette are nearly the same gray - the thumb vanishes into
 * the track. Here the off thumb is a light gray dot against a dark track so
 * the on/off state is unmistakable; on-state is a dark dot on amber.
 */
@Composable
fun cwSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = OnAmber,
    checkedTrackColor = Amber,
    checkedBorderColor = Amber,
    uncheckedThumbColor = InkMuted,
    uncheckedTrackColor = Panel,
    uncheckedBorderColor = Hairline,
)
