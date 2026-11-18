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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
                        QuickButtons(engine)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        JoystickAndZoom(engine, joystickSize = min(240f, boxH.value * 0.62f).dp)
                        SpeedSlider(engine, settings)
                        ZoomSpeedSlider(engine, settings)
                        ZoomInfo(engine, settings)
                    }
                }
            } else {
                // O vídeo fica fixo no topo; só os controles rolam por baixo dele.
                Column(Modifier.fillMaxSize()) {
                    video(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp).fillMaxWidth().aspectRatio(16f / 9f))
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    JoystickAndZoom(engine, joystickSize = min(300f, boxW.value - 24f - 78f - 12f).dp)
                    SpeedSlider(engine, settings)
                    ZoomSpeedSlider(engine, settings)
                    ZoomInfo(engine, settings)
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

/** Direções dos 8 botões em volta do joystick: ângulo (0 = direita, 90 = cima), pan, tilt. */
private val Wedges = listOf(
    Triple(90.0, 0, 1), Triple(45.0, 1, 1), Triple(0.0, 1, 0), Triple(315.0, 1, -1),
    Triple(270.0, 0, -1), Triple(225.0, -1, -1), Triple(180.0, -1, 0), Triple(135.0, -1, 1),
)

private const val DRAG_R = 0.50f     // dentro deste raio o toque arrasta o joystick
private const val WEDGE_IN = 0.56f   // anel dos 8 botões de direção
private const val WEDGE_OUT = 0.97f
private const val WEDGE_HALF = 19.5  // cada botão ocupa 39 dos 45 graus (folga entre eles)

/**
 * Joystick da tela, como no PTZ Studio do Windows:
 * - centro: arraste o círculo azul; a velocidade acompanha a distância do centro (movimento gradual);
 * - anel de fora: 8 botões de direção (inclusive diagonais); segurar = mover, soltar = parar.
 * O arraste só começa no centro, então tocar num botão de direção nunca puxa o joystick.
 */
@Composable
private fun VirtualJoystick(engine: Engine, size: Dp) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var pressedWedge by remember { mutableStateOf(-1) }
    Canvas(
        Modifier
            .size(size)
            .semantics { contentDescription = "Joystick: arraste o centro ou segure uma das 8 setas para mover a câmera" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val outer = min(this.size.width, this.size.height) / 2f
                    val d0 = down.position - center
                    val r0 = hypot(d0.x, d0.y)
                    if (r0 <= outer * DRAG_R) {
                        // Arraste proporcional
                        val travel = outer * WEDGE_IN
                        fun update(p: Offset) {
                            var d = p - center
                            val len = hypot(d.x, d.y)
                            if (len > travel) d = d * (travel / len)
                            knob = d
                            engine.setVirtualStick((d.x / travel).toDouble(), (-d.y / travel).toDouble())
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
                    } else if (r0 >= outer * (WEDGE_IN - 0.04f) && r0 <= outer * 1.05f) {
                        // Botão de direção (segurar)
                        var ang = Math.toDegrees(atan2(-d0.y.toDouble(), d0.x.toDouble()))
                        if (ang < 0) ang += 360.0
                        val idx = ((ang / 45.0).roundToInt()) % 8
                        var diff = kotlin.math.abs(ang - idx * 45.0)
                        if (diff > 180) diff = 360 - diff
                        if (diff <= WEDGE_HALF) {
                            val w = Wedges.first { it.first == idx * 45.0 }
                            pressedWedge = idx
                            engine.setScreenPanTilt(w.second, w.third)
                            down.consume()
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    ch.consume()
                                }
                            } finally {
                                pressedWedge = -1
                                engine.setScreenPanTilt(0, 0)
                            }
                        }
                    }
                }
            },
    ) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = min(this.size.width, this.size.height) / 2f
        drawCircle(Color(0xFF0F1217), outer, c)
        drawCircle(Color(0xFF3B4352), outer - 1.5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))

        val rIn = outer * WEDGE_IN
        val rOut = outer * WEDGE_OUT
        val rMid = (rIn + rOut) / 2f
        for (w in Wedges) {
            val a = w.first
            val pressed = pressedWedge == ((a / 45.0).roundToInt() % 8)
            val start = (-a - WEDGE_HALF).toFloat()
            val sweep = (WEDGE_HALF * 2).toFloat()
            val sector = Path().apply {
                arcTo(Rect(c, rOut), start, sweep, true)
                arcTo(Rect(c, rIn), start + sweep, -sweep, false)
                close()
            }
            drawPath(sector, if (pressed) Palette.AccentDim else Color(0xFF252A35))
            drawPath(sector, if (pressed) Palette.Accent else Color(0xFF3B4352), style = Stroke(1.dp.toPx()))
            // Seta (chevron) apontando para fora
            val rad = Math.toRadians(a)
            val dir = Offset(cos(rad).toFloat(), -sin(rad).toFloat())
            val perp = Offset(-dir.y, dir.x)
            val k = outer * 0.07f
            val tip = c + dir * (rMid + k * 0.6f)
            val chevron = Path().apply {
                val p1 = c + dir * (rMid - k * 0.6f) + perp * k
                val p2 = c + dir * (rMid - k * 0.6f) - perp * k
                moveTo(p1.x, p1.y); lineTo(tip.x, tip.y); lineTo(p2.x, p2.y)
            }
            drawPath(chevron, if (pressed) Palette.AccentText else Color(0xFF9AA4B5), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        drawCircle(
            Color(0xFF2A303B), outer * DRAG_R, c,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))),
        )
        val knobR = outer * 0.26f
        drawCircle(Palette.AccentText, knobR + 2.dp.toPx(), c + knob)
        drawCircle(Palette.Accent, knobR, c + knob)
    }
}

@Composable
private fun SpeedSlider(engine: Engine, settings: AppSettings) {
    val m = settings.motion
    PercentRow("Velocidade", m.speedPercent) { engine.setMotion(m.copy(speedPercent = it)) }
}

@Composable
private fun ZoomSpeedSlider(engine: Engine, settings: AppSettings) {
    val m = settings.motion
    PercentRow("Zoom", m.zoomSpeedPercent) { engine.setMotion(m.copy(zoomSpeedPercent = it)) }
}

/** Mostra o zoom atual e quanto o Pan/Tilt foi reduzido por causa dele. */
@Composable
private fun ZoomInfo(engine: Engine, settings: AppSettings) {
    val zoom by engine.zoomLevel.collectAsState()
    val m = settings.motion
    val z = zoom
    val text = when {
        z == null -> "Zoom: a câmera não informou a posição (Pan/Tilt sem redução automática)"
        !m.zoomSlowdownEnabled -> "Zoom ${(z * 100).toInt()}%"
        else -> "Zoom ${(z * 100).toInt()}% · Pan/Tilt a ${(m.zoomSlowdownFactor(z) * 100).toInt()}% da velocidade"
    }
    Text(text, color = if (z == null) Palette.Muted else Palette.Soft, fontSize = 12.sp)
}

@Composable
private fun PercentRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.width(82.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(1, 100)) },
            valueRange = 1f..100f,
            modifier = Modifier.weight(1f).height(36.dp),
            colors = SliderDefaults.colors(thumbColor = Palette.AccentBright, activeTrackColor = Palette.AccentBright),
        )
        Text("$value%", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
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
