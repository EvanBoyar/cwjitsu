package com.cwjitsu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compact play / stop button used at the bottom of the Home screen.
 *
 * The button shows a stop icon and "Stop" label while [isRunning] is true
 * (clicking it stops the session), otherwise a play icon and "Play" label.
 */
@Composable
fun PlaybackControls(
    isRunning: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        onClick = { if (isRunning) onStop() else onPlay() },
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                text = if (isRunning) "Stop" else "Play",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
