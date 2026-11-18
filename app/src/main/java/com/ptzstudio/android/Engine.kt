package com.ptzstudio.android

import android.content.Context
import android.hardware.input.InputManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ptzstudio.android.core.AppSettings
import com.ptzstudio.android.core.CameraConfig
import com.ptzstudio.android.core.CameraProbe
import com.ptzstudio.android.core.DefaultMapping
import com.ptzstudio.android.core.FoundCamera
import com.ptzstudio.android.core.MappingEngine
import com.ptzstudio.android.core.MotionConfig
import com.ptzstudio.android.core.PadAction
import com.ptzstudio.android.core.PadButton
import com.ptzstudio.android.core.PadState
import com.ptzstudio.android.core.PtzController
import com.ptzstudio.android.core.SettingsCodec
import com.ptzstudio.android.core.ViscaCommands
import com.ptzstudio.android.core.ViscaReplies
import com.ptzstudio.android.core.ViscaUdpClient
import com.ptzstudio.android.core.WsDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.max

/** Estado da ligação com a câmera. */
enum class LinkState(val label: String) {
    NO_CAMERA("Nenhuma câmera"),
    CONNECTING("Conectando…"),
    ONLINE("Câmera conectada"),
    NO_REPLY("Câmera sem resposta"),
}

/** O que a detecção de botão encontrou. */
sealed interface DetectEvent {
    data class Button(val button: PadButton) : DetectEvent
    data class UnknownKey(val keyCode: Int) : DetectEvent
}

/**
 * Coração do app: guarda as configurações, fala com a câmera, lê o controle físico e roda o
 * ciclo de controle (50 vezes por segundo) numa thread própria, independente da tela.
 */
class Engine(private val context: Context) {
    private val prefs = context.getSharedPreferences("ptz_studio", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _settings = MutableStateFlow(SettingsCodec.fromJson(prefs.getString(KEY_SETTINGS, null)))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _link = MutableStateFlow(LinkState.NO_CAMERA)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _gamepad = MutableStateFlow<String?>(null)
    /** Nome do controle físico conectado (nulo = nenhum). */
    val gamepad: StateFlow<String?> = _gamepad.asStateFlow()

    private val _pad = MutableStateFlow(PadState())
    /** Estado ao vivo do controle (para o desenho e a detecção). */
    val pad: StateFlow<PadState> = _pad.asStateFlow()

    private val _active = MutableStateFlow<Set<PadButton>>(emptySet())
    /** Botões, gatilhos e analógicos acionados agora (para acender no desenho). */
    val active: StateFlow<Set<PadButton>> = _active.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Avisos curtos para mostrar na tela. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    @Volatile private var lastReplyAt = 0L
    @Volatile private var connectedAt = 0L
    @Volatile private var foreground = true

    private val _zoomLevel = MutableStateFlow<Double?>(null)
    /** Zoom atual de 0 (afastado) a 1 (todo aproximado), lido da câmera. Nulo = a câmera não informou. */
    val zoomLevel: StateFlow<Double?> = _zoomLevel.asStateFlow()

    @Volatile private var rawZoom: Int? = null
    @Volatile private var lastZoomReplyAt = 0L
    @Volatile private var lastZoomPollMs = 0L
    @Volatile private var zoomFastUntil = 0L
    private var prevSentZoom = 0

    private val client = ViscaUdpClient { data ->
        lastReplyAt = SystemClock.elapsedRealtime()
        ViscaReplies.zoomPosition(data)?.let { onZoomPosition(it) }
    }
    val controller = PtzController(client)
    private val mapping = MappingEngine(controller) { a -> main.post { runAction(a) } }

    private val loop = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ptz-ciclo").apply { isDaemon = true } }
    private var lastTickNs = System.nanoTime()
    private var lastHealthMs = 0L

    // Entrada do controle físico (só na thread principal)
    private val keysDown = mutableSetOf<PadButton>()
    private val hatDown = mutableSetOf<PadButton>()
    private var keyLt = 0.0
    private var keyRt = 0.0
    private var axisState = PadState()

    /** Na tela de mapeamento o controle não mexe a câmera; os botões só são mostrados no desenho. */
    @Volatile var mappingMode = false
        set(value) {
            field = value
            pushPad()
        }

    @Volatile private var detectListener: ((DetectEvent) -> Unit)? = null

    init {
        applyCamera(reconnect = true)
        controller.config = _settings.value.motion
        loop.scheduleAtFixedRate({ safeTick() }, 0, 20, TimeUnit.MILLISECONDS)
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        im.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshGamepad()
            override fun onInputDeviceRemoved(deviceId: Int) {
                refreshGamepad()
                clearPad()
            }
            override fun onInputDeviceChanged(deviceId: Int) = refreshGamepad()
        }, main)
        refreshGamepad()
    }

    // ---------------- Ciclo ----------------

    private fun safeTick() {
        try {
            val nowNs = System.nanoTime()
            val dt = ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.1)
            lastTickNs = nowNs
            val nowMs = SystemClock.elapsedRealtime()
            controller.tick(dt, nowMs)
            pollZoom(nowMs)
            if (foreground && nowMs - lastHealthMs >= HEALTH_INTERVAL_MS) {
                lastHealthMs = nowMs
                health(nowMs)
            }
        } catch (_: Throwable) {
            // O ciclo nunca pode parar por causa de um erro.
        }
    }

    private fun health(nowMs: Long) {
        val cam = _settings.value.selectedCamera
        if (cam == null || cam.ip.isBlank()) {
            _link.value = LinkState.NO_CAMERA
            return
        }
        client.send(ViscaCommands.versionInquiry(cam.viscaAddress))
        _link.value = when {
            nowMs - lastReplyAt < REPLY_WINDOW_MS -> LinkState.ONLINE
            nowMs - connectedAt < REPLY_WINDOW_MS -> LinkState.CONNECTING
            else -> LinkState.NO_REPLY
        }
    }

    /**
     * Pergunta a posição do zoom à câmera: 4 vezes por segundo enquanto o zoom anda (e logo depois),
     * e a cada 1,5 s parado. A resposta ajusta a redução automática do Pan/Tilt.
     */
    private fun pollZoom(nowMs: Long) {
        val cam = _settings.value.selectedCamera ?: return
        if (!foreground || cam.ip.isBlank()) return
        val zoomNow = controller.lastSentZoom
        if (zoomNow == 0 && prevSentZoom != 0) zoomFastUntil = nowMs + 1500
        prevSentZoom = zoomNow
        val interval = if (zoomNow != 0 || nowMs < zoomFastUntil) 250L else 1500L
        if (nowMs - lastZoomPollMs >= interval) {
            lastZoomPollMs = nowMs
            client.send(ViscaCommands.zoomPositionInquiry(cam.viscaAddress))
        }
        if (lastZoomReplyAt != 0L && nowMs - lastZoomReplyAt > ZOOM_STALE_MS) {
            lastZoomReplyAt = 0L
            rawZoom = null
            controller.zoomLevel = null
            _zoomLevel.value = null
        }
    }

    private fun onZoomPosition(pos: Int) {
        lastZoomReplyAt = SystemClock.elapsedRealtime()
        rawZoom = pos
        val max = _settings.value.motion.zoomMaxPosition.coerceAtLeast(1)
        if (pos > max) {
            // A câmera foi além do máximo conhecido: aprende o novo máximo.
            main.post { val m = _settings.value.motion; if (pos > m.zoomMaxPosition) setMotion(m.copy(zoomMaxPosition = pos)) }
        }
        val level = (pos.toDouble() / max).coerceIn(0.0, 1.0)
        controller.zoomLevel = level
        _zoomLevel.value = level
    }

    /** Grava a posição atual do zoom como "zoom máximo" (usar com o zoom todo aproximado). */
    fun calibrateZoomMax(): Boolean {
        val pos = rawZoom
        if (pos == null || pos < 256) {
            say("A câmera não informou a posição do zoom")
            return false
        }
        setMotion(_settings.value.motion.copy(zoomMaxPosition = pos))
        say("Zoom máximo calibrado")
        return true
    }

    private fun applyCamera(reconnect: Boolean) {
        val cam = _settings.value.selectedCamera
        if (cam == null || cam.ip.isBlank()) {
            controller.releaseAll()
            client.close()
            _link.value = LinkState.NO_CAMERA
            return
        }
        controller.limits = cam.limits
        if (reconnect) {
            controller.stopNow(SystemClock.elapsedRealtime())
            client.connect(cam.ip, cam.port, cam.transport)
            connectedAt = SystemClock.elapsedRealtime()
            lastReplyAt = 0L
            lastHealthMs = 0L
            lastZoomReplyAt = 0L
            rawZoom = null
            controller.zoomLevel = null
            _zoomLevel.value = null
            _link.value = LinkState.CONNECTING
        }
    }

    // ---------------- Ciclo de vida ----------------

    fun onForeground() {
        foreground = true
        lastHealthMs = 0L
        refreshGamepad()
    }

    /** App saiu da tela: o Android não entrega mais o controle, então tudo para. */
    fun onBackground() {
        foreground = false
        clearPad()
        controller.releaseAll()
    }

    // ---------------- Configurações ----------------

    fun update(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        prefs.edit().putString(KEY_SETTINGS, SettingsCodec.toJson(new)).apply()
        controller.config = new.motion
        val oldCam = old.selectedCamera
        val newCam = new.selectedCamera
        val reconnect = oldCam?.id != newCam?.id || oldCam?.ip != newCam?.ip ||
            oldCam?.port != newCam?.port || oldCam?.transport != newCam?.transport
        if (reconnect || oldCam?.viscaAddress != newCam?.viscaAddress) applyCamera(reconnect)
    }

    fun setMotion(m: MotionConfig) = update { it.copy(motion = m.sanitized()) }

    fun saveCamera(cam: CameraConfig) = update { s ->
        val list = if (s.cameras.any { it.id == cam.id }) s.cameras.map { if (it.id == cam.id) cam else it } else s.cameras + cam
        s.copy(cameras = list, selectedCameraId = s.selectedCameraId ?: cam.id)
    }

    fun removeCamera(id: String) = update { s ->
        val list = s.cameras.filterNot { it.id == id }
        s.copy(cameras = list, selectedCameraId = if (s.selectedCameraId == id) list.firstOrNull()?.id else s.selectedCameraId)
    }

    fun selectCamera(id: String) = update { it.copy(selectedCameraId = id) }

    fun setMapping(button: PadButton, action: PadAction) = update { s ->
        s.copy(mapping = DefaultMapping.sanitize(s.mapping + (button to action)))
    }

    fun resetMapping() = update { it.copy(mapping = DefaultMapping.map) }

    fun learnKey(keyCode: Int, button: PadButton) = update { s ->
        s.copy(learnedKeys = s.learnedKeys.filterValues { it != button } + (keyCode to button))
    }

    fun renamePreset(n: Int, name: String) = update { s ->
        val names = s.presetNames.toMutableList()
        if (n in 1..names.size) names[n - 1] = name.trim().ifEmpty { "Preset $n" }
        s.copy(presetNames = names)
    }

    fun setVideoEnabled(on: Boolean) = update { it.copy(videoEnabled = on) }

    // ---------------- Comandos da tela ----------------

    private val address: Int get() = _settings.value.selectedCamera?.viscaAddress ?: 1

    fun setVirtualStick(x: Double, y: Double) = controller.setVirtualStick(x, y)
    fun setScreenPanTilt(pan: Int, tilt: Int) = controller.setScreenPanTilt(pan, tilt)
    fun setScreenZoom(dir: Int) = controller.setScreenZoom(dir)

    fun recallPreset(n: Int) {
        controller.sendOnce(ViscaCommands.presetRecall(address, n))
        zoomFastUntil = SystemClock.elapsedRealtime() + 8000
        say("${presetName(n)}")
    }

    fun savePreset(n: Int) {
        controller.sendOnce(ViscaCommands.presetSet(address, n))
        say("Posição salva em ${presetName(n)}")
    }

    fun home() {
        controller.sendOnce(ViscaCommands.home(address))
        zoomFastUntil = SystemClock.elapsedRealtime() + 8000
        say("Home")
    }

    fun autofocus() {
        controller.sendOnce(ViscaCommands.autoFocus(address, true))
        say("Autofoco ligado")
    }

    fun brightness(dir: Int) {
        controller.sendOnce(ViscaCommands.expCompPower(address, true))
        controller.sendOnce(ViscaCommands.expCompStep(address, dir))
        say(if (dir > 0) "Brilho +" else "Brilho −")
    }

    fun stopCamera() = controller.stopNow(SystemClock.elapsedRealtime())

    fun changeSpeed(delta: Int) {
        val m = _settings.value.motion
        setMotion(m.copy(speedPercent = (m.speedPercent + delta).coerceIn(1, 100)))
        say("Velocidade ${_settings.value.motion.speedPercent}%")
    }

    private fun presetName(n: Int) = _settings.value.presetNames.getOrNull(n - 1) ?: "Preset $n"

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    private fun runAction(a: PadAction) {
        val p = a.presetNumber
        when {
            p != null -> recallPreset(p)
            a == PadAction.AUTOFOCUS -> autofocus()
            a == PadAction.SPEED_UP -> changeSpeed(+10)
            a == PadAction.SPEED_DOWN -> changeSpeed(-10)
            a == PadAction.BRIGHT_UP -> brightness(+1)
            a == PadAction.BRIGHT_DOWN -> brightness(-1)
            a == PadAction.HOME -> home()
            a == PadAction.STOP -> { stopCamera(); say("Câmera parada") }
            else -> {}
        }
    }

    // ---------------- Câmeras na rede ----------------

    suspend fun probe(cam: CameraConfig): Boolean = withContext(Dispatchers.IO) {
        CameraProbe.isAlive(cam.ip, cam.port, cam.transport, cam.viscaAddress, 1200)
    }

    suspend fun discover(): List<FoundCamera> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("ptz-descoberta")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            WsDiscovery.discover(3000)
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    // ---------------- Controle físico ----------------

    fun startDetection(listener: (DetectEvent) -> Unit) {
        detectListener = listener
        pushPad()
    }

    fun stopDetection() {
        detectListener = null
        pushPad()
    }

    private fun isGamepad(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val s = device.sources
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun refreshGamepad() {
        val name = InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { isGamepad(it) }
            ?.name
        _gamepad.value = name
    }

    private fun clearPad() {
        keysDown.clear(); hatDown.clear(); keyLt = 0.0; keyRt = 0.0; axisState = PadState()
        mapping.reset()
        pushPad()
    }

    /** Recebe teclas da Activity. Devolve true se era do controle (e foi tratada). */
    fun onKeyEvent(e: KeyEvent): Boolean {
        if (!isGamepad(e.device) && !KeyEvent.isGamepadButton(e.keyCode)) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return true
        if (down && e.repeatCount > 0) return true
        val learned = _settings.value.learnedKeys[e.keyCode]
        val button = learned ?: keyToButton(e.keyCode)
        if (button == null) {
            if (down) detectListener?.let { l -> l(DetectEvent.UnknownKey(e.keyCode)) }
            return true
        }
        when (button) {
            PadButton.LT -> keyLt = if (down) 1.0 else 0.0
            PadButton.RT -> keyRt = if (down) 1.0 else 0.0
            else -> if (down) keysDown += button else keysDown -= button
        }
        pushPad()
        return true
    }

    /** Recebe analógicos, gatilhos e direcional (eixos) da Activity. */
    fun onMotionEvent(e: MotionEvent): Boolean {
        if (!isGamepad(e.device)) return false
        if (e.action != MotionEvent.ACTION_MOVE) return true
        val dev = e.device
        fun axis(a: Int): Double = e.getAxisValue(a).toDouble()
        fun has(a: Int): Boolean = dev?.getMotionRange(a, e.source) != null
        val rx: Double
        val ry: Double
        if (has(MotionEvent.AXIS_RZ) || !has(MotionEvent.AXIS_RX)) {
            rx = axis(MotionEvent.AXIS_Z); ry = -axis(MotionEvent.AXIS_RZ)
        } else {
            rx = axis(MotionEvent.AXIS_RX); ry = -axis(MotionEvent.AXIS_RY)
        }
        val lt = max(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
        val rt = max(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
        axisState = PadState(
            lsX = axis(MotionEvent.AXIS_X), lsY = -axis(MotionEvent.AXIS_Y),
            rsX = rx, rsY = ry, lt = lt.coerceIn(0.0, 1.0), rt = rt.coerceIn(0.0, 1.0),
        )
        val hx = axis(MotionEvent.AXIS_HAT_X)
        val hy = axis(MotionEvent.AXIS_HAT_Y)
        hatDown.clear()
        if (hx < -0.5) hatDown += PadButton.DPAD_LEFT
        if (hx > 0.5) hatDown += PadButton.DPAD_RIGHT
        if (hy < -0.5) hatDown += PadButton.DPAD_UP
        if (hy > 0.5) hatDown += PadButton.DPAD_DOWN
        pushPad()
        return true
    }

    private var lastActive: Set<PadButton> = emptySet()

    private fun pushPad() {
        val state = axisState.copy(
            pressed = keysDown + hatDown,
            lt = max(axisState.lt, keyLt),
            rt = max(axisState.rt, keyRt),
        )
        _pad.value = state
        val active = buildSet {
            addAll(state.pressed)
            if (hypot(state.lsX, state.lsY) > DETECT_STICK) add(PadButton.LS)
            if (hypot(state.rsX, state.rsY) > DETECT_STICK) add(PadButton.RS)
            if (state.lt > DETECT_TRIGGER) add(PadButton.LT)
            if (state.rt > DETECT_TRIGGER) add(PadButton.RT)
        }
        _active.value = active
        val listener = detectListener
        if (listener != null) {
            (active - lastActive).firstOrNull()?.let { listener(DetectEvent.Button(it)) }
        }
        lastActive = active
        // Na detecção ou no mapeamento, o controle não mexe a câmera.
        if (listener != null || mappingMode) {
            mapping.update(PadState(), _settings.value.mapping)
        } else {
            mapping.update(state, _settings.value.mapping)
        }
    }

    private fun keyToButton(code: Int): PadButton? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> PadButton.A
        KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
        KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
        KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.LB
        KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.RB
        KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.LT
        KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.RT
        KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> PadButton.VIEW
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadButton.MENU
        KeyEvent.KEYCODE_BUTTON_MODE -> PadButton.HOME
        KeyEvent.KEYCODE_DPAD_UP -> PadButton.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.DPAD_RIGHT
        else -> null
    }

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val HEALTH_INTERVAL_MS = 2000L
        private const val REPLY_WINDOW_MS = 5000L
        private const val ZOOM_STALE_MS = 5000L
        const val DETECT_STICK = 0.8
        const val DETECT_TRIGGER = 0.6
    }
}
