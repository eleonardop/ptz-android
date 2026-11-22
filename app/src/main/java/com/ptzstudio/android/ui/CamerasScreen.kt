package com.ptzstudio.android.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ptzstudio.android.Engine
import com.ptzstudio.android.core.CameraConfig
import com.ptzstudio.android.core.FoundCamera
import com.ptzstudio.android.core.Transport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Câmeras salvas, câmeras encontradas na rede e cadastro manual pelo IP. */
@Composable
fun CamerasScreen(engine: Engine, onBack: () -> Unit) {
    val settings by engine.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val online = remember { mutableStateMapOf<String, Boolean>() }
    var found by remember { mutableStateOf<List<FoundCamera>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CameraConfig?>(null) }
    var removing by remember { mutableStateOf<CameraConfig?>(null) }

    fun refresh() {
        if (searching) return
        searching = true
        scope.launch {
            val probes = settings.cameras.map { c -> async { c.id to engine.probe(c) } }
            val discovery = async { engine.discover() }
            probes.awaitAll().forEach { (id, ok) -> online[id] = ok }
            found = discovery.await()
            searching = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        ScreenBar("Câmeras", onBack) {
            IconButton(onClick = { refresh() }) { Icon(Icons.Default.Search, "Procurar câmeras na rede", tint = Palette.Text) }
            IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Atualizar", tint = Palette.Text) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Salvas")
            if (settings.cameras.isEmpty()) {
                Text(
                    "Nenhuma câmera salva. Use \"Adicionar câmera pelo IP\" ou adicione uma das encontradas na rede.",
                    color = Palette.Muted, fontSize = 13.sp,
                )
            }
            for (c in settings.cameras) {
                SavedCameraCard(
                    cam = c,
                    inUse = c.id == settings.selectedCamera?.id,
                    online = online[c.id],
                    onUse = { engine.selectCamera(c.id) },
                    onEdit = { editing = c },
                    onRemove = { removing = c },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Encontradas na rede", Modifier.weight(1f))
                if (searching) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.AccentText)
                    Text("Procurando…", color = Palette.Muted, fontSize = 12.sp)
                }
            }
            val savedIps = settings.cameras.map { it.ip.trim() }.toSet()
            val news = found.filter { it.ip !in savedIps }
            if (!searching && news.isEmpty()) {
                Text(
                    "Nenhuma câmera nova encontrada (a busca usa ONVIF; nem toda câmera responde). Você pode adicionar pelo IP.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
            }
            for (f in news) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Card)
                        .border(1.dp, Palette.Border, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(f.ip, color = Palette.Muted, fontSize = 12.sp)
                        Text(
                            if (f.rtspUrl.isNotBlank()) "Vídeo: ${f.rtspUrl}" else "Vídeo: não encontrado (dá para procurar ao adicionar)",
                            color = if (f.rtspUrl.isNotBlank()) Palette.Soft else Palette.Muted, fontSize = 11.sp,
                        )
                    }
                    PadTile("Adicionar", Modifier.width(100.dp), selected = true) {
                        editing = CameraConfig(name = f.name, ip = f.ip, rtspUrl = f.rtspUrl)
                    }
                }
            }

            PadTile("Adicionar câmera pelo IP", Modifier.fillMaxWidth(), height = 48.dp) {
                editing = CameraConfig(name = "Câmera ${settings.cameras.size + 1}")
            }
        }
    }

    editing?.let { c ->
        CameraEditDialog(c, onDismiss = { editing = null }, onFind = { engine.findRtsp(it) }) { saved ->
            engine.saveCamera(saved)
            editing = null
            scope.launch { online[saved.id] = engine.probe(saved) }
        }
    }
    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Palette.Card,
            title = { Text("Remover ${c.name}?", color = Palette.Text) },
            text = { Text("A câmera sai da lista deste celular. Nada muda na câmera.", color = Palette.Soft) },
            confirmButton = { TextButton(onClick = { engine.removeCamera(c.id); removing = null }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SavedCameraCard(
    cam: CameraConfig,
    inUse: Boolean,
    online: Boolean?,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Card)
            .border(BorderStroke(if (inUse) 1.5.dp else 1.dp, if (inUse) Palette.Accent else Palette.Line), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(14.dp).clip(CircleShape).background(
                    when (online) {
                        true -> Palette.Green
                        false -> Palette.Red
                        null -> Palette.Muted
                    },
                ),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(cam.name, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(cam.ip.ifBlank { "(sem IP)" }, color = Palette.Soft, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("${if (cam.transport == Transport.RAW_UDP) "VISCA UDP" else "VISCA over IP"} · ${cam.port}")
                    if (cam.rtspUrl.isNotBlank()) Tag("Vídeo RTSP")
                }
            }
            Text(
                when (online) {
                    true -> "Online"
                    false -> "Sem resposta"
                    null -> "…"
                },
                color = when (online) {
                    true -> Palette.GreenText
                    false -> Palette.RedText
                    null -> Palette.Muted
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (inUse) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Palette.AccentDim),
                    contentAlignment = Alignment.Center,
                ) { Text("Em uso", color = Palette.AccentText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            } else {
                PadTile("Usar esta câmera", Modifier.weight(1f), selected = true, onClick = onUse)
            }
            PadTile("Editar", Modifier.width(84.dp), onClick = onEdit)
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remover câmera", tint = Palette.Soft) }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        color = Palette.Soft,
        fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Chip).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CameraEditDialog(
    initial: CameraConfig,
    onDismiss: () -> Unit,
    onFind: suspend (String) -> String?,
    onSave: (CameraConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var finding by remember { mutableStateOf(false) }
    var findResult by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(initial.name) }
    var ip by remember { mutableStateOf(initial.ip) }
    var transport by remember { mutableStateOf(initial.transport) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var address by remember { mutableStateOf(initial.viscaAddress.toString()) }
    var rtsp by remember { mutableStateOf(initial.rtspUrl) }
    val portNum = port.toIntOrNull()
    val addrNum = address.toIntOrNull()
    val valid = ip.isNotBlank() && portNum != null && portNum in 1..65535 && addrNum != null && addrNum in 1..7

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        title = { Text("Câmera", color = Palette.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(
                    ip, { ip = it.trim() }, label = { Text("Endereço IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text("Protocolo", color = Palette.Muted, fontSize = 12.sp)
                for (t in Transport.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (port == transport.defaultPort.toString()) port = t.defaultPort.toString()
                            transport = t
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = transport == t, onClick = null)
                        Text(t.label, color = Palette.Text, fontSize = 14.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Porta") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        address, { address = it.filter(Char::isDigit).take(1) }, label = { Text("Endereço VISCA") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    rtsp, { rtsp = it.trim() }, label = { Text("Endereço RTSP do vídeo (opcional)") },
                    placeholder = { Text("rtsp://192.168.4.163:554/live/av0") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PadTile(if (finding) "Procurando…" else "Procurar endereço do vídeo", Modifier.weight(1f)) {
                        if (!finding && ip.isNotBlank()) {
                            finding = true
                            findResult = null
                            scope.launch {
                                val r = onFind(ip)
                                finding = false
                                if (r != null) {
                                    rtsp = r
                                    findResult = "Achado: $r"
                                } else {
                                    findResult = "Não achei sozinho. A câmera pode pedir senha no vídeo: veja o manual dela."
                                }
                            }
                        }
                    }
                }
                findResult?.let { Text(it, color = Palette.Soft, fontSize = 12.sp) }
                Text(
                    "Se a câmera pede usuário e senha no vídeo, use rtsp://usuario:senha@IP:554/caminho. " +
                        "O fluxo secundário (ex.: /live/av1 em vez de /live/av0) é menor e chega um pouco mais rápido no celular.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                if (!valid) Text("Preencha o IP, a porta (1 a 65535) e o endereço VISCA (1 a 7).", color = Palette.Amber, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { "Câmera" },
                            ip = ip.trim(),
                            transport = transport,
                            port = portNum ?: transport.defaultPort,
                            viscaAddress = addrNum ?: 1,
                            rtspUrl = rtsp.trim(),
                        ),
                    )
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Barra do topo das telas secundárias, com botão de voltar. */
@Composable
fun ScreenBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Bar).height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Palette.Text)
        }
        Text(title, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        actions()
    }
}

