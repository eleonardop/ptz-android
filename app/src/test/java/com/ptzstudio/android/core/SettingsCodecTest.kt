package com.ptzstudio.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun idaEVolta() {
        val cam = CameraConfig(name = "Igreja", ip = "192.168.4.163", port = 1259, transport = Transport.RAW_UDP, rtspUrl = "rtsp://192.168.4.163/2")
        val s = AppSettings(
            cameras = listOf(cam, CameraConfig(name = "Lateral", ip = "192.168.4.170", port = 52381, transport = Transport.VISCA_OVER_IP)),
            selectedCameraId = cam.id,
            mapping = DefaultMapping.map + (PadButton.HOME to PadAction.HOME),
            motion = MotionConfig(speedPercent = 40, rampPercent = 70, invertTilt = true),
            presetNames = listOf("Púlpito", "Coral", "Altar", "Plateia", "Preset 5", "Preset 6", "Preset 7", "Preset 8"),
            learnedKeys = mapOf(188 to PadButton.L4),
            videoEnabled = true,
        )
        val back = SettingsCodec.fromJson(SettingsCodec.toJson(s))
        assertEquals(s, back)
    }

    @Test
    fun textoVazioOuEstragado_voltaAoPadrao() {
        assertEquals(AppSettings(), SettingsCodec.fromJson(null))
        assertEquals(AppSettings(), SettingsCodec.fromJson("{isso não é json"))
    }

    @Test
    fun arquivoIncompleto_completaComPadroes() {
        val s = SettingsCodec.fromJson("""{"mapping":{"A":"HOME","LS":"PRESET_1","XX":"NONE"},"motion":{"speedPercent":500}}""")
        assertEquals(PadAction.HOME, s.mapping[PadButton.A])
        assertEquals(PadAction.STICK_MOVE, s.mapping[PadButton.LS])
        assertEquals(23, s.mapping.size)
        assertEquals(100, s.motion.speedPercent)
        assertTrue(s.cameras.isEmpty())
    }

    @Test
    fun video_ligadoPorPadrao_eAoAtualizarDaVersaoAntiga() {
        assertTrue(AppSettings().videoEnabled)
        assertTrue(SettingsCodec.fromJson("""{"version":1,"videoEnabled":false}""").videoEnabled)
        val desligado = AppSettings(videoEnabled = false)
        assertEquals(false, SettingsCodec.fromJson(SettingsCodec.toJson(desligado)).videoEnabled)
    }
}
