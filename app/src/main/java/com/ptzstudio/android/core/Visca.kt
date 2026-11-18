package com.ptzstudio.android.core

import kotlin.math.abs

/** Como os comandos VISCA viajam pela rede. */
enum class Transport(val label: String, val defaultPort: Int) {
    /** Pacote VISCA "puro" dentro do UDP (comum em câmeras PTZ genéricas). */
    RAW_UDP("UDP (VISCA puro)", 1259),

    /** VISCA over IP da Sony: cabeçalho de 8 bytes com número de sequência. */
    VISCA_OVER_IP("VISCA over IP (Sony)", 52381);

    companion object {
        fun fromName(name: String?): Transport = entries.firstOrNull { it.name == name } ?: RAW_UDP
    }
}

/**
 * Comandos VISCA padrão (mesmos bytes do PTZ Studio para Windows).
 * O suporte de cada comando na LJ510-30X-N só é confirmado com a câmera real.
 */
object ViscaCommands {
    const val MAX_PAN_SPEED = 0x18
    const val MAX_TILT_SPEED = 0x14
    /** Velocidades de zoom 1..8 (códigos VISCA 0..7). */
    const val MAX_ZOOM_SPEED = 8

    fun header(address: Int): Byte = (0x80 or address.coerceIn(1, 7)).toByte()

    /** pan: negativo = esquerda, positivo = direita. tilt: positivo = cima, negativo = baixo. 0 = parar o eixo. */
    fun panTiltDrive(
        address: Int,
        pan: Int,
        tilt: Int,
        maxPan: Int = MAX_PAN_SPEED,
        maxTilt: Int = MAX_TILT_SPEED,
    ): ByteArray {
        val ps = abs(pan).coerceIn(0, minOf(maxPan, MAX_PAN_SPEED)).coerceAtLeast(1)
        val ts = abs(tilt).coerceIn(0, minOf(maxTilt, MAX_TILT_SPEED)).coerceAtLeast(1)
        val pd = when {
            pan < 0 -> 0x01
            pan > 0 -> 0x02
            else -> 0x03
        }
        val td = when {
            tilt > 0 -> 0x01
            tilt < 0 -> 0x02
            else -> 0x03
        }
        return bytes(header(address).toInt(), 0x01, 0x06, 0x01, ps, ts, pd, td, 0xFF)
    }

    fun panTiltStop(address: Int): ByteArray = panTiltDrive(address, 0, 0)

    /** speed: positivo = aproximar (tele), negativo = afastar (wide), 0 = parar. Faixa 1..8. */
    fun zoom(address: Int, speed: Int, max: Int = MAX_ZOOM_SPEED): ByteArray {
        val s = abs(speed).coerceIn(0, minOf(max, MAX_ZOOM_SPEED))
        val code = when {
            speed == 0 -> 0x00
            speed > 0 -> 0x20 or (s - 1).coerceIn(0, 7)
            else -> 0x30 or (s - 1).coerceIn(0, 7)
        }
        return bytes(header(address).toInt(), 0x01, 0x04, 0x07, code, 0xFF)
    }

    /** dir: positivo = foco distante, negativo = foco próximo, 0 = parar. */
    fun focus(address: Int, dir: Int): ByteArray {
        val code = when {
            dir > 0 -> 0x02
            dir < 0 -> 0x03
            else -> 0x00
        }
        return bytes(header(address).toInt(), 0x01, 0x04, 0x08, code, 0xFF)
    }

    fun autoFocus(address: Int, on: Boolean): ByteArray =
        bytes(header(address).toInt(), 0x01, 0x04, 0x38, if (on) 0x02 else 0x03, 0xFF)

    fun presetSet(address: Int, slot: Int): ByteArray =
        bytes(header(address).toInt(), 0x01, 0x04, 0x3F, 0x01, slot.coerceIn(0, 254), 0xFF)

    fun presetRecall(address: Int, slot: Int): ByteArray =
        bytes(header(address).toInt(), 0x01, 0x04, 0x3F, 0x02, slot.coerceIn(0, 254), 0xFF)

    fun home(address: Int): ByteArray = bytes(header(address).toInt(), 0x01, 0x06, 0x04, 0xFF)

    /** Consulta de versão: usada só para saber se a câmera responde. */
    fun versionInquiry(address: Int): ByteArray = bytes(header(address).toInt(), 0x09, 0x00, 0x02, 0xFF)

    /** Compensação de exposição ligada/desligada (necessária antes de subir/descer o brilho). */
    fun expCompPower(address: Int, on: Boolean): ByteArray =
        bytes(header(address).toInt(), 0x01, 0x04, 0x3E, if (on) 0x02 else 0x03, 0xFF)

    /** dir: positivo = mais claro, negativo = mais escuro, 0 = voltar ao padrão. */
    fun expCompStep(address: Int, dir: Int): ByteArray {
        val code = when {
            dir > 0 -> 0x02
            dir < 0 -> 0x03
            else -> 0x00
        }
        return bytes(header(address).toInt(), 0x01, 0x04, 0x0E, code, 0xFF)
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
}

/** Cabeçalho do VISCA over IP: tipo (2 bytes), tamanho (2 bytes) e sequência (4 bytes), big-endian. */
object ViscaFraming {
    const val TYPE_COMMAND = 0x0100
    const val TYPE_INQUIRY = 0x0110
    const val TYPE_CONTROL = 0x0200

    fun wrap(payload: ByteArray, sequence: Long, type: Int): ByteArray {
        val data = ByteArray(8 + payload.size)
        data[0] = (type shr 8).toByte()
        data[1] = (type and 0xFF).toByte()
        data[2] = ((payload.size shr 8) and 0xFF).toByte()
        data[3] = (payload.size and 0xFF).toByte()
        data[4] = (sequence shr 24).toByte()
        data[5] = (sequence shr 16).toByte()
        data[6] = (sequence shr 8).toByte()
        data[7] = sequence.toByte()
        payload.copyInto(data, 8)
        return data
    }

    /** Pede à câmera para zerar o número de sequência. */
    fun resetSequence(): ByteArray = wrap(byteArrayOf(0x01), 0, TYPE_CONTROL)

    /** Comando (0x01 no 2º byte) ou consulta (0x09). */
    fun typeFor(payload: ByteArray): Int =
        if (payload.size > 1 && payload[1].toInt() == 0x09) TYPE_INQUIRY else TYPE_COMMAND
}
