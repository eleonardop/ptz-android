package com.ptzstudio.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cores do PTZ Studio (tema escuro grafite com azul). */
object Palette {
    val Ground = Color(0xFF0E1116)
    val Bar = Color(0xFF12161C)
    val Card = Color(0xFF171B22)
    val Raised = Color(0xFF1A1F27)
    val Chip = Color(0xFF1F242D)
    val Line = Color(0xFF232A34)
    val Border = Color(0xFF2A313C)
    val Text = Color(0xFFE6EAF0)
    val Soft = Color(0xFFC9D1DC)
    val Muted = Color(0xFF9AA4B2)
    val Accent = Color(0xFF2563EB)
    val AccentBright = Color(0xFF3B82F6)
    val AccentDim = Color(0xFF1B2A44)
    val AccentText = Color(0xFF93C5FD)
    val Green = Color(0xFF22C55E)
    val GreenDim = Color(0xFF13261A)
    val GreenText = Color(0xFF86EFAC)
    val Red = Color(0xFFEF4444)
    val RedDim = Color(0xFF2A1517)
    val RedText = Color(0xFFFCA5A5)
    val Amber = Color(0xFFFCD34D)
    val AmberDim = Color(0xFF2A2113)
}

@Composable
fun PtzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color.White,
            secondary = Palette.AccentBright,
            background = Palette.Ground,
            onBackground = Palette.Text,
            surface = Palette.Card,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Raised,
            onSurfaceVariant = Palette.Muted,
            outline = Palette.Border,
            surfaceContainer = Palette.Card,
            surfaceContainerHigh = Palette.Raised,
            surfaceContainerHighest = Palette.Chip,
        ),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = Palette.Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Card)
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun StatusChip(text: String, dot: Color, background: Color, textColor: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, color = textColor, fontSize = 12.sp, maxLines = 1)
    }
}

/**
 * Botão que age enquanto está apertado (segurar para mover). Solta = para.
 * Se o botão sumir da tela ainda apertado, também solta.
 */
@Composable
fun HoldButton(
    label: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 44.dp,
    content: @Composable () -> Unit = { Text(label, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    DisposableEffect(Unit) {
        onDispose { if (pressed) up() }
    }
    Box(
        modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) Palette.AccentDim else Palette.Raised)
            .border(1.dp, if (pressed) Palette.Accent else Palette.Border, RoundedCornerShape(10.dp))
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    down()
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                        up()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Botão comum do app (toque simples, com toque longo opcional). */
@Composable
fun PadTile(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    muted: Boolean = false,
    height: Dp = 44.dp,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    Box(
        modifier
            .defaultMinSize(minHeight = height)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.AccentDim else Palette.Raised)
            .border(1.dp, if (selected) Palette.Accent else Palette.Border, RoundedCornerShape(10.dp))
            .semantics {
                role = Role.Button
                contentDescription = text
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { click() },
                    onLongPress = { long?.invoke() },
                )
            }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (muted) Palette.Muted else Palette.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun FullWidthSpacer(height: Dp) = Spacer(Modifier.fillMaxWidth().padding(top = height))
