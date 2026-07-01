# Keep Compose runtime helpers
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep TTS engines / Media3 session classes
-keep class androidx.media3.session.** { *; }
