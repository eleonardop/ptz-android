package com.ptzstudio.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Testes com UDP de verdade, mas só dentro do próprio computador (127.0.0.1). Não substituem o teste com a câmera. */
class NetTest {
    private fun fakeCamera(reply: Boolean): Pair<DatagramSocket, MutableList<ByteArray>> {
        val s = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val got = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        Thread {
            val buf = ByteArray(512)
            while (!s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    got += p.data.copyOf(p.length)
                    if (reply) {
                        val ack = byteArrayOf(0x90.toByte(), 0x50, 0x00, 0xFF.toByte())
                        s.send(DatagramPacket(ack, ack.size, p.socketAddress))
                    }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
        return s to got
    }

    private fun waitFor(timeoutMs: Long = 2000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    fun udpPuro_enviaOPacoteSemCabecalho() {
        val (cam, got) = fakeCamera(reply = true)
        val replies = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val client = ViscaUdpClient { replies += it }
        client.connect("127.0.0.1", cam.localPort, Transport.RAW_UDP)
        client.send(ViscaCommands.home(1))
        assertTrue(waitFor { got.isNotEmpty() })
        assertArrayEquals(ViscaCommands.home(1), got[0])
        assertTrue("recebeu a resposta", waitFor { replies.isNotEmpty() })
        client.close()
        cam.close()
    }

    @Test
    fun viscaOverIp_zeraSequenciaENumeraComandos() {
        val (cam, got) = fakeCamera(reply = false)
        val client = ViscaUdpClient()
        client.connect("127.0.0.1", cam.localPort, Transport.VISCA_OVER_IP)
        client.send(ViscaCommands.home(1))
        client.send(ViscaCommands.versionInquiry(1))
        assertTrue(waitFor { got.size >= 3 })
        assertArrayEquals(ViscaFraming.resetSequence(), got[0])
        assertArrayEquals(ViscaFraming.wrap(ViscaCommands.home(1), 1, ViscaFraming.TYPE_COMMAND), got[1])
        assertArrayEquals(ViscaFraming.wrap(ViscaCommands.versionInquiry(1), 2, ViscaFraming.TYPE_INQUIRY), got[2])
        client.close()
        cam.close()
    }

    @Test
    fun sondagem_cameraQueResponde_eQueNaoResponde() {
        val (cam, _) = fakeCamera(reply = true)
        assertTrue(CameraProbe.isAlive("127.0.0.1", cam.localPort, Transport.RAW_UDP, timeoutMs = 1000))
        cam.close()
        val (mute, _) = fakeCamera(reply = false)
        assertFalse(CameraProbe.isAlive("127.0.0.1", mute.localPort, Transport.RAW_UDP, timeoutMs = 300))
        mute.close()
    }

    @Test
    fun descobertaOnvif_leIpENome() {
        val xml = """<?xml version="1.0"?><SOAP-ENV:Envelope><SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch>
            <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/PTZ%20Camera onvif://www.onvif.org/hardware/LJ510</d:Scopes>
            <d:XAddrs>http://192.168.4.163:8899/onvif/device_service http://[fe80::1]/onvif</d:XAddrs>
            </d:ProbeMatch></d:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
        val f = WsDiscovery.parse(xml, "10.0.0.9")
        assertNotNull(f)
        assertEquals("192.168.4.163", f!!.ip)
        assertEquals("PTZ Camera", f.name)
        assertEquals("http://192.168.4.163:8899/onvif/device_service", f.xaddr)
    }

    @Test
    fun descobertaOnvif_semXaddrUsaOIpDeQuemRespondeu() {
        val xml = "<d:ProbeMatches><d:ProbeMatch><d:Scopes>onvif://www.onvif.org/hardware/IPC</d:Scopes></d:ProbeMatch></d:ProbeMatches>"
        val f = WsDiscovery.parse(xml, "192.168.1.50")
        assertEquals("192.168.1.50", f!!.ip)
        assertEquals("IPC", f.name)
    }

    @Test
    fun descobertaOnvif_ignoraOutrasMensagens() {
        assertNull(WsDiscovery.parse("<Hello/>", "1.2.3.4"))
        assertTrue(WsDiscovery.probeMessage("abc").contains("uuid:abc"))
    }
}
