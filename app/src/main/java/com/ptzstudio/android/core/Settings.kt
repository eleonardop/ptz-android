package com.ptzstudio.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Uma câmera salva. */
data class CameraConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Câmera",
    val ip: String = "",
    val port: Int = Transport.RAW_UDP.defaultPort,
    val transport: Transport = Transport.RAW_UDP,
    val viscaAddress: Int = 1,
    val rtspUrl: String = "",
) {
    val limits: CameraLimits get() = CameraLimits(address = viscaAddress.coerceIn(1, 7))
}

/** Tudo o que o app guarda no aparelho. */
data class AppSettings(
    val cameras: List<CameraConfig> = emptyList(),
    val selectedCameraId: String? = null,
    val mapping: Map<PadButton, PadAction> = DefaultMapping.map,
    val motion: MotionConfig = MotionConfig(),
    val presetNames: List<String> = defaultPresetNames(),
    /** Códigos de tecla que o usuário ensinou na detecção (ex.: L4/R4 que o Android não conhece). */
    val learnedKeys: Map<Int, PadButton> = emptyMap(),
    /** Vídeo ao vivo ligado por padrão. */
    val videoEnabled: Boolean = true,
    /** Brilho pelo modo "Brilho" da câmera (para câmeras que ignoram a compensação de exposição). */
    val brightMode: Boolean = false,
    /** Manda um sinal de vibração zero ao controle a cada 3 minutos, para ele não desligar sozinho. */
    val keepPadAwake: Boolean = true,
) {
    val selectedCamera: CameraConfig?
        get() = cameras.firstOrNull { it.id == selectedCameraId } ?: cameras.firstOrNull()

    companion object {
        const val PRESET_COUNT = 8
        fun defaultPresetNames(): List<String> = (1..PRESET_COUNT).map { "Preset $it" }
    }
}

/** Conversão das configurações para JSON (e de volta), tolerante a arquivos antigos ou incompletos. */
object SettingsCodec {
    const val VERSION = 2

    fun toJson(s: AppSettings): String {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("cameras", JSONArray().apply {
            s.cameras.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("ip", c.ip)
                    put("port", c.port)
                    put("transport", c.transport.name)
                    put("viscaAddress", c.viscaAddress)
                    put("rtspUrl", c.rtspUrl)
                })
            }
        })
        s.selectedCameraId?.let { o.put("selectedCameraId", it) }
        o.put("mapping", JSONObject().apply { s.mapping.forEach { (b, a) -> put(b.name, a.name) } })
        val m = s.motion
        o.put("motion", JSONObject().apply {
            put("speedPercent", m.speedPercent)
            put("rampPercent", m.rampPercent)
            put("deadzonePercent", m.deadzonePercent)
            put("finePercent", m.finePercent)
            put("zoomSpeedPercent", m.zoomSpeedPercent)
            put("zoomSlowdownEnabled", m.zoomSlowdownEnabled)
            put("zoomSlowdownPercent", m.zoomSlowdownPercent)
            put("zoomMaxPosition", m.zoomMaxPosition)
            put("inputWatchdogEnabled", m.inputWatchdogEnabled)
            put("invertPan", m.invertPan)
            put("invertTilt", m.invertTilt)
            put("diagonalLockDeg", m.diagonalLockDeg)
        })
        o.put("presetNames", JSONArray().apply { s.presetNames.forEach { put(it) } })
        o.put("learnedKeys", JSONObject().apply { s.learnedKeys.forEach { (k, b) -> put(k.toString(), b.name) } })
        o.put("videoEnabled", s.videoEnabled)
        o.put("brightMode", s.brightMode)
        o.put("keepPadAwake", s.keepPadAwake)
        return o.toString()
    }

    fun fromJson(text: String?): AppSettings = tryParse(text) ?: AppSettings()

    /** Igual a [fromJson], mas devolve nulo se o texto existe e está estragado (para guardar uma cópia antes de sobrescrever). */
    fun tryParse(text: String?): AppSettings? {
        if (text.isNullOrBlank()) return AppSettings()
        return try {
            val o = JSONObject(text)
            val def = AppSettings()
            val cameras = mutableListOf<CameraConfig>()
            o.optJSONArray("cameras")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val transport = Transport.fromName(c.optString("transport"))
                    cameras += CameraConfig(
                        id = c.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = c.optString("name", "Câmera"),
                        ip = c.optString("ip", ""),
                        port = c.optInt("port", transport.defaultPort).coerceIn(1, 65535),
                        transport = transport,
                        viscaAddress = c.optInt("viscaAddress", 1).coerceIn(1, 7),
                        rtspUrl = c.optString("rtspUrl", ""),
                    )
                }
            }
            val mapping = mutableMapOf<PadButton, PadAction>()
            o.optJSONObject("mapping")?.let { mo ->
                mo.keys().forEach { k ->
                    val b = PadButton.fromName(k)
                    val a = PadAction.fromName(mo.optString(k))
                    if (b != null && a != null) mapping[b] = a
                }
            }
            val mj = o.optJSONObject("motion")
            val dm = def.motion
            val motion = if (mj == null) dm else MotionConfig(
                speedPercent = mj.optInt("speedPercent", dm.speedPercent),
                rampPercent = mj.optInt("rampPercent", dm.rampPercent),
                deadzonePercent = mj.optInt("deadzonePercent", dm.deadzonePercent),
                finePercent = mj.optInt("finePercent", dm.finePercent),
                zoomSpeedPercent = mj.optInt("zoomSpeedPercent", dm.zoomSpeedPercent),
                zoomSlowdownEnabled = mj.optBoolean("zoomSlowdownEnabled", dm.zoomSlowdownEnabled),
                zoomSlowdownPercent = mj.optInt("zoomSlowdownPercent", dm.zoomSlowdownPercent),
                zoomMaxPosition = mj.optInt("zoomMaxPosition", dm.zoomMaxPosition),
                inputWatchdogEnabled = mj.optBoolean("inputWatchdogEnabled", dm.inputWatchdogEnabled),
                invertPan = mj.optBoolean("invertPan", dm.invertPan),
                invertTilt = mj.optBoolean("invertTilt", dm.invertTilt),
                diagonalLockDeg = mj.optInt("diagonalLockDeg", dm.diagonalLockDeg),
            ).sanitized()
            val names = def.presetNames.toMutableList()
            o.optJSONArray("presetNames")?.let { arr ->
                for (i in 0 until minOf(arr.length(), names.size)) {
                    val n = arr.optString(i).trim()
                    if (n.isNotEmpty()) names[i] = n
                }
            }
            val learned = mutableMapOf<Int, PadButton>()
            o.optJSONObject("learnedKeys")?.let { lk ->
                lk.keys().forEach { k ->
                    val code = k.toIntOrNull()
                    val b = PadButton.fromName(lk.optString(k))
                    if (code != null && b != null) learned[code] = b
                }
            }
            val selected = o.optString("selectedCameraId").takeIf { id -> cameras.any { it.id == id } }
            AppSettings(
                cameras = cameras,
                selectedCameraId = selected ?: cameras.firstOrNull()?.id,
                mapping = DefaultMapping.sanitize(mapping),
                motion = motion,
                presetNames = names,
                learnedKeys = learned,
                // Até a versão 1 o vídeo vinha desligado por padrão: quem atualiza passa a ter o vídeo ligado.
                videoEnabled = if (o.optInt("version", 1) < 2) true else o.optBoolean("videoEnabled", true),
                brightMode = o.optBoolean("brightMode", false),
                keepPadAwake = o.optBoolean("keepPadAwake", true),
            )
        } catch (_: Exception) {
            null
        }
    }
}
