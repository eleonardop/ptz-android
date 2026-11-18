package com.ptzstudio.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ptzstudio.android.DetectEvent
import com.ptzstudio.android.Engine
import com.ptzstudio.android.core.PadAction
import com.ptzstudio.android.core.PadButton
import com.ptzstudio.android.core.PadGroup
import kotlinx.coroutines.delay

/** Cada botão do controle com a sua função. Começa com a lista pronta; o usuário muda o que quiser. */
@Composable
fun MappingScreen(engine: Engine, onBack: () -> Unit) {
    val settings by engine.settings.collectAsState()
    val active by engine.active.collectAsState()
    val gamepad by engine.gamepad.collectAsState()
    var selected by remember { mutableStateOf(PadButton.RT) }
    var detecting by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    // Nesta tela o controle não mexe a câmera: apertar um botão só o seleciona na lista.
    DisposableEffect(Unit) {
        engine.mappingMode = true
        onDispose { engine.mappingMode = false }
    }
    LaunchedEffect(active) {
        if (!detecting) active.firstOrNull()?.let { selected = it }
    }

    val highlight = if (active.isNotEmpty()) active + selected else setOf(selected)
    val pick = remember { { b: PadButton -> selected = b } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        ScreenBar("Mapeamento do controle", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusChip(
                gamepad ?: "Nenhum controle conectado",
                if (gamepad != null) Palette.Green else Palette.Muted,
                Palette.Chip,
                if (gamepad != null) Palette.Text else Palette.Muted,
            )
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Frente")
                ControllerFront(highlight, onTap = pick)
                SectionTitle("Costas")
                ControllerBack(highlight, onTap = pick)
                Text("Toque num botão do desenho para selecioná-lo.", color = Palette.Muted, fontSize = 12.sp)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.AccentDim)
                    .border(1.dp, Palette.Accent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Selecionado: ${selected.badge} · ${selected.label}", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                SelectedFunctionPicker(selected, settings.mapping[selected] ?: PadAction.NONE) { a -> engine.setMapping(selected, a) }
            }
            PadTile("Detectar botão no controle", Modifier.fillMaxWidth(), selected = true) { detecting = true }
            Text(
                "Toque num botão da lista para vê-lo no desenho, ou aperte o botão no controle para encontrá-lo.",
                color = Palette.Muted, fontSize = 12.sp,
            )

            for (group in PadGroup.entries) {
                SectionTitle(group.title, Modifier.padding(top = 6.dp))
                for (b in PadButton.entries.filter { it.group == group }) {
                    MappingRow(
                        button = b,
                        action = settings.mapping[b] ?: PadAction.NONE,
                        selected = b == selected,
                        onSelect = { selected = b },
                        onChange = { a -> selected = b; engine.setMapping(b, a) },
                    )
                }
            }

            PadTile("Restaurar padrão", Modifier.fillMaxWidth().padding(top = 8.dp), height = 48.dp) { confirmReset = true }
        }
    }

    if (detecting) {
        DetectDialog(engine, onClose = { detecting = false }) { b ->
            selected = b
            detecting = false
        }
    }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.Card,
            title = { Text("Restaurar padrão?", color = Palette.Text) },
            text = { Text("Todos os botões voltam para a lista pronta de funções.", color = Palette.Soft) },
            confirmButton = { TextButton(onClick = { engine.resetMapping(); confirmReset = false }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MappingRow(
    button: PadButton,
    action: PadAction,
    selected: Boolean,
    onSelect: () -> Unit,
    onChange: (PadAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.AccentDim else Palette.Card)
            .border(1.dp, if (selected) Palette.Accent else Palette.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.width(160.dp).heightIn(min = 44.dp).clickable(onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.widthIn(min = 40.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Palette.Accent else Palette.Chip)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(button.badge, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Text(button.label, color = Palette.Text, fontSize = 13.sp, lineHeight = 16.sp)
        }
        Box(Modifier.weight(1f)) {
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Ground)
                    .border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
                    .clickable { onSelect(); open = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("${action.label}  ▾", color = Palette.Text, fontSize = 12.sp, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (a in PadAction.optionsFor(button)) {
                    DropdownMenuItem(
                        text = { Text(a.label, fontWeight = if (a == action) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { open = false; onChange(a) },
                    )
                }
            }
        }
    }
}

/** Espera o usuário apertar um botão no controle (10 s). Botões que o Android não conhece podem ser ensinados. */
@Composable
private fun DetectDialog(engine: Engine, onClose: () -> Unit, onDetected: (PadButton) -> Unit) {
    var result by remember { mutableStateOf<DetectEvent?>(null) }
    var seconds by remember { mutableIntStateOf(10) }
    var round by remember { mutableIntStateOf(0) }
    val pad by engine.pad.collectAsState()

    DisposableEffect(Unit) {
        engine.startDetection { ev -> if (result == null) result = ev }
        onDispose { engine.stopDetection() }
    }
    LaunchedEffect(result, round) {
        if (result == null) {
            seconds = 10
            while (seconds > 0 && result == null) {
                delay(1000)
                seconds--
            }
        }
    }

    // Fica dentro da própria tela (não é uma janela separada): assim os botões do controle continuam chegando ao app.
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Card)
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Detectar botão", color = Palette.Text, fontSize = 22.sp)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val r = result) {
                        null -> if (seconds > 0) {
                            Text("Aperte o botão no controle", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Pode ser qualquer botão, gatilho ou analógico. Os analógicos são reconhecidos quando você empurra até o fim.",
                                color = Palette.Muted, fontSize = 13.sp,
                            )
                            Text("Esperando… $seconds s", color = Palette.Soft, fontSize = 12.sp)
                        } else {
                            Text("Nenhum sinal recebido", color = Palette.RedText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Alguns botões não chegam ao celular: o Home, o Screenshot, o M e os traseiros L4 e R4 podem ser usados pelo " +
                                    "próprio controle ou pelo Android. Para os traseiros, programe no controle o L4 ou o R4 para copiar outro botão e detecte de novo.",
                                color = Palette.Soft, fontSize = 13.sp,
                            )
                        }
                        is DetectEvent.Button -> {
                            Text("${r.button.badge} · ${r.button.label}", color = Palette.GreenText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Detectado", color = Palette.GreenText, fontSize = 13.sp)
                            if (r.button == PadButton.LT || r.button == PadButton.RT) {
                                val v = if (r.button == PadButton.LT) pad.lt else pad.rt
                                Text("Pressão agora: ${(v * 100).toInt()}%", color = Palette.Soft, fontSize = 13.sp)
                                LinearProgressIndicator(progress = { v.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Palette.AccentBright)
                            }
                        }
                        is DetectEvent.UnknownKey -> {
                            Text("Botão não reconhecido pelo Android (código ${r.keyCode})", color = Palette.Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Qual botão do controle é este?", color = Palette.Soft, fontSize = 13.sp)
                            for (b in PadButton.learnable) {
                                PadTile("${b.badge} · ${b.label}", Modifier.fillMaxWidth()) {
                                    engine.learnKey(r.keyCode, b)
                                    result = DetectEvent.Button(b)
                                }
                            }
                        }
                    }
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val r = result
                if (r is DetectEvent.Button) TextButton(onClick = { result = null; round++ }) { Text("Tentar de novo") }
                TextButton(onClick = onClose) { Text("Cancelar") }
                when {
                    r is DetectEvent.Button -> TextButton(onClick = { onDetected(r.button) }) { Text("Configurar") }
                    r == null && seconds <= 0 -> TextButton(onClick = { result = null; round++ }) { Text("Tentar de novo") }
                    else -> {}
                }
            }
        }
    }
}

/** Função do botão selecionado, que pode ser trocada ali mesmo (sem rolar até a lista). */
@Composable
private fun SelectedFunctionPicker(button: PadButton, action: PadAction, onChange: (PadAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Ground)
                .border(1.dp, Palette.Accent, RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Função: ", color = Palette.Muted, fontSize = 12.sp)
            Text(action.label, color = Palette.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text("Mudar ▾", color = Palette.Text, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in PadAction.optionsFor(button)) {
                DropdownMenuItem(
                    text = { Text(a.label, fontWeight = if (a == action) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onChange(a) },
                )
            }
        }
    }
}
