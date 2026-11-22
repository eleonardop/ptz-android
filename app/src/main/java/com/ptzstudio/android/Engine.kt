package com.ptzstudio.android

import android.content.Context
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
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
import com.ptzstudio.android.core.RtspFinder
import com.ptzstudio.android.core.MappingEngine
import com.ptzstudio.android.core.MotionConfig
import com.ptzstudio.android.core.PadAction
import com.ptzstudio.android.core.PadButton
import com.ptzstudio.android.core.PadState
import com.ptzstudio.android.core.PtzController
import com.ptzstudio.android.core.SettingsCodec
import com.ptzstudio.android.core.Transport
import com.ptzstudio.android.core.ViscaCommands
import com.ptzstudio.android.core.ViscaReplies
import com.ptzstudio.android.core.ViscaUdpClient
import com.ptzstudio.android.core.WsDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.net.DatagramSocket
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

    private val _settings = MutableStateFlow(loadSettings())
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
    @Volatile private var lastZoomPollMs = 0L
    @Volatile private var zoomFastUntil = 0L
    private var prevSentZoom = 0

    /** Rede Wi-Fi atual. Os sockets são presos a ela para os comandos não saírem pelos dados móveis. */
    @Volatile private var wifiNetwork: Network? = null

    private val client = ViscaUdpClient(prepareSocket = ::bindToWifi) { data ->
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
    /** Cópia dos analógicos/gatilhos para o ciclo (outra thread) checar se o controle parou de mandar sinal. */
    @Volatile private var analogSnapshot = PadState()
    @Volatile private var lastGamepadEventMs = 0L
    /** Controle que está comandando (com dois controles ligados, um não atrapalha o outro). */
    private var activeDeviceId: Int? = null

    /** Na tela de mapeamento o controle não mexe a câmera; os botões só são mostrados no desenho. */
    @Volatile var mappingMode = false
        set(value) {
            field = value
            pushPad()
        }

    @Volatile private var detectListener: ((DetectEvent) -> Unit)? = null

    init {
        main.postDelayed(padKeepAlive, PAD_KEEPALIVE_MS)
        applyCamera(reconnect = true)
        controller.config = _settings.value.motion
        loop.scheduleAtFixedRate({ safeTick() }, 0, 20, TimeUnit.MILLISECONDS)
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        im.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshGamepad()
            override fun onInputDeviceRemoved(deviceId: Int) {
                refreshGamepad()
                if (activeDeviceId == null || activeDeviceId == deviceId) {
                    // O controle que estava comandando sumiu: para tudo até as entradas voltarem ao neutro.
                    clearPad()
                    controller.safetyStop(SystemClock.elapsedRealtime())
                }
            }
            override fun onInputDeviceChanged(deviceId: Int) = refreshGamepad()
        }, main)
        refreshGamepad()
        watchWifi()
    }

    // ---------------- Rede Wi-Fi ----------------

    private fun loadSettings(): AppSettings {
        val text = prefs.getString(KEY_SETTINGS, null)
        val parsed = SettingsCodec.tryParse(text)
        if (parsed == null) {
            // Arquivo estragado: guarda uma cópia antes que a próxima mudança grave por cima.
            prefs.edit().putString(KEY_SETTINGS_BACKUP, text).apply()
            return AppSettings()
        }
        return parsed
    }

    private fun bindToWifi(s: DatagramSocket) {
        wifiNetwork?.bindSocket(s)
    }

    /**
     * Acompanha a rede Wi-Fi (mesmo sem internet). Quando ela aparece ou muda, a câmera é reconectada
     * por ela; isso também manda a parada de novo quando o Wi-Fi volta.
     */
    private fun watchWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        if (wifiNetwork != network) {
                            wifiNetwork = network
                            applyCamera(reconnect = true)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    main.post { if (wifiNetwork == network) wifiNetwork = null }
                }
            }, main)
        } catch (_: Exception) {
            // Sem acesso ao estado da rede: segue sem prender ao Wi-Fi.
        }
    }

    // ---------------- Ciclo ----------------

    private fun safeTick() {
        try {
            val nowNs = System.nanoTime()
            val dt = ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.1)
            lastTickNs = nowNs
            val nowMs = SystemClock.elapsedRealtime()
            controller.tick(dt, nowMs)
            checkInputWatchdog(nowMs)
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
        val old = _link.value
        val now = when {
            nowMs - lastReplyAt < REPLY_WINDOW_MS -> LinkState.ONLINE
            nowMs - connectedAt < REPLY_WINDOW_MS -> LinkState.CONNECTING
            else -> LinkState.NO_REPLY
        }
        if (now == LinkState.NO_REPLY) {
            if (old != LinkState.NO_REPLY && controller.isMoving) {
                // Como no Windows: câmera sem resposta durante um movimento = parada de segurança.
                controller.safetyStop(nowMs)
                say("Câmera sem resposta: movimento parado")
            }
            // VISCA over IP: se a câmera reiniciou, ela espera a sequência zerada.
            if (cam.transport == Transport.VISCA_OVER_IP) client.resetSequence()
        }
        if (now == LinkState.ONLINE && old == LinkState.NO_REPLY) {
            // A câmera voltou: a parada pode ter se perdido durante a queda. Reenvia se nada estiver em uso.
            controller.resendStopIfIdle(nowMs)
        }
        _link.value = now
    }

    /**
     * Segurança para controle Bluetooth que trava sem se desconectar: se um analógico ou gatilho está fora do
     * centro e o controle não manda nenhum sinal por [INPUT_WATCHDOG_MS], a câmera para.
     */
    private fun checkInputWatchdog(nowMs: Long) {
        if (!_settings.value.motion.inputWatchdogEnabled) return
        val a = analogSnapshot
        val dz = _settings.value.motion.deadzonePercent / 100.0
        val held = hypot(a.lsX, a.lsY) > dz || hypot(a.rsX, a.rsY) > dz || a.lt > 0.05 || a.rt > 0.05
        if (held && lastGamepadEventMs != 0L && nowMs - lastGamepadEventMs > INPUT_WATCHDOG_MS) {
            lastGamepadEventMs = nowMs
            analogSnapshot = PadState()
            controller.safetyStop(nowMs)
            main.post { clearPad() }
            say("Controle sem sinal: câmera parada")
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
        // Se a câmera parar de informar, vale a última posição conhecida (não volta à velocidade cheia de repente).
    }

    private fun onZoomPosition(pos: Int) {
        rawZoom = pos
        // O máximo só muda pelo botão Calibrar (um valor estranho da câmera não estraga a redução).
        val max = _settings.value.motion.zoomMaxPosition.coerceAtLeast(1)
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
        if (reconnect) {
            // A parada sai para a câmera antiga, com o endereço dela, antes de trocar.
            controller.stopNow(SystemClock.elapsedRealtime())
            controller.limits = cam.limits
            client.connect(cam.ip, cam.port, cam.transport)
            connectedAt = SystemClock.elapsedRealtime()
            lastReplyAt = 0L
            lastHealthMs = 0L
            lastZoomPollMs = 0L
            rawZoom = null
            controller.zoomLevel = null
            _zoomLevel.value = null
            _link.value = LinkState.CONNECTING
        } else {
            controller.limits = cam.limits
        }
    }

    // ---------------- Ciclo de vida ----------------

    fun onForeground() {
        foreground = true
        lastHealthMs = 0L
        refreshGamepad()
    }

    /** App saiu da tela: o Android não entrega mais o controle, então tudo para (e só volta a mover depois de soltar). */
    fun onBackground() {
        foreground = false
        clearPad()
        controller.safetyStop(SystemClock.elapsedRealtime())
    }

    /**
     * A janela do app perdeu o foco (cortina de notificações, aviso do sistema, tela dividida, janelas do app):
     * o "soltou" do controle iria para a outra janela, então para agora.
     */
    fun onFocusLost() {
        clearPad()
        controller.safetyStop(SystemClock.elapsedRealtime())
    }

    /** Usado quando o app vai fechar por erro: manda a parada e espera ela sair. */
    fun emergencyStop() {
        controller.stopNow(SystemClock.elapsedRealtime())
        client.flush(300)
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

    /** Maior velocidade de Pan/Tilt da câmera em uso (VISCA: 24). */
    val maxPanSpeed: Int get() = (_settings.value.selectedCamera?.limits ?: com.ptzstudio.android.core.CameraLimits()).maxPan

    /** Maior velocidade de zoom da câmera em uso (VISCA: 8). */
    val maxZoomSpeed: Int get() = (_settings.value.selectedCamera?.limits ?: com.ptzstudio.android.core.CameraLimits()).maxZoom

    fun setVirtualStick(x: Double, y: Double) = controller.setVirtualStick(x, y)
    fun setScreenPanTilt(pan: Int, tilt: Int) = controller.setScreenPanTilt(pan, tilt)
    fun setScreenZoom(dir: Int) = controller.setScreenZoom(dir)

    fun recallPreset(n: Int) {
        controller.sendOnce(ViscaCommands.presetRecall(address, n))
        controller.latchUntilNeutral()
        zoomFastUntil = SystemClock.elapsedRealtime() + 8000
        say("${presetName(n)}")
    }

    fun savePreset(n: Int) {
        controller.sendOnce(ViscaCommands.presetSet(address, n))
        say("Posição salva em ${presetName(n)}")
    }

    fun home() {
        controller.sendOnce(ViscaCommands.home(address))
        controller.latchUntilNeutral()
        zoomFastUntil = SystemClock.elapsedRealtime() + 8000
        say("Home")
    }

    private val _autofocusOn = MutableStateFlow<Boolean?>(null)
    /** Autofoco ligado (true), desligado/manual (false) ou ainda não usado (nulo). */
    val autofocusOn: StateFlow<Boolean?> = _autofocusOn.asStateFlow()

    /** Liga e desliga o autofoco a cada toque (desligado = o foco fica parado onde está). */
    fun autofocus() {
        val on = _autofocusOn.value != true
        controller.sendOnce(ViscaCommands.autoFocus(address, on))
        _autofocusOn.value = on
        say(if (on) "Autofoco ligado" else "Autofoco desligado (foco travado)")
    }

    /** Nível da compensação de exposição mandado por último (0..14; 7 = normal). */
    @Volatile private var expLevel = ViscaCommands.EXP_COMP_CENTER

    /**
     * Brilho. Padrão: compensação de exposição com o valor exato (algumas câmeras ignoram o "sobe/desce").
     * Opção em Ajustes: modo "Brilho" da câmera, para as que não aceitam compensação.
     */
    fun brightness(dir: Int) {
        if (_settings.value.brightMode) {
            controller.sendOnce(ViscaCommands.aeMode(address, bright = true))
            controller.sendOnce(ViscaCommands.brightStep(address, dir))
            say(if (dir > 0) "Brilho +" else "Brilho −")
            return
        }
        expLevel = (expLevel + if (dir > 0) 1 else -1).coerceIn(0, ViscaCommands.EXP_COMP_MAX)
        controller.sendOnce(ViscaCommands.expCompPower(address, true))
        controller.sendOnce(ViscaCommands.expCompDirect(address, expLevel))
        val v = expLevel - ViscaCommands.EXP_COMP_CENTER
        say("Brilho ${if (v > 0) "+$v" else if (v < 0) "−${-v}" else "0 (normal)"}")
    }

    /** Troca o jeito do brilho. Ao voltar para o padrão, devolve a câmera à exposição automática. */
    fun setBrightMode(on: Boolean) {
        update { it.copy(brightMode = on) }
        if (!on) controller.sendOnce(ViscaCommands.aeMode(address, bright = false))
        expLevel = ViscaCommands.EXP_COMP_CENTER
    }

    fun stopCamera() = controller.stopNow(SystemClock.elapsedRealtime())

    /** Sobe ou desce a velocidade do Pan/Tilt um passo (no valor real da câmera). */
    fun changeSpeed(delta: Int) {
        val m = _settings.value.motion
        val max = maxPanSpeed
        val level = (m.speedLevel(max) + delta).coerceIn(1, max)
        setMotion(m.withSpeedLevel(level, max))
        say("Velocidade $level de $max")
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
            a == PadAction.SPEED_UP -> changeSpeed(+1)
            a == PadAction.SPEED_DOWN -> changeSpeed(-1)
            a == PadAction.BRIGHT_UP -> brightness(+1)
            a == PadAction.BRIGHT_DOWN -> brightness(-1)
            a == PadAction.HOME -> home()
            a == PadAction.STOP -> { stopCamera(); say("Câmera parada") }
            else -> {}
        }
    }

    // ---------------- Câmeras na rede ----------------

    suspend fun probe(cam: CameraConfig): Boolean = withContext(Dispatchers.IO) {
        CameraProbe.isAlive(cam.ip, cam.port, cam.transport, cam.viscaAddress, 1200, ::bindToWifi)
    }

    suspend fun discover(): List<FoundCamera> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("ptz-descoberta")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            val list = WsDiscovery.discover(3000, ::bindToWifi)
            // Para cada câmera achada, já descobre o endereço do vídeo (em paralelo).
            list.map { f -> async { f.copy(rtspUrl = findRtspBlocking(f.ip, f.xaddr).orEmpty()) } }.awaitAll()
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    private fun findRtspBlocking(ip: String, xaddr: String?): String? {
        val net = wifiNetwork
        val open = { u: java.net.URL -> net?.openConnection(u) ?: u.openConnection() }
        val sock = { net?.socketFactory?.createSocket() ?: java.net.Socket() }
        val candidates = listOfNotNull(xaddr, "http://$ip/onvif/device_service", "http://$ip:8899/onvif/device_service").distinct()
        for (x in candidates) RtspFinder.viaOnvif(x, ip, open, 1500)?.let { return it }
        return RtspFinder.viaProbe(ip, sock)
    }

    /** Procura o endereço RTSP do vídeo de uma câmera pelo IP (botão no cadastro). */
    suspend fun findRtsp(ip: String): String? = withContext(Dispatchers.IO) {
        if (ip.isBlank()) null else try { findRtspBlocking(ip.trim(), null) } catch (_: Exception) { null }
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

    private val _padPulse = MutableStateFlow<String?>(null)
    /** Resultado do último sinal para manter o controle ligado (para mostrar em Ajustes). */
    val padPulse: StateFlow<String?> = _padPulse.asStateFlow()

    /** A cada 3 minutos manda um sinal de vibração zero ao controle, para ele não desligar sozinho por inatividade. */
    private val padKeepAlive: Runnable by lazy {
        object : Runnable {
            override fun run() {
                if (_settings.value.keepPadAwake) pulsePads()
                main.postDelayed(this, PAD_KEEPALIVE_MS)
            }
        }
    }

    /** Vibração de intensidade zero (não se sente), só para o controle receber um sinal. Devolve quantos controles aceitaram. */
    fun pulsePads(): Int {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 40), intArrayOf(0, 0), -1)
        var sent = 0
        var pads = 0
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (!isGamepad(d)) continue
            pads++
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val vm = d.vibratorManager
                    val ids = vm.vibratorIds
                    for (v in ids) vm.getVibrator(v).vibrate(effect)
                    if (ids.isNotEmpty()) sent++
                } else {
                    @Suppress("DEPRECATION")
                    val v = d.vibrator
                    if (v.hasVibrator()) {
                        v.vibrate(effect)
                        sent++
                    }
                }
            } catch (_: Exception) {
            }
        }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        _padPulse.value = when {
            pads == 0 -> "Nenhum controle conectado ($time)"
            sent == 0 -> "O Android não oferece vibração para este controle: o sinal não pôde ser enviado ($time)"
            else -> "Sinal enviado às $time"
        }
        return sent
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
        analogSnapshot = PadState()
        activeDeviceId = null
        mapping.reset()
        pushPad()
    }

    /** O controle informa a pressão dos gatilhos como eixo (aí o "botão" L2/R2 é ignorado). */
    private fun hasAnalogTriggers(dev: InputDevice?): Boolean {
        if (dev == null) return false
        return listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            .any { dev.getMotionRange(it) != null }
    }

    private fun padIsNeutral(): Boolean =
        keysDown.isEmpty() && hatDown.isEmpty() && keyLt == 0.0 && keyRt == 0.0 &&
            hypot(axisState.lsX, axisState.lsY) < 0.2 && hypot(axisState.rsX, axisState.rsY) < 0.2 &&
            axisState.lt < 0.1 && axisState.rt < 0.1

    /** Com dois controles ligados, só um comanda por vez (o outro é ignorado enquanto o primeiro está em uso). */
    private fun acceptDevice(id: Int): Boolean {
        val cur = activeDeviceId
        if (cur != null && cur != id && !padIsNeutral()) return false
        activeDeviceId = id
        return true
    }

    /** Recebe teclas da Activity. Devolve true se era do controle (e foi tratada). */
    fun onKeyEvent(e: KeyEvent): Boolean {
        if (!isGamepad(e.device) && !KeyEvent.isGamepadButton(e.keyCode)) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return true
        if (down && e.repeatCount > 0) return true
        if (!acceptDevice(e.deviceId)) return true
        lastGamepadEventMs = SystemClock.elapsedRealtime()
        val learned = _settings.value.learnedKeys[e.keyCode]
        val button = learned ?: keyToButton(e.keyCode)
        if (button == null) {
            if (down) detectListener?.let { l -> l(DetectEvent.UnknownKey(e.keyCode)) }
            return true
        }
        val analogTriggers = hasAnalogTriggers(e.device)
        when (button) {
            // Se o controle manda a pressão do gatilho como eixo, o "botão" L2/R2 não pode passar por cima dela
            // (senão um toque leve viraria zoom no máximo).
            PadButton.LT -> if (!analogTriggers) keyLt = if (down) 1.0 else 0.0
            PadButton.RT -> if (!analogTriggers) keyRt = if (down) 1.0 else 0.0
            else -> if (down) keysDown += button else keysDown -= button
        }
        pushPad()
        return true
    }

    /** Recebe analógicos, gatilhos e direcional (eixos) da Activity. */
    fun onMotionEvent(e: MotionEvent): Boolean {
        if (!isGamepad(e.device)) return false
        if (e.action != MotionEvent.ACTION_MOVE) return true
        if (!acceptDevice(e.deviceId)) return true
        lastGamepadEventMs = SystemClock.elapsedRealtime()
        val dev = e.device
        fun axis(a: Int): Double = e.getAxisValue(a).toDouble()
        fun has(a: Int): Boolean = dev?.getMotionRange(a, e.source) != null
        // Gatilho num eixo que vai de -1 a 1 (repouso em -1) vira 0..1.
        fun trigger(a: Int): Double {
            val r = dev?.getMotionRange(a, e.source) ?: return 0.0
            val v = axis(a)
            return if (r.min < -0.5f) (v - r.min) / (r.max - r.min) else v
        }
        val dedicatedTriggers = has(MotionEvent.AXIS_LTRIGGER) || has(MotionEvent.AXIS_BRAKE) ||
            has(MotionEvent.AXIS_RTRIGGER) || has(MotionEvent.AXIS_GAS)
        val rx: Double
        val ry: Double
        val lt: Double
        val rt: Double
        if (dedicatedTriggers || !has(MotionEvent.AXIS_RX)) {
            // Padrão Xbox/Android (GameSir): analógico direito em Z/RZ, gatilhos em eixos próprios.
            rx = axis(MotionEvent.AXIS_Z); ry = -axis(MotionEvent.AXIS_RZ)
            lt = max(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
            rt = max(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
        } else {
            // Sem eixos próprios de gatilho: analógico direito em RX/RY e gatilhos em Z/RZ.
            rx = axis(MotionEvent.AXIS_RX); ry = -axis(MotionEvent.AXIS_RY)
            lt = if (has(MotionEvent.AXIS_Z)) trigger(MotionEvent.AXIS_Z) else 0.0
            rt = if (has(MotionEvent.AXIS_RZ)) trigger(MotionEvent.AXIS_RZ) else 0.0
        }
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
        analogSnapshot = axisState
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
        private const val PAD_KEEPALIVE_MS = 3 * 60_000L
        private const val KEY_SETTINGS_BACKUP = "settings_corrompido"
        private const val HEALTH_INTERVAL_MS = 2000L
        private const val REPLY_WINDOW_MS = 5000L
        /** Analógico/gatilho fora do centro sem nenhum sinal do controle por este tempo = parada de segurança. */
        const val INPUT_WATCHDOG_MS = 5000L
        const val DETECT_STICK = 0.8
        const val DETECT_TRIGGER = 0.6
    }
}
