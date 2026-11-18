package com.ptzstudio.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ptzstudio.android.Engine
import kotlinx.coroutines.launch

private enum class Screen { CONTROL, CAMERAS, SETTINGS, MAPPING }

@Composable
fun PtzApp(engine: Engine) {
    PtzTheme {
        var screen by rememberSaveable { mutableStateOf(Screen.CONTROL) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            engine.messages.collect { msg ->
                launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(msg)
                }
            }
        }

        BackHandler(enabled = screen != Screen.CONTROL) {
            screen = if (screen == Screen.MAPPING) Screen.SETTINGS else Screen.CONTROL
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.Ground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when (screen) {
                Screen.CONTROL -> ControlScreen(
                    engine,
                    onCameras = { screen = Screen.CAMERAS },
                    onSettings = { screen = Screen.SETTINGS },
                )
                Screen.CAMERAS -> CamerasScreen(engine, onBack = { screen = Screen.CONTROL })
                Screen.SETTINGS -> SettingsScreen(
                    engine,
                    onBack = { screen = Screen.CONTROL },
                    onMapping = { screen = Screen.MAPPING },
                )
                Screen.MAPPING -> MappingScreen(engine, onBack = { screen = Screen.SETTINGS })
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
