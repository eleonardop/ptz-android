package com.ptzstudio.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingTest {
    @Test
    fun listaPronta_cobreTodosOsBotoes() {
        assertEquals(23, PadButton.entries.size)
        assertEquals(PadButton.entries.toSet(), DefaultMapping.map.keys)
        for ((b, a) in DefaultMapping.map) assertTrue("$b -> $a", b.kind in a.kinds)
    }

    @Test
    fun listaPronta_comoCombinado() {
        val m = DefaultMapping.map
        assertEquals(PadAction.TILT_UP, m[PadButton.DPAD_UP])
        assertEquals(PadAction.TILT_DOWN, m[PadButton.DPAD_DOWN])
        assertEquals(PadAction.PAN_LEFT, m[PadButton.DPAD_LEFT])
        assertEquals(PadAction.PAN_RIGHT, m[PadButton.DPAD_RIGHT])
        assertEquals(PadAction.PRESET_1, m[PadButton.A])
        assertEquals(PadAction.PRESET_2, m[PadButton.B])
        assertEquals(PadAction.PRESET_3, m[PadButton.X])
        assertEquals(PadAction.PRESET_4, m[PadButton.Y])
        assertEquals(PadAction.NONE, m[PadButton.HOME])
        assertEquals(PadAction.ZOOM_IN_PRESSURE, m[PadButton.RT])
        assertEquals(PadAction.ZOOM_OUT_PRESSURE, m[PadButton.LT])
        assertEquals(PadAction.STICK_MOVE, m[PadButton.LS])
    }

    @Test
    fun opcoes_porTipoDeBotao() {
        val stick = PadAction.optionsFor(PadButton.LS)
        assertTrue(PadAction.STICK_MOVE in stick && PadAction.PRESET_1 !in stick)
        val trig = PadAction.optionsFor(PadButton.RT)
        assertTrue(PadAction.ZOOM_IN_PRESSURE in trig && PadAction.PRESET_1 in trig && PadAction.STICK_MOVE !in trig)
        val btn = PadAction.optionsFor(PadButton.A)
        assertTrue(PadAction.PRESET_8 in btn && PadAction.ZOOM_IN_PRESSURE !in btn)
        assertEquals(PadAction.NONE, btn.first())
    }

    @Test
    fun sanitize_completaECorrige() {
        val m = DefaultMapping.sanitize(mapOf(PadButton.A to PadAction.STICK_MOVE, PadButton.B to PadAction.HOME))
        assertEquals(PadAction.PRESET_1, m[PadButton.A]) // função inválida para botão -> padrão
        assertEquals(PadAction.HOME, m[PadButton.B])
        assertEquals(23, m.size)
    }

    @Test
    fun presetNumber() {
        assertEquals(3, PadAction.PRESET_3.presetNumber)
        assertEquals(null, PadAction.HOME.presetNumber)
    }

    private val sent = mutableListOf<ByteArray>()
    private val controller = PtzController { sent += it }
    private val actions = mutableListOf<PadAction>()
    private val engine = MappingEngine(controller) { actions += it }
    private val map = DefaultMapping.map

    @Test
    fun preset_disparaUmaVezPorToque() {
        engine.update(PadState(pressed = setOf(PadButton.A)), map)
        engine.update(PadState(pressed = setOf(PadButton.A)), map)
        engine.update(PadState(), map)
        engine.update(PadState(pressed = setOf(PadButton.A)), map)
        assertEquals(listOf(PadAction.PRESET_1, PadAction.PRESET_1), actions)
    }

    @Test
    fun gatilhos_viramZoomProporcional() {
        controller.config = MotionConfig(rampPercent = 0)
        engine.update(PadState(rt = 1.0), map)
        var now = 0L
        repeat(3) { now += 20; controller.tick(0.02, now) }
        assertEquals(8, controller.lastSentZoom)
        engine.update(PadState(lt = 1.0), map)
        repeat(3) { now += 20; controller.tick(0.02, now) }
        assertEquals(-8, controller.lastSentZoom)
        engine.update(PadState(), map)
        now += 20; controller.tick(0.02, now)
        assertEquals(0, controller.lastSentZoom)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun gatilhoComoBotao_quandoMapeadoParaPreset() {
        val custom = map + (PadButton.RT to PadAction.PRESET_5)
        engine.update(PadState(rt = 0.3), custom)
        assertTrue(actions.isEmpty())
        engine.update(PadState(rt = 0.9), custom)
        assertEquals(listOf(PadAction.PRESET_5), actions)
    }

    @Test
    fun direcional_moveACamera() {
        controller.config = MotionConfig(rampPercent = 0)
        engine.update(PadState(pressed = setOf(PadButton.DPAD_RIGHT)), map)
        var now = 0L
        repeat(3) { now += 20; controller.tick(0.02, now) }
        assertTrue(controller.lastSentPan > 0)
        assertEquals(0, controller.lastSentTilt)
        engine.update(PadState(pressed = setOf(PadButton.DPAD_UP)), map)
        repeat(20) { now += 20; controller.tick(0.02, now) }
        assertEquals(0, controller.lastSentPan)
        assertTrue(controller.lastSentTilt > 0)
    }

    @Test
    fun analogicoDireito_movimentoFino() {
        controller.config = MotionConfig(rampPercent = 0, speedPercent = 100)
        engine.update(PadState(rsX = 1.0), map)
        var now = 0L
        repeat(3) { now += 20; controller.tick(0.02, now) }
        val fino = controller.lastSentPan
        engine.update(PadState(), map)
        repeat(3) { now += 20; controller.tick(0.02, now) }
        engine.update(PadState(lsX = 1.0), map)
        repeat(3) { now += 20; controller.tick(0.02, now) }
        assertTrue("fino=$fino normal=${controller.lastSentPan}", fino in 1 until controller.lastSentPan)
    }

    @Test
    fun botaoSemFuncao_naoFazNada() {
        engine.update(PadState(pressed = setOf(PadButton.HOME, PadButton.M, PadButton.VIEW)), map)
        controller.tick(0.02, 20)
        assertTrue(actions.isEmpty())
        assertTrue(sent.isEmpty())
    }
}
