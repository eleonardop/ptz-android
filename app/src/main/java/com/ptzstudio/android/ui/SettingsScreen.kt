package com.ptzstudio.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ptzstudio.android.BuildConfig
import com.ptzstudio.android.Engine
import com.ptzstudio.android.core.MotionConfig

@Composable
fun SettingsScreen(engine: Engine, onBack: () -> Unit, onMapping: () -> Unit) {
    val settings by engine.settings.collectAsState()
    val gamepad by engine.gamepad.collectAsState()
    val zoom by engine.zoomLevel.collectAsState()
    val m = settings.motion
    fun set(new: MotionConfig) = engine.setMotion(new)

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        ScreenBar("Ajustes", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Vídeo ao vivo")
            Panel(Modifier.fillMaxWidth()) {
                SwitchRow("Mostrar o vídeo da câmera na tela de controle", settings.videoEnabled) { engine.setVideoEnabled(it) }
                Hint("O endereço RTSP fica no cadastro de cada câmera (Câmeras › Editar).")
            }

            SectionTitle("Manter o controle ligado")
            Panel(Modifier.fillMaxWidth()) {
                val pulse by engine.padPulse.collectAsState()
                SwitchRow("Sinal de vibração zero a cada 3 minutos", settings.keepPadAwake) { on -> engine.update { it.copy(keepPadAwake = on) } }
                Hint("O controle recebe um sinal sem vibrar de verdade, para não desligar sozinho por falta de uso durante o culto.")
                pulse?.let { Text("Último sinal: $it", color = Palette.Soft, fontSize = 13.sp) }
                PadTile("Testar agora", Modifier.fillMaxWidth()) { engine.pulsePads() }
            }

            SectionTitle("Brilho")
            Panel(Modifier.fillMaxWidth()) {
                SwitchRow("Usar o modo Brilho da câmera", settings.brightMode) { engine.setBrightMode(it) }
                Hint(
                    "Desligado (padrão): os botões de brilho mudam a compensação de exposição, mantendo a exposição automática. " +
                        "Se o brilho não mudar na sua câmera, ligue esta opção: ela coloca a câmera no modo Brilho, em que a " +
                        "claridade sobe e desce direto. Ao desligar, a câmera volta à exposição automática.",
                )
            }

            SectionTitle("Controle físico")
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusChip(
                        gamepad ?: "Nenhum controle conectado",
                        if (gamepad != null) Palette.Green else Palette.Muted,
                        Palette.Chip,
                        if (gamepad != null) Palette.Text else Palette.Muted,
                    )
                }
                Hint("Conecte o GameSir pelo Bluetooth do celular (modo Android/Xbox) ou por cabo USB-C.")
                PadTile("Mapear botões do controle", Modifier.fillMaxWidth(), onClick = onMapping)
                SwitchRow("Segurança: parar se o controle parar de mandar sinal", m.inputWatchdogEnabled) { set(m.copy(inputWatchdogEnabled = it)) }
                Hint(
                    "Se um analógico ou gatilho estiver fora do centro e o controle ficar 5 s sem mandar nenhum sinal " +
                        "(Bluetooth travado), a câmera para. Se ela parar sozinha em movimentos longos e lentos, desligue.",
                )
            }

            SectionTitle("Movimento")
            Panel(Modifier.fillMaxWidth()) {
                LevelSlider("Velocidade do Pan/Tilt", m.speedLevel(engine.maxPanSpeed), engine.maxPanSpeed, Modifier.fillMaxWidth()) {
                    set(m.withSpeedLevel(it, engine.maxPanSpeed))
                }
                PercentSlider("Aceleração gradual", m.rampPercent, 0..100) { set(m.copy(rampPercent = it)) }
                Hint("0% = começa na velocidade final. 100% = leva cerca de 2 segundos para chegar à velocidade final.")
                PercentSlider("Zona morta", m.deadzonePercent, 0..30) { set(m.copy(deadzonePercent = it)) }
                Hint("Folga do centro do analógico que é ignorada (evita a câmera andar sozinha).")
                PercentSlider("Movimento fino", m.finePercent, 5..100) { set(m.copy(finePercent = it)) }
                LevelSlider("Velocidade do zoom", m.zoomLevelSetting(engine.maxZoomSpeed), engine.maxZoomSpeed, Modifier.fillMaxWidth()) {
                    set(m.withZoomLevelSetting(it, engine.maxZoomSpeed))
                }
                Hint("Vale para os gatilhos e para os botões de zoom. Também dá para mudar na tela principal.")
                PercentSlider("Trava de diagonal", m.diagonalLockDeg, 0..44, unit = "°") { set(m.copy(diagonalLockDeg = it)) }
                Hint("Se o analógico está quase na horizontal ou na vertical, o outro eixo é ignorado (sem diagonais sem querer).")
                SwitchRow("Inverter vertical (Tilt)", m.invertTilt) { set(m.copy(invertTilt = it)) }
                SwitchRow("Inverter horizontal (Pan)", m.invertPan) { set(m.copy(invertPan = it)) }
            }

            SectionTitle("Pan/Tilt mais lento com zoom alto")
            Panel(Modifier.fillMaxWidth()) {
                SwitchRow("Reduzir a velocidade conforme o zoom aumenta", m.zoomSlowdownEnabled) { set(m.copy(zoomSlowdownEnabled = it)) }
                PercentSlider("Redução com o zoom no máximo", m.zoomSlowdownPercent, 0..95) { set(m.copy(zoomSlowdownPercent = it)) }
                Hint(
                    "Ex.: 80% = com o zoom todo aproximado, o Pan/Tilt anda a 20% da velocidade. A redução começa leve e " +
                        "aumenta mais no fim do zoom. O app pergunta a posição do zoom à câmera várias vezes por segundo.",
                )
                Text(
                    when (val z = zoom) {
                        null -> "Zoom agora: a câmera não informou a posição. Sem essa informação a redução fica desligada."
                        else -> "Zoom agora: ${(z * 100).toInt()}% · Pan/Tilt a ${(m.zoomSlowdownFactor(z) * 100).toInt()}% da velocidade"
                    },
                    color = Palette.Soft, fontSize = 13.sp,
                )
                PadTile("Calibrar: o zoom está todo aproximado agora", Modifier.fillMaxWidth()) {
                    engine.calibrateZoomMax()
                }
                Hint("Use se o zoom nunca chega a 100%: aproxime o zoom até o fim e toque no botão acima.")
            }

            Text(
                "PTZ Studio Android ${BuildConfig.VERSION_NAME}",
                color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PercentSlider(label: String, value: Int, range: IntRange, unit: String = "%", onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("$value$unit", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Palette.AccentBright, activeTrackColor = Palette.AccentBright),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.Muted, fontSize = 12.sp)
}
