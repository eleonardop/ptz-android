package com.ptzstudio.android.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * Descobre sozinho o endereço RTSP do vídeo de uma câmera:
 *  1. pergunta à própria câmera pelo ONVIF (GetStreamUri), sem senha;
 *  2. se não der, testa os caminhos mais comuns na porta 554 (pedido DESCRIBE), começando pelo das câmeras PTZ
 *     como a LJ510 (/live/av0).
 */
object RtspFinder {
    /** Caminhos comuns, do mais provável para câmeras PTZ de transmissão ao menos provável. */
    val COMMON_PATHS = listOf(
        "/live/av0", "/live/av1", "/stream1", "/live/ch00_0", "/av0_0", "/h264/ch1/main/av_stream",
        "/cam/realmonitor?channel=1&subtype=0", "/11", "/1", "/live/main", "/ch01.264", "/media/video1", "/h264", "/live",
    )

    // ---------------- ONVIF ----------------

    private fun envelope(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" """ +
            """xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" """ +
            """xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body>$body</s:Body></s:Envelope>"""

    fun tagText(xml: String, local: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?$local(?:\\s[^>]*)?>([^<]*)</(?:[A-Za-z0-9_]+:)?$local>").find(xml)?.groupValues?.get(1)?.trim()

    /** XAddr de um serviço (Media) na resposta do GetCapabilities. */
    fun serviceXaddr(xml: String, service: String): String? {
        val block = Regex("<(?:[A-Za-z0-9_]+:)?$service(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$service>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return null
        return tagText(block, "XAddr")?.takeIf { it.startsWith("http") }
    }

    /** Token do primeiro perfil (o principal, de maior qualidade). */
    fun firstProfileToken(xml: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?Profiles\\s[^>]*token=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)

    /** Endereço do GetStreamUri; troca o IP se a câmera responder com outro (ex.: 0.0.0.0). */
    fun streamUri(xml: String, cameraIp: String): String? {
        val uri = tagText(xml, "Uri")?.replace("&amp;", "&") ?: return null
        if (!uri.startsWith("rtsp://")) return null
        return fixHost(uri, cameraIp)
    }

    fun fixHost(uri: String, ip: String): String {
        val m = Regex("^rtsp://(?:[^@/]*@)?([^:/]+)").find(uri) ?: return uri
        val host = m.groupValues[1]
        return if (host == ip) uri else uri.replaceRange(m.groups[1]!!.range, ip)
    }

    private fun post(open: (URL) -> URLConnection, url: String, body: String, timeoutMs: Int): String? = try {
        val c = open(URL(url)) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        val bytes = envelope(body).toByteArray()
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode in 200..299) c.inputStream.use { String(it.readBytes()) } else null
    } catch (_: Exception) {
        null
    }

    /** Pergunta o RTSP pelo ONVIF sem senha (muitas câmeras respondem). Nulo se a câmera exigir senha ou não souber. */
    fun viaOnvif(deviceXaddr: String, cameraIp: String, open: (URL) -> URLConnection, timeoutMs: Int = 2500): String? {
        val caps = post(open, deviceXaddr, "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>", timeoutMs)
        val media = caps?.let { serviceXaddr(it, "Media") } ?: deviceXaddr
        val profiles = post(open, media, "<trt:GetProfiles/>", timeoutMs) ?: return null
        val token = firstProfileToken(profiles) ?: return null
        val r = post(
            open, media,
            "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol>" +
                "</tt:Transport></trt:StreamSetup><trt:ProfileToken>$token</trt:ProfileToken></trt:GetStreamUri>",
            timeoutMs,
        ) ?: return null
        return streamUri(r, cameraIp)
    }

    // ---------------- Teste direto na porta 554 ----------------

    /** Manda um DESCRIBE e devolve o código da resposta (200 = existe, 401 = existe mas pede senha, 404 = não existe). */
    fun describe(ip: String, port: Int, path: String, newSocket: () -> Socket, timeoutMs: Int = 1500): Int? = try {
        newSocket().use { s ->
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s.soTimeout = timeoutMs
            val req = "DESCRIBE rtsp://$ip:$port$path RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n" +
                "User-Agent: PTZ Studio Android\r\n\r\n"
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return null
            Regex("^RTSP/\\d\\.\\d\\s+(\\d{3})").find(line)?.groupValues?.get(1)?.toIntOrNull()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Escolhe o caminho pelos códigos de cada teste: o primeiro que respondeu 200. Se todos pedem senha, só vale
     * quando algum outro deu "não existe" (senão a câmera pede senha antes de olhar o caminho e não dá para saber).
     */
    fun choose(results: List<Pair<String, Int?>>): String? {
        results.firstOrNull { it.second == 200 }?.let { return it.first }
        val anyMissing = results.any { it.second == 404 || it.second == 400 }
        if (anyMissing) results.firstOrNull { it.second == 401 }?.let { return it.first }
        return null
    }

    /** Procura pelos caminhos comuns, parando no primeiro que responde 200. */
    fun viaProbe(ip: String, newSocket: () -> Socket, port: Int = 554, describeFn: (String) -> Int? = { describe(ip, port, it, newSocket) }): String? {
        val results = mutableListOf<Pair<String, Int?>>()
        for (p in COMMON_PATHS) {
            val code = describeFn(p)
            results += p to code
            if (code == 200) break
            // Porta fechada ou câmera muda: não adianta testar o resto.
            if (code == null && results.size == 1) return null
        }
        return choose(results)?.let { "rtsp://$ip:$port$it" }
    }

    /** Junta os dois jeitos. Bloqueia: chamar fora da thread da tela. */
    fun find(ip: String, deviceXaddr: String?, open: (URL) -> URLConnection, newSocket: () -> Socket): String? {
        if (deviceXaddr != null) viaOnvif(deviceXaddr, ip, open)?.let { return it }
        return viaProbe(ip, newSocket)
    }
}
