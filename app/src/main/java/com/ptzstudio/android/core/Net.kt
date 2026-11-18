package com.ptzstudio.android.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Cliente VISCA por UDP. Os envios saem em ordem por uma única thread; uma segunda thread
 * escuta as respostas só para saber se a câmera está respondendo.
 */
class ViscaUdpClient(
    /** Prepara cada socket novo antes do uso (no app: prende o socket à rede Wi-Fi). */
    private val prepareSocket: (DatagramSocket) -> Unit = {},
    private val onReply: (ByteArray) -> Unit = {},
) : PacketSender {
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "visca-envio").apply { isDaemon = true }
    }
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var target: InetSocketAddress? = null
    @Volatile private var transport: Transport = Transport.RAW_UDP
    private val sequence = AtomicLong(0)
    @Volatile private var generation = 0

    /** Aponta para uma câmera. Pode ser chamado de novo para trocar de câmera. */
    fun connect(ip: String, port: Int, transport: Transport) {
        sendExecutor.execute {
            closeSocket()
            generation++
            val gen = generation
            try {
                val s = DatagramSocket()
                try { prepareSocket(s) } catch (_: Exception) {}
                socket = s
                target = InetSocketAddress(InetAddress.getByName(ip.trim()), port)
                this.transport = transport
                sequence.set(0)
                startReceiver(s, gen)
                if (transport == Transport.VISCA_OVER_IP) rawSend(ViscaFraming.resetSequence())
            } catch (_: Exception) {
                target = null
            }
        }
    }

    /** VISCA over IP: pede à câmera para zerar a sequência (ex.: depois que ela reiniciou). */
    fun resetSequence() {
        sendExecutor.execute {
            if (transport == Transport.VISCA_OVER_IP) {
                sequence.set(0)
                rawSend(ViscaFraming.resetSequence())
            }
        }
    }

    override fun send(packet: ByteArray) {
        sendExecutor.execute {
            val data = if (transport == Transport.VISCA_OVER_IP) {
                ViscaFraming.wrap(packet, sequence.incrementAndGet(), ViscaFraming.typeFor(packet))
            } else {
                packet
            }
            rawSend(data)
        }
    }

    private fun rawSend(data: ByteArray) {
        val s = socket ?: return
        val t = target ?: return
        try {
            s.send(DatagramPacket(data, data.size, t))
        } catch (_: Exception) {
            // Rede indisponível: o próximo ciclo tenta de novo.
        }
    }

    private fun startReceiver(s: DatagramSocket, gen: Int) {
        Thread({
            val buf = ByteArray(512)
            while (!s.isClosed && gen == generation) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    onReply(p.data.copyOf(p.length))
                } catch (_: Exception) {
                    if (s.isClosed) break
                }
            }
        }, "visca-respostas").apply { isDaemon = true }.start()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun close() {
        sendExecutor.execute { closeSocket(); generation++ }
    }

    /** Espera os envios pendentes saírem (usado ao fechar o app). */
    fun flush(timeoutMs: Long = 300) {
        val f = sendExecutor.submit {}
        try { f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }
}

/** Pergunta a uma câmera se ela responde (consulta de versão VISCA). */
object CameraProbe {
    fun isAlive(
        ip: String,
        port: Int,
        transport: Transport,
        address: Int = 1,
        timeoutMs: Int = 1200,
        prepareSocket: (DatagramSocket) -> Unit = {},
    ): Boolean {
        return try {
            DatagramSocket().use { s ->
                try { prepareSocket(s) } catch (_: Exception) {}
                s.soTimeout = timeoutMs
                val addr = InetSocketAddress(InetAddress.getByName(ip.trim()), port)
                val inquiry = ViscaCommands.versionInquiry(address)
                if (transport == Transport.VISCA_OVER_IP) {
                    val reset = ViscaFraming.resetSequence()
                    s.send(DatagramPacket(reset, reset.size, addr))
                }
                val data = if (transport == Transport.VISCA_OVER_IP) {
                    ViscaFraming.wrap(inquiry, 1, ViscaFraming.TYPE_INQUIRY)
                } else {
                    inquiry
                }
                s.send(DatagramPacket(data, data.size, addr))
                val buf = ByteArray(256)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        return false
                    }
                    if (p.length > 0) return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Câmera encontrada na rede pela descoberta ONVIF. */
data class FoundCamera(val ip: String, val name: String)

/** Descoberta ONVIF (WS-Discovery): pergunta na rede quem é câmera de vídeo. */
object WsDiscovery {
    const val MULTICAST_IP = "239.255.255.250"
    const val PORT = 3702

    fun probeMessage(id: String = UUID.randomUUID().toString()): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" """ +
            """xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" """ +
            """xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" """ +
            """xmlns:dn="http://www.onvif.org/ver10/network/wsdl">""" +
            "<e:Header><w:MessageID>uuid:$id</w:MessageID>" +
            "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>" +
            "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"

    /** Lê a resposta (ProbeMatch) e devolve o IP e um nome amigável. Nulo se não for uma resposta válida. */
    fun parse(xml: String, senderIp: String?): FoundCamera? {
        if (!xml.contains("ProbeMatch")) return null
        val xaddrs = Regex("<[^>]*XAddrs[^>]*>([^<]*)</[^>]*XAddrs>").find(xml)?.groupValues?.get(1).orEmpty()
        val ipFromXaddr = Regex("""https?://\[?([0-9a-fA-F:.]+?)\]?(?::\d+)?/""").findAll(xaddrs)
            .map { it.groupValues[1] }
            .firstOrNull { it.count { c -> c == '.' } == 3 }
        val ip = ipFromXaddr ?: senderIp ?: return null
        val scopes = Regex("<[^>]*Scopes[^>]*>([^<]*)</[^>]*Scopes>").find(xml)?.groupValues?.get(1).orEmpty()
        fun scope(key: String): String? = scopes.split(Regex("\\s+"))
            .firstOrNull { it.contains("/$key/") }
            ?.substringAfter("/$key/")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.isNotBlank() }
        val name = scope("name") ?: scope("hardware") ?: "Câmera ONVIF"
        return FoundCamera(ip, name)
    }

    /** Envia a pergunta e junta as respostas por [timeoutMs]. Bloqueia: chamar fora da thread principal. */
    fun discover(timeoutMs: Int = 3000, prepareSocket: (DatagramSocket) -> Unit = {}): List<FoundCamera> {
        val found = LinkedHashMap<String, FoundCamera>()
        try {
            DatagramSocket().use { s ->
                try { prepareSocket(s) } catch (_: Exception) {}
                s.soTimeout = 400
                s.broadcast = true
                val msg = probeMessage().toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName(MULTICAST_IP)
                repeat(2) { s.send(DatagramPacket(msg, msg.size, group, PORT)) }
                val buf = ByteArray(16 * 1024)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val xml = String(p.data, 0, p.length, Charsets.UTF_8)
                    parse(xml, p.address?.hostAddress)?.let { found.putIfAbsent(it.ip, it) }
                }
            }
        } catch (_: Exception) {
        }
        return found.values.toList()
    }
}
