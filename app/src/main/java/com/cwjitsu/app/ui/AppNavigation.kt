package com.cwjitsu.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cwjitsu.app.ui.screens.HomeScreen
import com.cwjitsu.app.ui.screens.SettingsScreen

/**
 * Nav graph. The Home screen is the single point of entry - it hosts the
 * category toggles, per-category settings (countries, source text), and
 * playback. Settings is a sibling screen for global configuration.
 */
@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Route.Home.path) {
        composable(Route.Home.path) {
            HomeScreen(
                onPickSettings = { nav.navigate(Route.Settings.path) },
            )
        }
        composable(Route.Settings.path) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}

sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Settings : Route("settings")
}
