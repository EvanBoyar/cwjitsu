package com.cwjitsu.app.ui.theme

import androidx.compose.ui.graphics.Color

// "Dial-glow" palette: a warm near-black charcoal base with a single amber
// accent, like the backlit dial of a radio receiver. The boldness lives in
// one place (amber); everything structural is a disciplined neutral gray so
// the accent reads as signal, not decoration.

// Neutrals — warm charcoal, cool enough to stay professional.
val Charcoal = Color(0xFF0E1013)      // app background (near-black)
val Panel = Color(0xFF171A1F)         // cards / surfaces
val PanelRaised = Color(0xFF21262D)   // raised container / surfaceVariant
val Hairline = Color(0xFF2D333B)      // outlines / dividers
val HairlineDim = Color(0xFF22272E)   // subtle outlines
val Ink = Color(0xFFE8EAED)           // primary text (off-white)
val InkMuted = Color(0xFFA0A8B2)      // secondary text / captions

// Amber accent — the dial glow.
val Amber = Color(0xFFE7A33D)
val AmberDim = Color(0xFFC98F35)      // pressed / secondary accent
val OnAmber = Color(0xFF1A1206)       // text/icons on amber fills
val AmberContainer = Color(0xFF3A2A12) // amber-tinted selected surface
val OnAmberContainer = Color(0xFFF6DCAB)

// Error — a professional signal red, not a candy red.
val SignalRed = Color(0xFFE5534B)
val OnSignalRed = Color(0xFF1A0705)
val SignalRedContainer = Color(0xFF3B1512)
val OnSignalRedContainer = Color(0xFFF5B8B3)
