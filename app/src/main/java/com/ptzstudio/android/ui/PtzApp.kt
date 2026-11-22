package com.ptzstudio.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
        // Aviso curto e pequeno no topo (não tapa os botões de baixo; some sozinho).
        var toast by remember { mutableStateOf<String?>(null) }
        var toastId by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            engine.messages.collect { msg ->
                toast = msg
                toastId++
            }
        }
        LaunchedEffect(toastId) {
            if (toast != null) {
                delay(1600)
                toast = null
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
            AnimatedVisibility(
                toast != null, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp, start = 24.dp, end = 24.dp),
            ) {
                Text(
                    toast.orEmpty(), color = Color.White, fontSize = 13.sp, maxLines = 2,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xE61E293B))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}
