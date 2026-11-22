package com.ptzstudio.android.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        // Buffer mínimo: começa a mostrar assim que chega o primeiro quadro (menos atraso entre o comando e a imagem).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(LOW_LATENCY_MIN_MS, 1000, 0, 100)
            .setPrioritizeTimeOverSizeThresholds(true)
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

    // Vídeo ficando para trás (Wi-Fi oscilou, celular demorou): acelera um pouco até alcançar o ao vivo de novo.
    LaunchedEffect(player) {
        var speed = 1f
        while (true) {
            delay(200)
            val behind = player.totalBufferedDuration
            val want = catchUpSpeed(behind)
            if (want != speed) {
                speed = want
                player.setPlaybackSpeed(want)
            }
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

private const val LOW_LATENCY_MIN_MS = 150

/** Velocidade de reprodução para alcançar o ao vivo: quanto mais atrasado no buffer, mais rápido (sem aparecer). */
fun catchUpSpeed(bufferedMs: Long): Float = when {
    bufferedMs > 600 -> 1.5f
    bufferedMs > 300 -> 1.2f
    bufferedMs > 180 -> 1.06f
    else -> 1f
}

/** Desenho dos ícones pequenos do vídeo. */
enum class OverlayGlyph { FULLSCREEN, EXIT_FULLSCREEN, VIDEO_OFF }

/**
 * Controles do vídeo no estilo do YouTube: ficam escondidos (a imagem fica limpa) e aparecem ao tocar no vídeo,
 * pequenos, nos cantos; somem sozinhos depois de 3 segundos.
 */
@Composable
fun VideoOverlay(fullscreen: Boolean, onToggleFullscreen: () -> Unit, onVideoOff: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, touches) {
        if (visible) {
            delay(3000)
            visible = false
        }
    }
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = {
                visible = !visible
                touches++
            })
        },
    ) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f))) {
                OverlayButton(OverlayGlyph.VIDEO_OFF, "Desligar vídeo", Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    visible = false
                    onVideoOff()
                }
                OverlayButton(
                    if (fullscreen) OverlayGlyph.EXIT_FULLSCREEN else OverlayGlyph.FULLSCREEN,
                    if (fullscreen) "Sair da tela cheia" else "Tela cheia",
                    Modifier.align(Alignment.BottomEnd).padding(6.dp),
                ) {
                    visible = false
                    onToggleFullscreen()
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(glyph: OverlayGlyph, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
            .semantics { contentDescription = description }
            .pointerInput(glyph) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val c = Color.White
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                val p = Path().apply {
                    moveTo(x, y + dy)
                    lineTo(x, y)
                    lineTo(x + dx, y)
                }
                drawPath(p, c, style = stroke)
            }
            val a = w * 0.32f
            when (glyph) {
                OverlayGlyph.FULLSCREEN -> {
                    corner(0f, 0f, a, a); corner(w, 0f, -a, a); corner(0f, w, a, -a); corner(w, w, -a, -a)
                }
                OverlayGlyph.EXIT_FULLSCREEN -> {
                    corner(a, a, -a, -a); corner(w - a, a, a, -a); corner(a, w - a, -a, a); corner(w - a, w - a, a, a)
                }
                OverlayGlyph.VIDEO_OFF -> {
                    // Câmera de vídeo com um risco por cima.
                    drawRoundRect(c, Offset(0f, w * 0.28f), Size(w * 0.66f, w * 0.46f), CornerRadius(w * 0.08f), style = stroke)
                    val tri = Path().apply {
                        moveTo(w * 0.70f, w * 0.44f); lineTo(w, w * 0.30f); lineTo(w, w * 0.72f); lineTo(w * 0.70f, w * 0.58f); close()
                    }
                    drawPath(tri, c, style = stroke)
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(w * 0.05f, w * 0.05f), Offset(w * 0.95f, w * 0.95f), strokeWidth = w * 0.24f)
                    drawLine(c, Offset(w * 0.05f, w * 0.05f), Offset(w * 0.95f, w * 0.95f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
                }
            }
        }
    }
}
