package com.ptzstudio.android.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Vídeo ao vivo da câmera por RTSP (H.264/H.265, decodificado pelo próprio celular).
 * O vídeo é independente dos comandos: se ele travar, o controle da câmera continua funcionando.
 */
@OptIn(UnstableApi::class)
@Composable
fun RtspVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var error by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableIntStateOf(0) }

    val player = remember(url, attempt) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 250)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            val source = RtspMediaSource.Factory()
                .setTimeoutMs(6000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            setMediaSource(source)
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    var failures by remember(url) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "Sem vídeo (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})"
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    error = null
                    failures = 0
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Queda do Wi-Fi ou da câmera: tenta de novo sozinho, esperando um pouco mais a cada vez (3 s, 6 s, 10 s...).
    LaunchedEffect(error, attempt) {
        if (error != null) {
            delay(minOf(3000L * (failures + 1), 10_000L))
            failures++
            error = null
            attempt++
        }
    }

    // App fora da tela ou tela bloqueada: para de receber e decodificar o vídeo; volta ao reabrir.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.stop()
                Lifecycle.Event.ON_START -> {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.playWhenReady = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        val e = error
        if (e != null) {
            Column(
                Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(e, color = Palette.Soft, fontSize = 13.sp, textAlign = TextAlign.Center)
                Text("Confira o endereço RTSP em Câmeras › Editar.", color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                Button(
                    onClick = { error = null; attempt++ },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                ) { Text("Tentar de novo") }
            }
        }
    }
}
