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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            VideoOverlay(
                fullscreen = true,
                onToggleFullscreen = { fullscreen = false },
                onVideoOff = { fullscreen = false; engine.setVideoEnabled(false) },
            )
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ground)) {
        val boxW = maxWidth
        val boxH = maxHeight
        val landscape = boxW > boxH
        val video: @Composable (Modifier) -> Unit = { m ->
            VideoCard(engine, settings, m) { fullscreen = true }
        }
        val topBar: @Composable () -> Unit = {
            TopBar(cam?.name ?: "PTZ Studio", link, gamepad, onCameras, onSettings)
        }
        if (landscape) {
            // Deitado: o vídeo sobe até o topo à esquerda, no formato 16:9; a barra fica só na coluna da direita.
            val leftW = min(boxW.value * 0.55f, (boxH.value - 24f - 86f - 40f - 16f) * 16f / 9f).coerceAtLeast(220f)
            val rightW = boxW.value - leftW - 36f
            val stick = min(rightW - 2 * ZOOM_W - 20f, boxH.value - 52f - 32f - 46f - 22f - 16f).coerceIn(110f, 260f)
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(leftW.dp).fillMaxHeight().padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    PresetGrid(settings, engine, onEdit = { editPreset = it })
                    QuickButtons(engine)
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp)) {
                    topBar()
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        JoystickAndZoom(engine, joystickSize = stick.dp)
                        SpeedAndZoomSliders(engine, settings)
                        ZoomInfo(engine, settings)
                    }
                }
            }
        } else {
            // Em pé: o vídeo fica fixo no topo e os controles se ajustam ao espaço que sobra, sem precisar rolar.
            Column(Modifier.fillMaxSize()) {
                topBar()
                video(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp).fillMaxWidth().aspectRatio(16f / 9f))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val availH = maxHeight.value
                    // Altura fixa do resto: velocidades 46, zoom atual 16, presets 86, botões 40, espaços e margens.
                    val stick = min(boxW.value - 24f - 2 * ZOOM_W - 20f, availH - 250f).coerceIn(140f, 320f)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        JoystickAndZoom(engine, joystickSize = stick.dp)
                        SpeedAndZoomSliders(engine, settings)
                        ZoomInfo(engine, settings)
                        PresetGrid(settings, engine, onEdit = { editPreset = it })
                        QuickButtons(engine)
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
        // O título fica sempre inteiro (até um limite); quem encolhe são os avisos ao lado.
        Text(
            title, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkChip(link, Modifier.weight(1f, fill = false))
            if (gamepad != null) {
                StatusChip("Controle", Palette.AccentBright, Palette.Chip, Palette.Soft, Modifier.weight(1f, fill = false))
            }
        }
        IconButton(onClick = onCameras) { Icon(Icons.Default.List, contentDescription = "Câmeras", tint = Palette.Text) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = Palette.Text) }
    }
}

@Composable
fun LinkChip(link: LinkState, modifier: Modifier = Modifier) {
    when (link) {
        LinkState.ONLINE -> StatusChip(link.label, Palette.Green, Palette.GreenDim, Palette.GreenText, modifier)
        LinkState.CONNECTING -> StatusChip(link.label, Palette.Amber, Palette.AmberDim, Palette.Amber, modifier)
        LinkState.NO_REPLY -> StatusChip(link.label, Palette.Red, Palette.RedDim, Palette.RedText, modifier)
        LinkState.NO_CAMERA -> StatusChip(link.label, Palette.Muted, Palette.Chip, Palette.Muted, modifier)
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
                VideoOverlay(fullscreen = false, onToggleFullscreen = onFullscreen, onVideoOff = { engine.setVideoEnabled(false) })
            }
        }
    }
}

private const val ZOOM_W = 56f

/**
 * Joystick no centro (fácil de usar com uma mão), com Zoom + em cima e Zoom − embaixo à direita.
 * Um espaço do mesmo tamanho à esquerda mantém o joystick centralizado.
 */
@Composable
private fun JoystickAndZoom(engine: Engine, joystickSize: Dp) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(ZOOM_W.dp))
        VirtualJoystick(engine, joystickSize)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val h = maxOf(joystickSize * 0.44f, 64.dp)
            ZoomHold("+", "Zoom +", 1, engine, h)
            ZoomHold("−", "Zoom −", -1, engine, h)
        }
    }
}

@Composable
private fun ZoomHold(symbol: String, label: String, dir: Int, engine: Engine, height: Dp) {
    HoldButton(
        label,
        { engine.setScreenZoom(dir) },
        { engine.setScreenZoom(0) },
        Modifier.width(ZOOM_W.dp).height(height),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(symbol, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("Zoom", color = Palette.Muted, fontSize = 10.sp)
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
                        try {
                            update(down.position)
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                update(ch.position)
                                ch.consume()
                            }
                        } finally {
                            // Sempre solta, mesmo se o gesto for cancelado no meio (outro dedo, troca de tela...).
                            knob = Offset.Zero
                            engine.setVirtualStick(0.0, 0.0)
                        }
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

/** Velocidade do Pan/Tilt e do zoom lado a lado, no valor real da câmera (ex.: 1 a 24 e 1 a 8). */
@Composable
private fun SpeedAndZoomSliders(engine: Engine, settings: AppSettings) {
    val m = settings.motion
    val maxPan = engine.maxPanSpeed
    val maxZoom = engine.maxZoomSpeed
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LevelSlider("Velocidade", m.speedLevel(maxPan), maxPan, Modifier.weight(1f)) {
            engine.setMotion(m.withSpeedLevel(it, maxPan))
        }
        LevelSlider("Zoom", m.zoomLevelSetting(maxZoom), maxZoom, Modifier.weight(1f)) {
            engine.setMotion(m.withZoomLevelSetting(it, maxZoom))
        }
    }
}

@Composable
fun LevelSlider(label: String, value: Int, max: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("$value de $max", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        ThinSlider(value, max, Modifier.fillMaxWidth(), onChange)
    }
}

/** Barra fina (menos altura que a barra padrão), de 1 até [max], de 1 em 1. Tocar ou arrastar. */
@Composable
fun ThinSlider(value: Int, max: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val top = max.coerceAtLeast(2)
    Canvas(
        modifier
            .height(28.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), 1f..top.toFloat(), (top - 2).coerceAtLeast(0))
                setProgress { t -> change(t.roundToInt().coerceIn(1, top)); true }
            }
            .pointerInput(top) {
                val r = 9.dp.toPx()
                fun at(x: Float): Int {
                    val f = ((x - r) / (this.size.width - 2 * r)).coerceIn(0f, 1f)
                    return (1 + f * (top - 1)).roundToInt().coerceIn(1, top)
                }
                awaitEachGesture {
                    val d = awaitFirstDown()
                    change(at(d.position.x))
                    d.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == d.id } ?: break
                        if (!c.pressed) break
                        change(at(c.position.x))
                        c.consume()
                    }
                }
            },
    ) {
        val r = 9.dp.toPx()
        val y = this.size.height / 2f
        val left = r
        val right = this.size.width - r
        val x = left + (right - left) * ((current - 1f) / (top - 1f)).coerceIn(0f, 1f)
        drawLine(Color(0xFF3A4250), Offset(left, y), Offset(right, y), 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Palette.AccentBright, Offset(left, y), Offset(x, y), 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Palette.AccentBright, r, Offset(x, y))
    }
}

/** Mostra o zoom atual e quanto o Pan/Tilt foi reduzido por causa dele. */
@Composable
private fun ZoomInfo(engine: Engine, settings: AppSettings) {
    val zoom by engine.zoomLevel.collectAsState()
    val m = settings.motion
    val z = zoom
    val text = when {
        z == null -> "Zoom: posição desconhecida (sem redução automática)"
        !m.zoomSlowdownEnabled -> "Zoom ${(z * 100).toInt()}%"
        else -> "Zoom ${(z * 100).toInt()}% · Pan/Tilt a ${(m.zoomSlowdownFactor(z) * 100).toInt()}% da velocidade"
    }
    Text(text, color = if (z == null) Palette.Muted else Palette.Soft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun QuickButtons(engine: Engine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val af by engine.autofocusOn.collectAsState()
        PadTile(
            when (af) {
                true -> "AF ligado"
                false -> "AF desligado"
                null -> "Autofoco"
            },
            Modifier.weight(1f), height = 40.dp, selected = af == true,
        ) { engine.autofocus() }
        PadTile("Brilho −", Modifier.weight(1f), height = 40.dp) { engine.brightness(-1) }
        PadTile("Brilho +", Modifier.weight(1f), height = 40.dp) { engine.brightness(1) }
        PadTile("Home", Modifier.weight(1f), height = 40.dp) { engine.home() }
    }
}

@Composable
private fun PresetRow(settings: AppSettings, engine: Engine, onEdit: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in 1..AppSettings.PRESET_COUNT) {
            PadTile(
                settings.presetNames.getOrElse(n - 1) { "$n" },
                Modifier.weight(1f),
                height = 40.dp,
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
                        height = 40.dp,
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
