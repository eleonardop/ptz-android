package com.ptzstudio.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTest {
    private val sent = mutableListOf<ByteArray>()
    private val c = PtzController { sent += it }
    private var now = 1_000L

    private fun tick(times: Int = 1, stepMs: Long = 20) {
        repeat(times) {
            now += stepMs
            c.tick(stepMs / 1000.0, now)
        }
    }

    private fun ByteArray.isPanTilt() = size == 9 && this[1].toInt() == 0x01 && this[2].toInt() == 0x06 && this[3].toInt() == 0x01
    private fun ByteArray.isZoom() = size == 6 && this[2].toInt() == 0x04 && this[3].toInt() == 0x07
    private fun ByteArray.panSpeed() = if (this[6].toInt() == 0x03) 0 else (this[4].toInt() and 0xFF) * (if (this[6].toInt() == 0x01) -1 else 1)
    private fun ByteArray.tiltSpeed() = if (this[7].toInt() == 0x03) 0 else (this[5].toInt() and 0xFF) * (if (this[7].toInt() == 0x02) -1 else 1)
    private fun ByteArray.isStop() = isPanTilt() && this[6].toInt() == 0x03 && this[7].toInt() == 0x03
    private fun ByteArray.zoomSpeed(): Int {
        val code = this[4].toInt() and 0xFF
        return when (code and 0xF0) {
            0x20 -> (code and 0x0F) + 1
            0x30 -> -((code and 0x0F) + 1)
            else -> 0
        }
    }

    private fun lastPanTilt() = sent.last { it.isPanTilt() }

    @Test
    fun parado_naoEnviaNada() {
        tick(50)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun analogico_movimentoGradual_velocidadeSobeAosPoucos() {
        c.config = MotionConfig(speedPercent = 100, rampPercent = 50) // ~1 s até o máximo
        c.setPhysicalStick(1.0, 0.0)
        tick(3)
        val inicio = lastPanTilt().panSpeed()
        tick(60)
        val depois = lastPanTilt().panSpeed()
        assertTrue("começa devagar ($inicio)", inicio in 1..4)
        assertEquals(24, depois)
        assertTrue(sent.filter { it.isPanTilt() }.map { it.panSpeed() }.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun analogico_proporcionalAInclinacao() {
        c.config = MotionConfig(speedPercent = 100, rampPercent = 0)
        c.setPhysicalStick(0.5, 0.0)
        tick(5)
        val meio = lastPanTilt().panSpeed()
        c.setPhysicalStick(1.0, 0.0)
        tick(5)
        val todo = lastPanTilt().panSpeed()
        assertTrue("meia inclinação ($meio) < inclinação total ($todo)", meio in 2 until todo)
        assertEquals(24, todo)
    }

    @Test
    fun velocidadePercentual_limitaOMaximo() {
        c.config = MotionConfig(speedPercent = 50, rampPercent = 0)
        c.setPhysicalStick(1.0, 1.0)
        tick(5)
        assertEquals(12, lastPanTilt().panSpeed())
        assertEquals(10, lastPanTilt().tiltSpeed())
    }

    @Test
    fun soltar_paraNaHora_eRepeteAParada() {
        c.config = MotionConfig(rampPercent = 0)
        c.setPhysicalStick(0.0, 1.0)
        tick(5)
        assertTrue(lastPanTilt().tiltSpeed() > 0)
        c.setPhysicalStick(0.0, 0.0)
        val antes = sent.size
        tick(1)
        assertTrue("parada no mesmo ciclo", sent.last().isStop())
        tick(400) // 8 s
        val paradas = sent.drop(antes).count { it.isStop() }
        assertTrue("parada repetida ($paradas)", paradas >= 6)
        assertTrue(sent.drop(antes).all { it.isStop() })
    }

    @Test
    fun segurando_reenviaACada400ms() {
        c.config = MotionConfig(rampPercent = 0)
        c.setPhysicalStick(1.0, 0.0)
        tick(5)
        val antes = sent.count { it.isPanTilt() }
        tick(50) // 1 s parado na mesma inclinação
        val depois = sent.count { it.isPanTilt() }
        assertTrue(depois - antes in 2..3)
    }

    @Test
    fun horizontalComLeveDesvio_semDiagonal() {
        c.config = MotionConfig(rampPercent = 0)
        c.setPhysicalStick(0.95, 0.15)
        tick(5)
        assertEquals(0, lastPanTilt().tiltSpeed())
        assertTrue(lastPanTilt().panSpeed() > 0)
    }

    @Test
    fun cimaNoAnalogico_tiltParaCima_semInversao() {
        c.config = MotionConfig(rampPercent = 0)
        c.setPhysicalStick(0.0, 1.0)
        tick(3)
        assertTrue(lastPanTilt().tiltSpeed() > 0)
        c.config = MotionConfig(rampPercent = 0, invertTilt = true)
        c.setPhysicalStick(0.0, 0.0); tick(2)
        c.setPhysicalStick(0.0, 1.0); tick(3)
        assertTrue(lastPanTilt().tiltSpeed() < 0)
    }

    @Test
    fun zonaMorta_ignoraFolgaDoAnalogico() {
        c.setPhysicalStick(0.08, -0.05)
        tick(20)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun movimentoFino_maisLento() {
        c.config = MotionConfig(speedPercent = 100, rampPercent = 0, finePercent = 35)
        c.setPhysicalStick(1.0, 0.0, fine = true)
        tick(5)
        val fino = lastPanTilt().panSpeed()
        assertTrue("fino = $fino", fino in 1..6)
    }

    @Test
    fun gatilho_zoomProporcionalAPressao_eParaAoSoltar() {
        c.setAnalogZoom(0.2)
        tick(3)
        val leve = sent.last { it.isZoom() }.zoomSpeed()
        c.setAnalogZoom(1.0)
        tick(3)
        val forte = sent.last { it.isZoom() }.zoomSpeed()
        assertTrue("leve=$leve forte=$forte", leve in 1..3)
        assertEquals(8, forte)
        c.setAnalogZoom(0.0)
        tick(1)
        assertEquals(0, sent.last().zoomSpeed())
        assertTrue(sent.last().isZoom())
        c.setAnalogZoom(-0.6)
        tick(3)
        assertTrue(sent.last { it.isZoom() }.zoomSpeed() < 0)
    }

    @Test
    fun direcional_movePanETilt() {
        c.config = MotionConfig(rampPercent = 0, speedPercent = 100)
        c.setPadPanTilt(-1, 0)
        tick(3)
        assertTrue(lastPanTilt().panSpeed() < 0)
        assertEquals(0, lastPanTilt().tiltSpeed())
        c.setPadPanTilt(0, 0)
        tick(1)
        assertTrue(sent.last().isStop())
    }

    @Test
    fun joystickDaTela_e_controleJuntos_quemComecouPrimeiroComanda() {
        c.config = MotionConfig(rampPercent = 0)
        c.setVirtualStick(-1.0, 0.0)
        tick(3)
        c.setPhysicalStick(1.0, 0.0)
        tick(3)
        assertTrue("o da tela continua no comando", lastPanTilt().panSpeed() < 0)
        c.setVirtualStick(0.0, 0.0)
        tick(15) // a inversão de sentido passa pelo zero antes (sem tranco)
        assertTrue("agora o físico", lastPanTilt().panSpeed() > 0)
    }

    @Test
    fun pararCamera_mandaParadaDeTudo() {
        c.setPhysicalStick(1.0, 0.0)
        c.setAnalogZoom(1.0)
        tick(5)
        c.stopNow(now)
        assertTrue(sent.any { it.isStop() })
        assertTrue(sent.last { it.isZoom() }.zoomSpeed() == 0)
        tick(5)
        assertEquals(0, c.lastSentPan)
        assertEquals(0, c.lastSentZoom)
    }

    @Test
    fun foco_desligaAutomaticoEParaAoSoltar() {
        c.setPadFocus(1)
        tick(1)
        val hex = sent.map { it.joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) } }
        assertTrue(hex.contains("81 01 04 38 03 FF"))
        assertTrue(hex.contains("81 01 04 08 02 FF"))
        c.setPadFocus(0)
        tick(1)
        assertEquals("81 01 04 08 00 FF", sent.last().joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) })
    }

    @Test
    fun releaseAll_zeraTudo() {
        c.setPhysicalStick(1.0, 1.0)
        c.setAnalogZoom(1.0)
        tick(3)
        c.releaseAll()
        tick(1)
        assertFalse(c.lastSentPan != 0 || c.lastSentTilt != 0 || c.lastSentZoom != 0)
    }
}
