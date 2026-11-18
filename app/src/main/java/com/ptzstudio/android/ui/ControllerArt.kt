package com.ptzstudio.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.ptzstudio.android.core.PadButton

/*
 * Desenho do controle (frente e costas), feito com formas simples num quadro de 366 x 250 unidades.
 * O botão em azul é o selecionado ou o que está sendo apertado agora.
 */

private const val VB_W = 366f
private const val VB_H = 250f

private const val BODY =
    "M70 10 C120 4 246 4 296 10 C330 16 352 40 358 80 C364 120 368 190 358 222 C350 244 318 246 304 226 " +
        "C290 204 276 166 254 146 C236 134 130 134 112 146 C90 166 76 204 62 226 C48 246 16 244 8 222 " +
        "C-2 190 2 120 8 80 C14 40 36 16 70 10 Z"
private const val CENTER_PANEL = "M123 7 H243 L221 55 Q219 59 214 59 H152 Q147 59 145 55 Z"
private const val GRIP_L = "M12 104 C8 150 6 200 12 222 C20 240 50 240 60 224 C72 204 86 170 104 150 C80 138 48 118 12 104 Z"
private const val GRIP_R = "M354 104 C358 150 360 200 354 222 C346 240 316 240 306 224 C294 204 280 170 262 150 C286 138 318 118 354 104 Z"
private const val TOP_RIM = "M70 10 C120 4 246 4 296 10"
private const val TRIG_L = "M78 26 C80 20 124 20 126 26 C130 40 130 58 124 68 C118 78 86 78 80 68 C74 58 74 40 78 26 Z"
private const val TRIG_R = "M288 26 C286 20 242 20 240 26 C236 40 236 58 242 68 C248 78 280 78 286 68 C292 58 292 40 288 26 Z"
private const val BUMP_L = "M70 26 C70 8 82 2 102 2 C122 2 134 8 134 26 C120 20 84 20 70 26 Z"
private const val BUMP_R = "M296 26 C296 8 284 2 264 2 C244 2 232 8 232 26 C246 20 282 20 296 26 Z"
private const val PADDLE_BAR = "M84 116 C92 104 110 102 130 102 H236 C256 102 274 104 282 116 C276 124 262 126 248 126 H118 C104 126 90 124 84 116 Z"
private const val PADDLE_R4 = "M90 115 C96 107 110 106 128 106 H180 V122 H118 C106 122 94 121 90 115 Z"
private const val PADDLE_L4 = "M276 115 C270 107 256 106 238 106 H186 V122 H248 C260 122 272 121 276 115 Z"

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

private object Paths {
    val body = parse(BODY)
    val centerPanel = parse(CENTER_PANEL)
    val gripL = parse(GRIP_L)
    val gripR = parse(GRIP_R)
    val topRim = parse(TOP_RIM)
    val trigL = parse(TRIG_L)
    val trigR = parse(TRIG_R)
    val bumpL = parse(BUMP_L)
    val bumpR = parse(BUMP_R)
    val paddleBar = parse(PADDLE_BAR)
    val paddleR4 = parse(PADDLE_R4)
    val paddleL4 = parse(PADDLE_L4)
}

private val HighlightColor = Color(0xFF3B82F6)
private val Dark = Color(0xFF2B2E33)
private val DarkShadow = Color(0xFF121417)
private val LetterColor = Color(0xFFC9CDD2)

private fun base(b: PadButton): Color = when (b) {
    PadButton.LB, PadButton.RB -> Color(0xFF4A4F56)
    PadButton.LT, PadButton.RT -> Color(0xFF34383E)
    PadButton.L4, PadButton.R4 -> Color(0xFFC2C6CC)
    PadButton.L3, PadButton.R3 -> Color(0xFF33373D)
    else -> Dark
}

private class Art(val highlight: Set<PadButton>) {
    fun fill(b: PadButton): Color = if (b in highlight) HighlightColor else base(b)
    fun letter(b: PadButton): Color = if (b in highlight) Color.White else LetterColor
    fun lit(b: PadButton) = b in highlight
}

/** Escala do quadro 366 x 250 para o tamanho real e escreve textos centralizados. */
private class Labeler(val scope: DrawScope, val measurer: TextMeasurer, val s: Float) {
    fun text(t: String, cx: Float, baseline: Float, units: Float, color: Color, bold: Boolean = true) {
        val style = TextStyle(
            color = color,
            fontSize = with(scope) { (units * s).toSp() },
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
        val layout = measurer.measure(t, style)
        val x = cx * s - layout.size.width / 2f
        val y = baseline * s - layout.firstBaseline
        scope.drawText(layout, topLeft = Offset(x, y))
    }
}

/** Área de toque de um botão no desenho (centro e raio em unidades do quadro 366 x 250). */
private data class Hit(val button: PadButton, val cx: Float, val cy: Float, val r: Float)

private val FrontHits = listOf(
    Hit(PadButton.L3, 87f, 51f, 9f), Hit(PadButton.R3, 231f, 95f, 9f),
    Hit(PadButton.HOME, 183f, 21f, 12f), Hit(PadButton.SCREENSHOT, 183f, 46f, 10f), Hit(PadButton.M, 183f, 123f, 11f),
    Hit(PadButton.VIEW, 147.5f, 31f, 11f), Hit(PadButton.MENU, 219f, 31f, 11f),
    Hit(PadButton.Y, 279f, 39.5f, 12f), Hit(PadButton.A, 279f, 80.5f, 12f), Hit(PadButton.X, 256f, 60f, 12f), Hit(PadButton.B, 302f, 60f, 12f),
    Hit(PadButton.DPAD_UP, 136f, 86f, 12f), Hit(PadButton.DPAD_DOWN, 136f, 118f, 12f),
    Hit(PadButton.DPAD_LEFT, 120f, 102f, 12f), Hit(PadButton.DPAD_RIGHT, 152f, 102f, 12f),
    Hit(PadButton.LS, 87f, 51f, 27f), Hit(PadButton.RS, 231f, 95f, 27f),
    Hit(PadButton.LB, 88f, 10f, 18f), Hit(PadButton.RB, 278f, 10f, 18f),
)

private val BackHits = listOf(
    Hit(PadButton.RB, 102f, 13f, 18f), Hit(PadButton.LB, 264f, 13f, 18f),
    Hit(PadButton.RT, 102f, 50f, 26f), Hit(PadButton.LT, 264f, 50f, 26f),
    Hit(PadButton.R4, 140f, 114f, 30f), Hit(PadButton.L4, 226f, 114f, 30f),
)

/** Botão tocado no desenho: o primeiro da lista (os menores vêm antes) que contém o ponto, com folga. */
private fun hitTest(hits: List<Hit>, x: Float, y: Float): PadButton? =
    hits.firstOrNull { h -> (x - h.cx) * (x - h.cx) + (y - h.cy) * (y - h.cy) <= (h.r * 1.15f) * (h.r * 1.15f) }?.button

private fun Modifier.tapToSelect(hits: List<Hit>, onTap: ((PadButton) -> Unit)?): Modifier =
    if (onTap == null) this else this.pointerInput(onTap) {
        detectTapGestures { p ->
            val s = this.size.width / VB_W
            hitTest(hits, p.x / s, p.y / s)?.let(onTap)
        }
    }

/** Frente do controle. Com [onTap], tocar num botão do desenho o seleciona. */
@Composable
fun ControllerFront(highlight: Set<PadButton>, modifier: Modifier = Modifier, onTap: ((PadButton) -> Unit)? = null) {
    val measurer = rememberTextMeasurer()
    val art = remember(highlight) { Art(highlight) }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(VB_W / VB_H)
            .semantics { contentDescription = "Frente do controle: toque num botão para selecioná-lo" }
            .tapToSelect(FrontHits, onTap),
    ) {
        val s = size.width / VB_W
        withTransform({ scale(s, s, pivot = Offset.Zero) }) { drawFront(art) }
        val l = Labeler(this, measurer, s)
        l.text("Y", 279f, 44.5f, 13f, art.letter(PadButton.Y), bold = false)
        l.text("A", 279f, 85.5f, 13f, art.letter(PadButton.A), bold = false)
        l.text("X", 256f, 65f, 13f, art.letter(PadButton.X), bold = false)
        l.text("B", 302f, 65f, 13f, art.letter(PadButton.B), bold = false)
        l.text("M", 183f, 125.5f, 7f, if (art.lit(PadButton.M)) Color.White else Color(0xFF9CA3AF))
    }
}

/** Costas do controle. Com [onTap], tocar num botão do desenho o seleciona. */
@Composable
fun ControllerBack(highlight: Set<PadButton>, modifier: Modifier = Modifier, onTap: ((PadButton) -> Unit)? = null) {
    val measurer = rememberTextMeasurer()
    val art = remember(highlight) { Art(highlight) }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(VB_W / VB_H)
            .semantics { contentDescription = "Costas do controle: toque num botão para selecioná-lo" }
            .tapToSelect(BackHits, onTap),
    ) {
        val s = size.width / VB_W
        withTransform({ scale(s, s, pivot = Offset.Zero) }) { drawBack(art) }
        val l = Labeler(this, measurer, s)
        val light = Color(0xFFD1D5DB)
        l.text("RB", 102f, 16f, 8.5f, if (art.lit(PadButton.RB)) Color.White else light)
        l.text("LB", 264f, 16f, 8.5f, if (art.lit(PadButton.LB)) Color.White else light)
        l.text("RT", 102f, 54f, 10f, if (art.lit(PadButton.RT)) Color.White else light)
        l.text("LT", 264f, 54f, 10f, if (art.lit(PadButton.LT)) Color.White else light)
        val dark = Color(0xFF374151)
        l.text("R4", 138f, 117.5f, 9f, if (art.lit(PadButton.R4)) Color.White else dark)
        l.text("L4", 228f, 117.5f, 9f, if (art.lit(PadButton.L4)) Color.White else dark)
    }
}

private fun DrawScope.shadowEllipse() {
    drawOval(Color.Black.copy(alpha = 0.25f), topLeft = Offset(33f, 239f), size = Size(300f, 10f))
}

private fun DrawScope.drawFront(a: Art) {
    shadowEllipse()
    drawRoundRect(a.fill(PadButton.LB), Offset(64f, 2f), Size(48f, 16f), CornerRadius(7f))
    drawRoundRect(a.fill(PadButton.RB), Offset(254f, 2f), Size(48f, 16f), CornerRadius(7f))
    translate(0f, 8f) {
        drawPath(Paths.body, Brush.verticalGradient(listOf(Color(0xFFC4C8CE), Color(0xFF9DA3AB)), startY = 4f, endY = 250f))
    }
    drawPath(
        Paths.body,
        Brush.verticalGradient(
            0f to Color.White, 0.6f to Color(0xFFF3F4F6), 1f to Color(0xFFDFE2E6),
            startY = 4f, endY = 246f,
        ),
    )
    drawPath(Paths.body, Color(0xFFC3C7CD), style = Stroke(1f))
    drawPath(Paths.centerPanel, Color(0xFFF8F9FA))
    drawPath(Paths.centerPanel, Color(0xFFE3E5E8), style = Stroke(0.8f))

    stick(87f, 51f, a.fill(PadButton.LS), a.fill(PadButton.L3))
    stick(231f, 95f, a.fill(PadButton.RS), a.fill(PadButton.R3))

    // Direcional em cruz (só 4 direções, sem diagonais)
    translate(0f, 2f) {
        drawRoundRect(DarkShadow, Offset(125f, 73f), Size(22f, 29f), CornerRadius(4f))
        drawRoundRect(DarkShadow, Offset(125f, 102f), Size(22f, 29f), CornerRadius(4f))
        drawRoundRect(DarkShadow, Offset(107f, 91f), Size(29f, 22f), CornerRadius(4f))
        drawRoundRect(DarkShadow, Offset(136f, 91f), Size(29f, 22f), CornerRadius(4f))
    }
    drawRoundRect(a.fill(PadButton.DPAD_UP), Offset(125f, 73f), Size(22f, 29f), CornerRadius(4f))
    drawRoundRect(a.fill(PadButton.DPAD_DOWN), Offset(125f, 102f), Size(22f, 29f), CornerRadius(4f))
    drawRoundRect(a.fill(PadButton.DPAD_LEFT), Offset(107f, 91f), Size(29f, 22f), CornerRadius(4f))
    drawRoundRect(a.fill(PadButton.DPAD_RIGHT), Offset(136f, 91f), Size(29f, 22f), CornerRadius(4f))
    drawRect(Dark, Offset(125f, 91f), Size(22f, 22f))
    drawCircle(Color(0xFF33373D), 6f, Offset(136f, 102f))
    val arrows = Path().apply {
        moveTo(136f, 77f); lineTo(139f, 81.5f); lineTo(133f, 81.5f); close()
        moveTo(136f, 127f); lineTo(139f, 122.5f); lineTo(133f, 122.5f); close()
        moveTo(111f, 102f); lineTo(115.5f, 99f); lineTo(115.5f, 105f); close()
        moveTo(161f, 102f); lineTo(156.5f, 99f); lineTo(156.5f, 105f); close()
    }
    drawPath(arrows, Color(0xFF7A8088))

    // A B X Y
    for ((b, c) in listOf(
        PadButton.Y to Offset(279f, 39.5f), PadButton.A to Offset(279f, 80.5f),
        PadButton.X to Offset(256f, 60f), PadButton.B to Offset(302f, 60f),
    )) {
        drawCircle(DarkShadow, 12.5f, c + Offset(0f, 1.5f))
        drawCircle(a.fill(b), 12f, c)
    }

    // Botões do centro
    rotate(-28f, pivot = Offset(147.5f, 31f)) {
        drawRoundRect(a.fill(PadButton.VIEW), Offset(143.5f, 21f), Size(8f, 20f), CornerRadius(4f))
    }
    rotate(28f, pivot = Offset(219f, 31f)) {
        drawRoundRect(a.fill(PadButton.MENU), Offset(215f, 21f), Size(8f, 20f), CornerRadius(4f))
    }
    drawCircle(DarkShadow, 11.5f, Offset(183f, 22f))
    drawCircle(a.fill(PadButton.HOME), 11f, Offset(183f, 21f))
    drawCircle(Color(0xFF4A4F56), 11f, Offset(183f, 21f), style = Stroke(1f))
    val house = Path().apply {
        moveTo(178f, 22f); lineTo(183f, 17.5f); lineTo(188f, 22f)
        moveTo(179.8f, 21f); lineTo(179.8f, 25.5f); lineTo(186.2f, 25.5f); lineTo(186.2f, 21f)
    }
    drawPath(house, LetterColor, style = Stroke(1.3f))
    drawRoundRect(a.fill(PadButton.SCREENSHOT), Offset(172f, 41f), Size(22f, 10f), CornerRadius(5f))
    drawCircle(Color(0xFF9CA3AF), 2f, Offset(183f, 46f))
    drawRoundRect(a.fill(PadButton.M), Offset(172f, 118f), Size(22f, 10f), CornerRadius(5f))
}

private fun DrawScope.stick(cx: Float, cy: Float, cap: Color, top: Color) {
    drawCircle(Color(0xFFECEEF0), 27f, Offset(cx, cy + 4f))
    drawCircle(Color(0xFFDADDE1), 27f, Offset(cx, cy + 4f), style = Stroke(1f))
    drawCircle(
        Brush.verticalGradient(listOf(Color(0xFFF08A3C), Color(0xFFB9500F)), startY = cy - 12f, endY = cy + 30f),
        21f, Offset(cx, cy + 9f),
    )
    drawCircle(Color(0xFF15171A).copy(alpha = 0.5f), 21f, Offset(cx, cy + 2f))
    drawCircle(cap, 20.5f, Offset(cx, cy))
    drawCircle(
        Color(0xFF5A6068), 20.5f, Offset(cx, cy),
        style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.8f, 1.4f))),
    )
    drawCircle(top, 15f, Offset(cx, cy))
    val r = 20.5f
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.22f),
            0.55f to Color.White.copy(alpha = 0.03f),
            1f to Color.Black.copy(alpha = 0.25f),
            center = Offset(cx - r + 0.38f * 2 * r, cy - r + 0.3f * 2 * r),
            radius = 1.6f * r,
        ),
        r, Offset(cx, cy),
    )
}

private fun DrawScope.drawBack(a: Art) {
    shadowEllipse()
    translate(0f, 6f) { drawPath(Paths.body, Color(0xFFA7ADB5)) }
    drawPath(Paths.body, Brush.verticalGradient(listOf(Color(0xFFE9EBEE), Color(0xFFC6CACF)), startY = 4f, endY = 246f))
    drawPath(Paths.body, Color(0xFFAEB3BA), style = Stroke(1.2f))
    // Textura das empunhaduras
    for (grip in listOf(Paths.gripL, Paths.gripR)) {
        clipPath(grip) {
            var y = 100f
            while (y < 245f) {
                var x = 0f
                while (x < VB_W) {
                    drawCircle(Color(0xFF8F969F).copy(alpha = 0.55f), 0.7f, Offset(x + 1.2f, y + 1.2f))
                    x += 3.5f
                }
                y += 3.5f
            }
        }
    }
    drawPath(Paths.topRim, Color.White.copy(alpha = 0.9f), style = Stroke(5f))

    val trigShade = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.12f)), startY = 20f, endY = 78f,
    )
    drawPath(Paths.trigL, a.fill(PadButton.RT))
    drawPath(Paths.trigL, trigShade)
    drawPath(Paths.trigL, Color(0xFF23262B), style = Stroke(1f))
    drawPath(Paths.trigR, a.fill(PadButton.LT))
    drawPath(Paths.trigR, trigShade)
    drawPath(Paths.trigR, Color(0xFF23262B), style = Stroke(1f))
    drawPath(Paths.bumpL, a.fill(PadButton.RB))
    drawPath(Paths.bumpL, Color(0xFF2F3338), style = Stroke(1f))
    drawPath(Paths.bumpR, a.fill(PadButton.LB))
    drawPath(Paths.bumpR, Color(0xFF2F3338), style = Stroke(1f))

    drawRoundRect(Color(0xFF7C838C), Offset(134f, 62f), Size(12f, 6f), CornerRadius(3f))
    drawRoundRect(Color(0xFF7C838C), Offset(220f, 62f), Size(12f, 6f), CornerRadius(3f))
    drawRoundRect(Color(0xFF8A9099), Offset(177f, 14f), Size(12f, 4f), CornerRadius(2f))
    drawRoundRect(Color(0xFFDDE0E4).copy(alpha = 0.8f), Offset(152f, 60f), Size(62f, 30f), CornerRadius(5f))

    drawPath(Paths.paddleBar, Color(0xFFB5BAC1))
    drawPath(Paths.paddleR4, a.fill(PadButton.R4))
    drawPath(Paths.paddleR4, Color(0xFF9EA4AC), style = Stroke(1f))
    drawPath(Paths.paddleL4, a.fill(PadButton.L4))
    drawPath(Paths.paddleL4, Color(0xFF9EA4AC), style = Stroke(1f))
    for (i in 0 until 4) drawCircle(Color(0xFFB08D57), 1.8f, Offset(176f + i * 5f, 132f))
}
