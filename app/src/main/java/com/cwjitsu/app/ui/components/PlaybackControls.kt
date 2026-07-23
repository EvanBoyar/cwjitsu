package com.cwjitsu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Transport controls at the bottom of the Home screen:
 * Stop | Previous | Restart | Play-Pause | Next.
 *
 * All buttons are always present with fixed sizes (Stop, Previous,
 * Restart and Next are merely disabled while no session is running), so
 * the Play/Pause button never changes size or position when the session
 * state flips. The buttons are icon-only so nothing inside them shifts
 * either. Play/Pause starts a session when idle, pauses a running one,
 * and resumes a paused one. Stop tears the session down entirely
 * (dismissing the media notification). Previous steps back to the item
 * sent before the current one; Restart replays the current item from the
 * top; Next skips ahead (useful for long news headlines). Next sits at
 * the outer edge, away from Stop, since it is the button tapped most
 * often mid-session.
 */
@Composable
fun PlaybackControls(
    isRunning: Boolean,
    isPaused: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onRestart: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPlay = !isRunning || isPaused
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideButton(
            icon = Icons.Filled.Stop,
            contentDescription = "Stop",
            enabled = isRunning,
            onClick = onStop,
        )
        SideButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Previous",
            enabled = isRunning,
            onClick = onPrevious,
        )
        SideButton(
            icon = Icons.Filled.Replay,
            contentDescription = "Restart",
            enabled = isRunning,
            onClick = onRestart,
        )
        Button(
            modifier = Modifier.weight(1f).height(56.dp),
            onClick = onPlayPause,
        ) {
            Icon(
                imageVector = if (showPlay) Icons.Filled.PlayArrow
                              else Icons.Filled.Pause,
                contentDescription = if (showPlay) "Play" else "Pause",
                modifier = Modifier.size(32.dp),
            )
        }
        SideButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Next",
            enabled = isRunning,
            onClick = onNext,
        )
    }
}

@Composable
private fun SideButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(56.dp).width(64.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
    }
}
