package com.ptzstudio.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ptzstudio.android.Engine
import com.ptzstudio.android.LinkState
import com.ptzstudio.android.core.AppSettings
import kotlin.math.hypot
import kotlin.math.min

/** Tela principal: vídeo, joystick, zoom, velocidade, presets e botões rápidos. */
@Composable
fun ControlScreen(engine: Engine, onCameras: () -> Unit, onSettings: () -> Unit) {
    val settings by engine.settings.collectAsState()
    val link by engine.link.collectAsState()
    val gamepad by engine.gamepad.collectAsState()
    var fullscreen by remember { mutableStateOf(false) }
    var editPreset by remember { mutableStateOf<Int?>(null) }

    val cam = settings.selectedCamera
    val rtsp = cam?.rtspUrl.orEmpty()

    if (fullscreen && settings.videoEnabled && rtsp.isNotBlank()) {
        androidx.activity.compose.BackHandler { fullscreen = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            RtspVideo(rtsp, Modifier.fillMaxSize())
            TextButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("Sair da tela cheia", color = Color.White)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        TopBar(cam?.name ?: "PTZ Studio", link, gamepad, onCameras, onSettings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val boxW = maxWidth
            val boxH = maxHeight
            val landscape = boxW > boxH
            val video: @Composable (Modifier) -> Unit = { m ->
                VideoCard(engine, settings, m) { fullscreen = true }
            }
            if (landscape) {
                Row(
                    Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        video(Modifier.fillMaxWidth().weight(1f))
                        PresetRow(settings, engine, onEdit = { editPreset = it })
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        JoystickAndZoom(engine, joystickSize = min(200f, boxH.value * 0.52f).dp)
                        SpeedSlider(engine, settings)
                        QuickButtons(engine)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    JoystickAndZoom(engine, joystickSize = min(240f, boxW.value * 0.62f).dp)
                    SpeedSlider(engine, settings)
                    SectionTitle("Posições salvas")
                    PresetGrid(settings, engine, onEdit = { editPreset = it })
                    QuickButtons(engine)
                    Text(
                        "Toque num preset para ir até ele. Toque e segure para salvar a posição atual ou mudar o nome.",
                        color = Palette.Muted, fontSize = 12.sp,
                    )
                }
            }
        }
    }

    editPreset?.let { n ->
        PresetDialog(n, settings.presetNames.getOrElse(n - 1) { "Preset $n" }, engine) { editPreset = null }
    }
}

@Composable
private fun TopBar(title: String, link: LinkState, gamepad: String?, onCameras: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Bar).padding(start = 12.dp, end = 4.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        LinkChip(link)
        if (gamepad != null) {
            StatusChip("Controle", Palette.AccentBright, Palette.Chip, Palette.Soft)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCameras) { Icon(Icons.Default.List, contentDescription = "Câmeras", tint = Palette.Text) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = Palette.Text) }
    }
}

@Composable
fun LinkChip(link: LinkState) {
    when (link) {
        LinkState.ONLINE -> StatusChip(link.label, Palette.Green, Palette.GreenDim, Palette.GreenText)
        LinkState.CONNECTING -> StatusChip(link.label, Palette.Amber, Palette.AmberDim, Palette.Amber)
        LinkState.NO_REPLY -> StatusChip(link.label, Palette.Red, Palette.RedDim, Palette.RedText)
        LinkState.NO_CAMERA -> StatusChip(link.label, Palette.Muted, Palette.Chip, Palette.Muted)
    }
}

@Composable
private fun VideoCard(engine: Engine, settings: AppSettings, modifier: Modifier, onFullscreen: () -> Unit) {
    val rtsp = settings.selectedCamera?.rtspUrl.orEmpty()
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF05070A))
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            rtsp.isBlank() -> Text(
                "Sem vídeo.\nPara ver a câmera aqui, coloque o endereço RTSP em Câmeras › Editar.",
                color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp),
            )
            !settings.videoEnabled -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vídeo desligado", color = Palette.Muted, fontSize = 13.sp)
                PadTile("Ligar vídeo", Modifier.width(140.dp)) { engine.setVideoEnabled(true) }
            }
            else -> {
                RtspVideo(rtsp, Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PadTile("Desligar", Modifier.width(90.dp)) { engine.setVideoEnabled(false) }
                    PadTile("Tela cheia", Modifier.width(100.dp), onClick = onFullscreen)
                }
            }
        }
    }
}

@Composable
private fun JoystickAndZoom(engine: Engine, joystickSize: Dp) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VirtualJoystick(engine, joystickSize)
        Column(
            Modifier.width(78.dp).height(joystickSize),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionTitle("Zoom")
            HoldButton("Zoom +", { engine.setScreenZoom(1) }, { engine.setScreenZoom(0) }, Modifier.fillMaxWidth().weight(1f)) {
                Text("+", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
            HoldButton("Zoom −", { engine.setScreenZoom(-1) }, { engine.setScreenZoom(0) }, Modifier.fillMaxWidth().weight(1f)) {
                Text("−", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Joystick da tela: arraste o círculo azul. A velocidade acompanha a distância do centro (movimento gradual).
 * As setas nas bordas movem enquanto estão apertadas.
 */
@Composable
private fun VirtualJoystick(engine: Engine, size: Dp) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var radiusPx by remember { mutableFloatStateOf(1f) }
    Box(Modifier.size(size)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Joystick: arraste para mover a câmera" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val center = Offset(this.size.width / 2f, this.size.height / 2f)
                        val r = min(this.size.width, this.size.height) / 2f * 0.72f
                        radiusPx = r
                        fun update(p: Offset) {
                            var d = p - center
                            val len = hypot(d.x, d.y)
                            if (len > r) d = d * (r / len)
                            knob = d
                            engine.setVirtualStick((d.x / r).toDouble(), (-d.y / r).toDouble())
                        }
                        update(down.position)
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            update(ch.position)
                            ch.consume()
                        }
                        knob = Offset.Zero
                        engine.setVirtualStick(0.0, 0.0)
                    }
                },
        ) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val outer = min(this.size.width, this.size.height) / 2f
            drawCircle(Color(0xFF151A21), outer, c)
            drawCircle(Palette.Border, outer, c, style = Stroke(1.dp.toPx()))
            drawCircle(Palette.Line, outer * 0.72f, c, style = Stroke(1.dp.toPx()))
            val knobR = outer * 0.33f
            drawCircle(Palette.AccentDim, knobR + 4.dp.toPx(), c + knob)
            drawCircle(Palette.Accent, knobR, c + knob)
        }
        val arrow = size * 0.22f
        ArrowHold(Icons.Default.KeyboardArrowUp, "Para cima", Modifier.align(Alignment.TopCenter), arrow,
            { engine.setScreenPanTilt(0, 1) }, { engine.setScreenPanTilt(0, 0) })
        ArrowHold(Icons.Default.KeyboardArrowDown, "Para baixo", Modifier.align(Alignment.BottomCenter), arrow,
            { engine.setScreenPanTilt(0, -1) }, { engine.setScreenPanTilt(0, 0) })
        ArrowHold(Icons.Default.KeyboardArrowLeft, "Para a esquerda", Modifier.align(Alignment.CenterStart), arrow,
            { engine.setScreenPanTilt(-1, 0) }, { engine.setScreenPanTilt(0, 0) })
        ArrowHold(Icons.Default.KeyboardArrowRight, "Para a direita", Modifier.align(Alignment.CenterEnd), arrow,
            { engine.setScreenPanTilt(1, 0) }, { engine.setScreenPanTilt(0, 0) })
    }
}

@Composable
private fun ArrowHold(icon: ImageVector, label: String, modifier: Modifier, size: Dp, onDown: () -> Unit, onUp: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(maxOf(size, 44.dp))
            .wrapContentSize()
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    onDown()
                    try { tryAwaitRelease() } finally { pressed = false; onUp() }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (pressed) Palette.AccentText else Palette.Muted, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun SpeedSlider(engine: Engine, settings: AppSettings) {
    val m = settings.motion
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Velocidade", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.width(82.dp))
        Slider(
            value = m.speedPercent.toFloat(),
            onValueChange = { engine.setMotion(m.copy(speedPercent = it.toInt())) },
            valueRange = 1f..100f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Palette.AccentBright, activeTrackColor = Palette.AccentBright),
        )
        Text("${m.speedPercent}%", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun QuickButtons(engine: Engine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadTile("Autofoco", Modifier.weight(1f)) { engine.autofocus() }
        PadTile("Brilho −", Modifier.weight(1f)) { engine.brightness(-1) }
        PadTile("Brilho +", Modifier.weight(1f)) { engine.brightness(1) }
        PadTile("Home", Modifier.weight(1f)) { engine.home() }
    }
}

@Composable
private fun PresetRow(settings: AppSettings, engine: Engine, onEdit: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in 1..AppSettings.PRESET_COUNT) {
            PadTile(
                settings.presetNames.getOrElse(n - 1) { "$n" },
                Modifier.weight(1f),
                height = 48.dp,
                onLongPress = { onEdit(n) },
            ) { engine.recallPreset(n) }
        }
    }
}

@Composable
private fun PresetGrid(settings: AppSettings, engine: Engine, onEdit: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 1..4) {
                    val n = row * 4 + col
                    PadTile(
                        settings.presetNames.getOrElse(n - 1) { "$n" },
                        Modifier.weight(1f),
                        onLongPress = { onEdit(n) },
                    ) { engine.recallPreset(n) }
                }
            }
        }
    }
}

@Composable
private fun PresetDialog(n: Int, current: String, engine: Engine, onClose: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var confirmSave by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Card,
        title = { Text("Preset $n", color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true)
                if (!confirmSave) {
                    PadTile("Salvar a posição atual da câmera aqui", Modifier.fillMaxWidth()) { confirmSave = true }
                } else {
                    Text("A posição gravada antes neste preset será substituída.", color = Palette.Amber, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PadTile("Cancelar", Modifier.weight(1f)) { confirmSave = false }
                        PadTile("Gravar", Modifier.weight(1f), selected = true) {
                            engine.savePreset(n)
                            confirmSave = false
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.renamePreset(n, name); onClose() }) { Text("Salvar nome") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Fechar") } },
    )
}
