package com.ptzstudio.android.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/** Destino dos pacotes VISCA (a rede no app; uma lista nos testes). */
fun interface PacketSender {
    fun send(packet: ByteArray)
}

/** Limites da câmera em uso. */
data class CameraLimits(
    val address: Int = 1,
    val maxPan: Int = 24,
    val maxTilt: Int = 20,
    val maxZoom: Int = 8,
)

/** Ajustes de movimento guardados no aparelho. */
data class MotionConfig(
    /** Velocidade de Pan/Tilt, 1% a 100% da velocidade máxima da câmera. */
    val speedPercent: Int = 65,
    /** Aceleração gradual: 0% = sem rampa; 100% = cerca de 2 s até a velocidade máxima. */
    val rampPercent: Int = 40,
    /** Zona morta dos analógicos, em %. */
    val deadzonePercent: Int = 12,
    /** Força do "movimento fino", em % da inclinação. */
    val finePercent: Int = 35,
    /** Velocidade do zoom (gatilhos e botões), 1% a 100% da velocidade máxima da câmera. */
    val zoomSpeedPercent: Int = 70,
    /** Reduz o Pan/Tilt automaticamente quando o zoom aumenta. */
    val zoomSlowdownEnabled: Boolean = true,
    /** Quanto o Pan/Tilt é reduzido com o zoom no máximo (0% a 95%). */
    val zoomSlowdownPercent: Int = 80,
    /** Valor que a câmera informa com o zoom todo aproximado (calibrável). */
    val zoomMaxPosition: Int = 16384,
    val invertPan: Boolean = false,
    val invertTilt: Boolean = false,
    /** Trava de diagonal: abaixo deste ângulo o eixo secundário é ignorado (0 = desligada). */
    val diagonalLockDeg: Int = 18,
) {
    fun sanitized(): MotionConfig = copy(
        speedPercent = speedPercent.coerceIn(1, 100),
        rampPercent = rampPercent.coerceIn(0, 100),
        deadzonePercent = deadzonePercent.coerceIn(0, 50),
        finePercent = finePercent.coerceIn(5, 100),
        zoomSpeedPercent = zoomSpeedPercent.coerceIn(1, 100),
        zoomSlowdownPercent = zoomSlowdownPercent.coerceIn(0, 95),
        zoomMaxPosition = zoomMaxPosition.coerceIn(256, 0xFFFF),
        diagonalLockDeg = diagonalLockDeg.coerceIn(0, 44),
    )

    /**
     * Quanto sobra da velocidade de Pan/Tilt para um zoom de 0 (afastado) a 1 (todo aproximado).
     * A redução cresce mais no fim do zoom, onde a imagem fica mais sensível.
     */
    fun zoomSlowdownFactor(zoom: Double?): Double {
        if (!zoomSlowdownEnabled || zoom == null) return 1.0
        val z = zoom.coerceIn(0.0, 1.0)
        val r = zoomSlowdownPercent.coerceIn(0, 95) / 100.0
        return 1.0 - r * Math.pow(z, 1.5)
    }

    /** Segundos para ir do repouso à velocidade máxima. */
    val rampSeconds: Double get() = rampPercent.coerceIn(0, 100) / 100.0 * 2.0
}

/**
 * Converte as entradas (controle físico, joystick da tela, botões) em comandos VISCA.
 *
 * - Movimento gradual: a velocidade segue a inclinação do analógico (curva) e sobe em rampa.
 * - Zoom gradual: a velocidade segue a pressão do gatilho; ao soltar, para na hora.
 * - Ao soltar tudo, a parada é enviada e repetida algumas vezes (UDP pode perder pacotes).
 * - Enquanto se move, o comando é reenviado a cada 400 ms para a câmera não "esquecer".
 *
 * Todos os métodos podem ser chamados de qualquer thread.
 */
class PtzController(private val sender: PacketSender) {
    @Volatile var limits: CameraLimits = CameraLimits()
    @Volatile var config: MotionConfig = MotionConfig()

    /** Posição atual do zoom, de 0 (afastado) a 1 (todo aproximado); nulo = desconhecida (sem redução). */
    @Volatile var zoomLevel: Double? = null

    private val lock = Any()

    // Entradas
    private var physX = 0.0
    private var physY = 0.0
    private var physFine = false
    private var virtX = 0.0
    private var virtY = 0.0
    private var owner: Source? = null
    private var padPan = 0
    private var padTilt = 0
    private var screenPan = 0
    private var screenTilt = 0
    private var analogZoom = 0.0
    private var padZoom = 0
    private var screenZoom = 0
    private var padFocus = 0
    private var screenFocus = 0

    // Saída
    private val pan = AxisChannel()
    private val tilt = AxisChannel()
    private var sentPan = 0
    private var sentTilt = 0
    private var sentZoom = 0
    private var sentFocus = 0
    private var lastPtMs = Long.MIN_VALUE / 2
    private var lastZoomMs = Long.MIN_VALUE / 2
    private val ptRepeat = StopRepeat()
    private val zoomRepeat = StopRepeat()
    private val focusRepeat = StopRepeat()

    private enum class Source { PHYSICAL, VIRTUAL }

    val lastSentPan: Int get() = synchronized(lock) { sentPan }
    val lastSentTilt: Int get() = synchronized(lock) { sentTilt }
    val lastSentZoom: Int get() = synchronized(lock) { sentZoom }

    /** Analógico do controle físico (x: direita +, y: cima +). fine = movimento fino. */
    fun setPhysicalStick(x: Double, y: Double, fine: Boolean = false) = synchronized(lock) {
        physX = clean(x); physY = clean(y); physFine = fine
    }

    /** Joystick da tela (x: direita +, y: cima +). */
    fun setVirtualStick(x: Double, y: Double) = synchronized(lock) {
        virtX = clean(x); virtY = clean(y)
    }

    /** Botões do controle físico para Pan/Tilt (-1, 0, 1). */
    fun setPadPanTilt(panDir: Int, tiltDir: Int) = synchronized(lock) {
        padPan = panDir.coerceIn(-1, 1); padTilt = tiltDir.coerceIn(-1, 1)
    }

    /** Setas da tela (-1, 0, 1). */
    fun setScreenPanTilt(panDir: Int, tiltDir: Int) = synchronized(lock) {
        screenPan = panDir.coerceIn(-1, 1); screenTilt = tiltDir.coerceIn(-1, 1)
    }

    /** Zoom proporcional (gatilhos ou analógico): -1 afastar .. +1 aproximar. */
    fun setAnalogZoom(z: Double) = synchronized(lock) { analogZoom = clean(z) }

    fun setPadZoom(dir: Int) = synchronized(lock) { padZoom = dir.coerceIn(-1, 1) }
    fun setScreenZoom(dir: Int) = synchronized(lock) { screenZoom = dir.coerceIn(-1, 1) }
    fun setPadFocus(dir: Int) = synchronized(lock) { padFocus = dir.coerceIn(-1, 1) }
    fun setScreenFocus(dir: Int) = synchronized(lock) { screenFocus = dir.coerceIn(-1, 1) }

    /** Zera todas as entradas (app em segundo plano, controle desconectado...). A parada sai no próximo ciclo. */
    fun releaseAll() = synchronized(lock) {
        physX = 0.0; physY = 0.0; virtX = 0.0; virtY = 0.0; owner = null
        padPan = 0; padTilt = 0; screenPan = 0; screenTilt = 0
        analogZoom = 0.0; padZoom = 0; screenZoom = 0; padFocus = 0; screenFocus = 0
    }

    /** "Parar câmera": zera as entradas e manda a parada de todos os eixos agora. */
    fun stopNow(nowMs: Long) = synchronized(lock) {
        releaseAll()
        pan.reset(); tilt.reset()
        val a = limits.address
        sender.send(ViscaCommands.panTiltStop(a))
        sender.send(ViscaCommands.zoom(a, 0))
        if (sentFocus != 0) sender.send(ViscaCommands.focus(a, 0))
        sentPan = 0; sentTilt = 0; sentZoom = 0; sentFocus = 0
        lastPtMs = nowMs; lastZoomMs = nowMs
        ptRepeat.arm(); zoomRepeat.arm()
    }

    /** Comando único (preset, Home, autofoco, brilho). */
    fun sendOnce(packet: ByteArray) = sender.send(packet)

    /** Um ciclo de controle. Chamado a cada ~20 ms. */
    fun tick(dtSeconds: Double, nowMs: Long) = synchronized(lock) {
        val cfg = config.sanitized()
        val lim = limits
        val dz = cfg.deadzonePercent / 100.0

        // 1) Quem comanda o movimento: quem saiu do centro primeiro segura até voltar ao centro.
        val physActive = hypot(physX, physY) > dz
        val virtActive = hypot(virtX, virtY) > 0.02
        if (owner == Source.PHYSICAL && !physActive) owner = null
        if (owner == Source.VIRTUAL && !virtActive) owner = null
        if (owner == null) owner = when {
            physActive -> Source.PHYSICAL
            virtActive -> Source.VIRTUAL
            else -> null
        }
        var x: Double
        var y: Double
        when (owner) {
            Source.PHYSICAL -> {
                val k = if (physFine) cfg.finePercent / 100.0 else 1.0
                x = AxisMath.deadzone(physX, dz) * k
                y = AxisMath.deadzone(physY, dz) * k
            }
            Source.VIRTUAL -> {
                x = AxisMath.deadzone(virtX, 0.02)
                y = AxisMath.deadzone(virtY, 0.02)
            }
            null -> { x = 0.0; y = 0.0 }
        }
        val digPan = (padPan + screenPan).coerceIn(-1, 1)
        val digTilt = (padTilt + screenTilt).coerceIn(-1, 1)
        if (digPan != 0 || digTilt != 0) {
            // Botões/setas têm prioridade sobre o analógico enquanto apertados.
            x = digPan.toDouble(); y = digTilt.toDouble()
        } else {
            val locked = AxisMath.directionLock(x, y, cfg.diagonalLockDeg.toDouble())
            x = locked.first; y = locked.second
        }
        if (cfg.invertPan) x = -x
        if (cfg.invertTilt) y = -y

        // 2) Velocidade-alvo com curva e rampa.
        val scale = cfg.speedPercent / 100.0 * cfg.zoomSlowdownFactor(zoomLevel)
        val panMax = (lim.maxPan.coerceIn(1, ViscaCommands.MAX_PAN_SPEED) * scale).coerceAtLeast(1.0)
        val tiltMax = (lim.maxTilt.coerceIn(1, ViscaCommands.MAX_TILT_SPEED) * scale).coerceAtLeast(1.0)
        val ramp = cfg.rampSeconds
        val panAxis = AxisSettings(
            deadzone = 0.0, minSpeed = 1, maxSpeed = panMax.roundToInt().coerceAtLeast(1), curveExponent = 2.0,
            accelPerSecond = if (ramp <= 0) 0.0 else panMax / ramp, decelPerSecond = 120.0,
        )
        val tiltAxis = panAxis.copy(
            maxSpeed = tiltMax.roundToInt().coerceAtLeast(1),
            accelPerSecond = if (ramp <= 0) 0.0 else tiltMax / ramp,
        )
        val p = pan.step(AxisMath.targetSpeed(x, panAxis), dtSeconds, panAxis)
        val t = tilt.step(AxisMath.targetSpeed(y, tiltAxis), dtSeconds, tiltAxis)

        // 3) Zoom: pressão do gatilho -> velocidade (linear, sem rampa: soltar para na hora).
        var z = analogZoom
        val btnZoom = (padZoom + screenZoom).coerceIn(-1, 1)
        if (btnZoom != 0) z = btnZoom.toDouble()
        z = AxisMath.deadzone(z.coerceIn(-1.0, 1.0), 0.05)
        val cameraZoomMax = lim.maxZoom.coerceIn(1, ViscaCommands.MAX_ZOOM_SPEED)
        val zoomMax = (cameraZoomMax * cfg.zoomSpeedPercent / 100.0).roundToInt().coerceIn(1, cameraZoomMax)
        val zoomSpeed = if (z == 0.0) 0 else sign(z).toInt() * (1 + ((zoomMax - 1) * abs(z)).roundToInt()).coerceIn(1, zoomMax)

        applyPanTilt(lim, toInt(p, lim.maxPan), toInt(t, lim.maxTilt), nowMs)
        applyZoom(lim, zoomSpeed, nowMs)
        applyFocus(lim, (padFocus + screenFocus).coerceIn(-1, 1), nowMs)
    }

    private fun applyPanTilt(lim: CameraLimits, p: Int, t: Int, nowMs: Long) {
        if (p == 0 && t == 0) {
            if (sentPan != 0 || sentTilt != 0) {
                sender.send(ViscaCommands.panTiltStop(lim.address))
                sentPan = 0; sentTilt = 0; lastPtMs = nowMs; ptRepeat.arm()
            } else if (ptRepeat.due(nowMs)) {
                sender.send(ViscaCommands.panTiltStop(lim.address))
            }
            return
        }
        val changed = p != sentPan || t != sentTilt
        val since = nowMs - lastPtMs
        if ((changed && since >= COMMAND_INTERVAL_MS) || (!changed && since >= KEEP_ALIVE_MS)) {
            sender.send(ViscaCommands.panTiltDrive(lim.address, p, t, lim.maxPan, lim.maxTilt))
            sentPan = p; sentTilt = t; lastPtMs = nowMs; ptRepeat.cancel()
        }
    }

    private fun applyZoom(lim: CameraLimits, z: Int, nowMs: Long) {
        if (z == 0) {
            if (sentZoom != 0) {
                // Soltar o gatilho: parada imediata, sem esperar o intervalo mínimo.
                sender.send(ViscaCommands.zoom(lim.address, 0))
                sentZoom = 0; lastZoomMs = nowMs; zoomRepeat.arm()
            } else if (zoomRepeat.due(nowMs)) {
                sender.send(ViscaCommands.zoom(lim.address, 0))
            }
            return
        }
        val changed = z != sentZoom
        val since = nowMs - lastZoomMs
        if ((changed && since >= COMMAND_INTERVAL_MS) || (!changed && since >= KEEP_ALIVE_MS)) {
            sender.send(ViscaCommands.zoom(lim.address, z, lim.maxZoom))
            sentZoom = z; lastZoomMs = nowMs; zoomRepeat.cancel()
        }
    }

    private fun applyFocus(lim: CameraLimits, dir: Int, nowMs: Long) {
        if (dir != sentFocus) {
            if (dir != 0) {
                // Foco manual: desliga o automático antes de mover o foco.
                if (sentFocus == 0) sender.send(ViscaCommands.autoFocus(lim.address, false))
                sender.send(ViscaCommands.focus(lim.address, dir))
                focusRepeat.cancel()
            } else {
                sender.send(ViscaCommands.focus(lim.address, 0))
                focusRepeat.arm()
            }
            sentFocus = dir
        } else if (dir == 0 && focusRepeat.due(nowMs)) {
            sender.send(ViscaCommands.focus(lim.address, 0))
        }
    }

    private fun toInt(speed: Double, cameraMax: Int): Int {
        if (abs(speed) < 0.5) return 0
        val i = abs(speed).roundToInt().coerceAtLeast(1)
        return sign(speed).toInt() * minOf(i, cameraMax.coerceAtLeast(1))
    }

    private fun clean(v: Double): Double = if (v.isNaN()) 0.0 else v.coerceIn(-1.0, 1.0)

    /** Reforço da parada: repete em intervalos crescentes (~5,5 s no total) enquanto o eixo continua parado. */
    private class StopRepeat {
        private var at = 0L
        private var anchored = false
        private var step = SCHEDULE.size

        fun arm() { step = 0; anchored = false }
        fun cancel() { step = SCHEDULE.size }

        fun due(nowMs: Long): Boolean {
            if (step >= SCHEDULE.size) return false
            if (!anchored) { at = nowMs; anchored = true; return false }
            if (nowMs - at < SCHEDULE[step]) return false
            step++
            return true
        }

        companion object {
            val SCHEDULE = longArrayOf(30, 90, 250, 600, 1200, 2000, 3500, 5500)
        }
    }

    companion object {
        const val KEEP_ALIVE_MS = 400L
        const val COMMAND_INTERVAL_MS = 40L
    }
}

/** Estado do controle físico num instante. y: cima = +. */
data class PadState(
    val pressed: Set<PadButton> = emptySet(),
    val lsX: Double = 0.0,
    val lsY: Double = 0.0,
    val rsX: Double = 0.0,
    val rsY: Double = 0.0,
    val lt: Double = 0.0,
    val rt: Double = 0.0,
)

/**
 * Aplica o mapeamento (botão -> função) ao controlador.
 * Funções contínuas (movimento, zoom, foco) valem enquanto o botão está apertado.
 * Funções de um toque (preset, Home, autofoco, velocidade, brilho, parar) disparam [onAction] ao apertar.
 */
class MappingEngine(
    private val controller: PtzController,
    private val onAction: (PadAction) -> Unit,
) {
    private var previouslyActive: Set<PadButton> = emptySet()

    fun update(state: PadState, mapping: Map<PadButton, PadAction>) {
        // Analógicos
        var moveX = 0.0
        var moveY = 0.0
        var fine = false
        var zoom = 0.0
        for (stick in listOf(PadButton.LS, PadButton.RS)) {
            val sx = if (stick == PadButton.LS) state.lsX else state.rsX
            val sy = if (stick == PadButton.LS) state.lsY else state.rsY
            val (cx, cy, isFine) = when (mapping[stick]) {
                PadAction.STICK_MOVE -> Triple(sx, sy, false)
                PadAction.STICK_FINE -> Triple(sx, sy, true)
                PadAction.STICK_PAN_ONLY -> Triple(sx, 0.0, false)
                PadAction.STICK_TILT_ONLY -> Triple(0.0, sy, false)
                PadAction.STICK_ZOOM -> {
                    zoom += sy
                    Triple(0.0, 0.0, false)
                }
                else -> Triple(0.0, 0.0, false)
            }
            // O analógico mais inclinado comanda (os dois nunca se somam).
            if (hypot(cx, cy) > hypot(moveX, moveY)) {
                moveX = cx; moveY = cy; fine = isFine
            }
        }

        // Gatilhos: pressão -> zoom, ou viram "botão" acima de 50%.
        val active = state.pressed.toMutableSet()
        for (trig in listOf(PadButton.LT, PadButton.RT)) {
            val v = (if (trig == PadButton.LT) state.lt else state.rt).coerceIn(0.0, 1.0)
            when (mapping[trig]) {
                PadAction.ZOOM_IN_PRESSURE -> zoom += v
                PadAction.ZOOM_OUT_PRESSURE -> zoom -= v
                else -> if (v > TRIGGER_AS_BUTTON) active += trig else active -= trig
            }
        }

        // Botões contínuos
        var panDir = 0
        var tiltDir = 0
        var zoomDir = 0
        var focusDir = 0
        for (b in active) {
            when (mapping[b]) {
                PadAction.PAN_LEFT -> panDir -= 1
                PadAction.PAN_RIGHT -> panDir += 1
                PadAction.TILT_UP -> tiltDir += 1
                PadAction.TILT_DOWN -> tiltDir -= 1
                PadAction.ZOOM_IN -> zoomDir += 1
                PadAction.ZOOM_OUT -> zoomDir -= 1
                PadAction.FOCUS_FAR -> focusDir += 1
                PadAction.FOCUS_NEAR -> focusDir -= 1
                else -> {}
            }
        }

        controller.setPhysicalStick(moveX, moveY, fine)
        controller.setAnalogZoom(zoom.coerceIn(-1.0, 1.0))
        controller.setPadPanTilt(panDir.coerceIn(-1, 1), tiltDir.coerceIn(-1, 1))
        controller.setPadZoom(zoomDir.coerceIn(-1, 1))
        controller.setPadFocus(focusDir.coerceIn(-1, 1))

        // Funções de um toque: só na hora em que o botão é apertado.
        for (b in active - previouslyActive) {
            val a = mapping[b] ?: continue
            if (a in ONE_SHOT) onAction(a)
        }
        previouslyActive = active
    }

    /** Esquece o que estava apertado (ao trocar de tela ou perder o controle). */
    fun reset() {
        previouslyActive = emptySet()
    }

    companion object {
        const val TRIGGER_AS_BUTTON = 0.5
        val ONE_SHOT: Set<PadAction> = setOf(
            PadAction.AUTOFOCUS, PadAction.SPEED_UP, PadAction.SPEED_DOWN,
            PadAction.BRIGHT_UP, PadAction.BRIGHT_DOWN, PadAction.HOME, PadAction.STOP,
        ) + (1..8).map { PadAction.preset(it) }
    }
}
