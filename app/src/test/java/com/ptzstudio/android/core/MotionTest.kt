package com.ptzstudio.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun zonaMorta_semSalto() {
        assertEquals(0.0, AxisMath.deadzone(0.1, 0.12), 1e-9)
        assertEquals(0.0, AxisMath.deadzone(-0.12, 0.12), 1e-9)
        // logo depois da zona morta a saída começa perto de zero
        assertTrue(AxisMath.deadzone(0.13, 0.12) < 0.02)
        assertEquals(1.0, AxisMath.deadzone(1.0, 0.12), 1e-9)
        assertEquals(-1.0, AxisMath.deadzone(-1.0, 0.12), 1e-9)
    }

    @Test
    fun curva_maisPrecisaoPertoDoCentro() {
        val s = AxisSettings(minSpeed = 1, maxSpeed = 20, curveExponent = 2.0)
        val pouco = AxisMath.targetSpeed(0.25, s)
        val metade = AxisMath.targetSpeed(0.5, s)
        val tudo = AxisMath.targetSpeed(1.0, s)
        assertTrue(pouco < metade && metade < tudo)
        assertEquals(20.0, tudo, 1e-9)
        assertEquals(-20.0, AxisMath.targetSpeed(-1.0, s), 1e-9)
        assertEquals(0.0, AxisMath.targetSpeed(0.0, s), 1e-9)
    }

    @Test
    fun travaDeDiagonal() {
        // quase horizontal: tilt é ignorado
        assertEquals(0.9 to 0.0, AxisMath.directionLock(0.9, 0.1, 18.0))
        // quase vertical: pan é ignorado
        assertEquals(0.0 to -0.9, AxisMath.directionLock(0.1, -0.9, 18.0))
        // diagonal de verdade continua diagonal
        assertEquals(0.7 to 0.7, AxisMath.directionLock(0.7, 0.7, 18.0))
        // desligada
        assertEquals(0.9 to 0.1, AxisMath.directionLock(0.9, 0.1, 0.0))
    }

    @Test
    fun rampa_sobeAosPoucos() {
        val s = AxisSettings(minSpeed = 1, maxSpeed = 20, accelPerSecond = 10.0)
        val ch = AxisChannel()
        val v1 = ch.step(20.0, 0.1, s)
        val v2 = ch.step(20.0, 0.1, s)
        assertTrue("começa devagar", v1 <= 2.0 + 1e-9)
        assertTrue("sobe", v2 > v1)
        repeat(30) { ch.step(20.0, 0.1, s) }
        assertEquals(20.0, ch.current, 1e-9)
    }

    @Test
    fun rampa_soltarParaNaHora() {
        val s = AxisSettings(minSpeed = 1, maxSpeed = 20, accelPerSecond = 100.0)
        val ch = AxisChannel()
        repeat(10) { ch.step(20.0, 0.1, s) }
        assertEquals(0.0, ch.step(0.0, 0.02, s), 1e-9)
    }

    @Test
    fun rampa_inversaoPassaPeloZero() {
        val s = AxisSettings(minSpeed = 1, maxSpeed = 20, accelPerSecond = 0.0, decelPerSecond = 100.0)
        val ch = AxisChannel()
        ch.step(20.0, 0.02, s)
        val v = ch.step(-20.0, 0.02, s)
        assertTrue("ainda no sentido antigo, reduzindo", v > 0 && v < 20.0)
    }

    @Test
    fun semRampa_velocidadeImediata() {
        val s = AxisSettings(minSpeed = 1, maxSpeed = 20, accelPerSecond = 0.0)
        assertEquals(15.0, AxisChannel().step(15.0, 0.02, s), 1e-9)
    }
}
