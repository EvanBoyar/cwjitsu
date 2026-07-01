package com.cwjitsu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Play / stop control at the bottom of the Home screen, with a Skip button
 * that appears while a session is running.
 *
 * The main button shows a stop icon and "Stop" while [isRunning] is true
 * (clicking it stops the session), otherwise a play icon and "Play". The
 * Skip button jumps to the next item (useful for long news headlines).
 */
@Composable
fun PlaybackControls(
    isRunning: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            modifier = Modifier.weight(1f).height(56.dp),
            onClick = { if (isRunning) onStop() else onPlay() },
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Filled.Stop
                              else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = if (isRunning) "Stop" else "Play",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (isRunning) {
            FilledTonalButton(
                onClick = onSkip,
                modifier = Modifier.height(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
