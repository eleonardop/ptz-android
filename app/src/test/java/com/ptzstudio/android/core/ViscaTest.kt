package com.ptzstudio.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ViscaTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun panTiltDrive_direitaECima() {
        assertEquals("81 01 06 01 0A 05 02 01 FF", hex(ViscaCommands.panTiltDrive(1, 10, 5)))
    }

    @Test
    fun panTiltDrive_esquerdaEBaixo() {
        assertEquals("81 01 06 01 03 04 01 02 FF", hex(ViscaCommands.panTiltDrive(1, -3, -4)))
    }

    @Test
    fun panTiltDrive_soPanDeixaTiltParado() {
        assertEquals("81 01 06 01 07 01 02 03 FF", hex(ViscaCommands.panTiltDrive(1, 7, 0)))
    }

    @Test
    fun panTiltDrive_respeitaLimitesDaCamera() {
        assertEquals("81 01 06 01 18 14 02 01 FF", hex(ViscaCommands.panTiltDrive(1, 99, 99)))
    }

    @Test
    fun panTiltStop() {
        assertEquals("81 01 06 01 01 01 03 03 FF", hex(ViscaCommands.panTiltStop(1)))
    }

    @Test
    fun zoom_codigos() {
        assertEquals("81 01 04 07 27 FF", hex(ViscaCommands.zoom(1, 8)))
        assertEquals("81 01 04 07 20 FF", hex(ViscaCommands.zoom(1, 1)))
        assertEquals("81 01 04 07 33 FF", hex(ViscaCommands.zoom(1, -4)))
        assertEquals("81 01 04 07 00 FF", hex(ViscaCommands.zoom(1, 0)))
    }

    @Test
    fun foco_preset_home() {
        assertEquals("81 01 04 08 02 FF", hex(ViscaCommands.focus(1, 1)))
        assertEquals("81 01 04 08 03 FF", hex(ViscaCommands.focus(1, -1)))
        assertEquals("81 01 04 38 02 FF", hex(ViscaCommands.autoFocus(1, true)))
        assertEquals("81 01 04 3F 02 03 FF", hex(ViscaCommands.presetRecall(1, 3)))
        assertEquals("81 01 04 3F 01 05 FF", hex(ViscaCommands.presetSet(1, 5)))
        assertEquals("81 01 06 04 FF", hex(ViscaCommands.home(1)))
        assertEquals("81 09 00 02 FF", hex(ViscaCommands.versionInquiry(1)))
    }

    @Test
    fun brilho() {
        assertEquals("81 01 04 3E 02 FF", hex(ViscaCommands.expCompPower(1, true)))
        assertEquals("81 01 04 0E 02 FF", hex(ViscaCommands.expCompStep(1, 1)))
        assertEquals("81 01 04 0E 03 FF", hex(ViscaCommands.expCompStep(1, -1)))
    }

    @Test
    fun enderecoDaCamera() {
        assertEquals("83 01 06 04 FF", hex(ViscaCommands.home(3)))
        assertEquals(0x81.toByte(), ViscaCommands.header(0))
        assertEquals(0x87.toByte(), ViscaCommands.header(9))
    }

    @Test
    fun viscaOverIp_cabecalho() {
        val payload = ViscaCommands.home(1)
        val framed = ViscaFraming.wrap(payload, 0x01020304, ViscaFraming.TYPE_COMMAND)
        assertEquals("01 00 00 05 01 02 03 04 81 01 06 04 FF", hex(framed))
        assertEquals(ViscaFraming.TYPE_INQUIRY, ViscaFraming.typeFor(ViscaCommands.versionInquiry(1)))
        assertEquals(ViscaFraming.TYPE_COMMAND, ViscaFraming.typeFor(payload))
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0x01), ViscaFraming.resetSequence())
    }
}
