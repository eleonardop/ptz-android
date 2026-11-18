package com.ptzstudio.android.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sign

/** Ajustes de um eixo (pan, tilt ou zoom). Velocidades em unidades VISCA. */
data class AxisSettings(
    val deadzone: Double = 0.12,
    val minSpeed: Int = 1,
    val maxSpeed: Int = 12,
    /** Expoente da curva: 1 = linear; 2 = mais precisão perto do centro. */
    val curveExponent: Double = 2.0,
    /** Unidades de velocidade por segundo ao acelerar. 0 = sem rampa (instantâneo). */
    val accelPerSecond: Double = 20.0,
    /** Unidades por segundo ao reduzir (ainda com o analógico inclinado). */
    val decelPerSecond: Double = 120.0,
)

object AxisMath {
    /** Remove a zona morta e reescala o restante para 0..1, sem salto na saída da zona. */
    fun deadzone(v: Double, dz: Double): Double {
        val d = dz.coerceIn(0.0, 0.95)
        val a = abs(v)
        if (a <= d) return 0.0
        return sign(v) * minOf(1.0, (a - d) / (1.0 - d))
    }

    /** Curva de resposta 0..1 -> 0..1. */
    fun curve(a: Double, exponent: Double): Double =
        a.coerceIn(0.0, 1.0).pow(exponent.coerceIn(1.0, 6.0))

    /** Velocidade-alvo (com sinal) para uma entrada já sem zona morta. */
    fun targetSpeed(v: Double, s: AxisSettings): Double {
        val a = abs(v)
        if (a <= 0.0) return 0.0
        val c = curve(a, s.curveExponent)
        val min = s.minSpeed.coerceAtLeast(1)
        val max = s.maxSpeed.coerceAtLeast(min)
        return sign(v) * (min + (max - min) * c)
    }

    /**
     * Evita diagonais indesejadas: se o analógico está quase na horizontal (ou vertical),
     * o outro eixo é zerado. toleranceDeg = ângulo de tolerância (0 = desligado).
     */
    fun directionLock(x: Double, y: Double, toleranceDeg: Double): Pair<Double, Double> {
        val ax = abs(x)
        val ay = abs(y)
        if (ax == 0.0 || ay == 0.0) return x to y
        val tol = toleranceDeg.coerceIn(0.0, 44.0)
        val angle = Math.toDegrees(atan2(ay, ax)) // 0 = horizontal puro
        if (angle < tol) return x to 0.0
        if (90.0 - angle < tol) return 0.0 to y
        return x to y
    }
}

/** Rampa de aceleração de um eixo. Soltar (alvo 0) para na hora. */
class AxisChannel {
    var current = 0.0
        private set

    fun reset() {
        current = 0.0
    }

    fun step(target: Double, dt: Double, s: AxisSettings): Double {
        if (target == 0.0) {
            current = 0.0
            return 0.0
        }
        // Inversão de sentido: reduz até zero antes de ir para o outro lado (evita tranco na imagem).
        if (current != 0.0 && sign(target) != sign(current)) {
            if (s.decelPerSecond <= 0) {
                current = target
                return current
            }
            val down = s.decelPerSecond * dt
            current = if (abs(current) <= down) 0.0 else current - sign(current) * down
            return current
        }
        // Começa já na velocidade mínima, depois sobe aos poucos.
        if (current == 0.0) current = sign(target) * minOf(abs(target), s.minSpeed.toDouble().coerceAtLeast(1.0))
        val growing = abs(target) > abs(current)
        val rate = if (growing) s.accelPerSecond else s.decelPerSecond
        if (rate <= 0) {
            current = target
            return current
        }
        val stepSize = rate * dt
        val diff = target - current
        current = if (abs(diff) <= stepSize) target else current + sign(diff) * stepSize
        return current
    }
}
