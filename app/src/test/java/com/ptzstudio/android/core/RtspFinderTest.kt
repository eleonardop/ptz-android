package com.ptzstudio.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RtspFinderTest {
    @Test
    fun leRespostasOnvif() {
        val caps = """<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Device><tt:XAddr>http://x/dev</tt:XAddr></tt:Device><tt:Media><tt:XAddr>http://192.168.4.163:8899/onvif/Media</tt:XAddr></tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>"""
        assertEquals("http://192.168.4.163:8899/onvif/Media", RtspFinder.serviceXaddr(caps, "Media"))
        val prof = """<trt:GetProfilesResponse><trt:Profiles fixed="true" token="PROFILE_000"><tt:Name>main</tt:Name></trt:Profiles><trt:Profiles token="PROFILE_001"/></trt:GetProfilesResponse>"""
        assertEquals("PROFILE_000", RtspFinder.firstProfileToken(prof))
        val uri = """<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://0.0.0.0:554/live/av0?a=1&amp;b=2</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"""
        assertEquals("rtsp://192.168.4.163:554/live/av0?a=1&b=2", RtspFinder.streamUri(uri, "192.168.4.163"))
        assertEquals("rtsp://u:p@10.0.0.2/x", RtspFinder.fixHost("rtsp://u:p@1.1.1.1/x", "10.0.0.2"))
    }

    @Test
    fun escolheOCaminho() {
        // A LJ510 responde 200 em /live/av0: para ali.
        val asked = mutableListOf<String>()
        val r = RtspFinder.viaProbe("192.168.4.163", { Socket() }, describeFn = { asked += it; if (it == "/live/av0") 200 else 404 })
        assertEquals("rtsp://192.168.4.163:554/live/av0", r)
        assertEquals(listOf("/live/av0"), asked)
        // Pede senha só no caminho certo (os outros não existem): usa ele.
        assertEquals("/stream1", RtspFinder.choose(listOf("/live/av0" to 404, "/stream1" to 401, "/x" to 404)))
        // Pede senha em tudo: não dá para saber o caminho.
        assertNull(RtspFinder.choose(listOf("/live/av0" to 401, "/stream1" to 401)))
        // Porta fechada: desiste logo.
        assertNull(RtspFinder.viaProbe("1.2.3.4", { Socket() }, describeFn = { null }))
    }

    @Test
    fun describeDeVerdade_numServidorRtspDeTeste() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                repeat(2) {
                    server.accept().use { c ->
                        val line = c.getInputStream().bufferedReader().readLine()
                        val code = if (line.contains("/live/av0 ")) "200 OK" else "404 Not Found"
                        c.getOutputStream().write("RTSP/1.0 $code\r\nCSeq: 2\r\n\r\n".toByteArray())
                    }
                }
            }
            assertEquals(200, RtspFinder.describe("127.0.0.1", server.localPort, "/live/av0", { Socket() }))
            assertEquals(404, RtspFinder.describe("127.0.0.1", server.localPort, "/stream1", { Socket() }))
        }
    }
}
