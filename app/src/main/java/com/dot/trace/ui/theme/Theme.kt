package com.dot.trace.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Dot 앱과 같은 미니멀 다크 + 라임 포인트 컬러 */
object Dot {
    val Ink = Color(0xFF0B0B0C)          // 배경
    val Surface = Color(0xFF151517)      // 카드
    val Surface2 = Color(0xFF1E1E21)     // 칩, 눌림
    val Line = Color(0xFF26262A)         // 구분선
    val Text = Color(0xFFF4F4F2)
    val Muted = Color(0xFF8B8B90)
    val Faint = Color(0xFF55555A)
    val Lime = Color(0xFFD4FF3A)         // 포인트, 경로
    val OnLime = Color(0xFF0B0B0C)
    val Coral = Color(0xFFFF6B5B)        // 정지, 삭제, 경고
    val Amber = Color(0xFFFFC14D)        // GPS 찾는 중
}

private val scheme = darkColorScheme(
    primary = Dot.Lime,
    onPrimary = Dot.OnLime,
    secondary = Dot.Lime,
    onSecondary = Dot.OnLime,
    background = Dot.Ink,
    onBackground = Dot.Text,
    surface = Dot.Surface,
    onSurface = Dot.Text,
    surfaceVariant = Dot.Surface2,
    onSurfaceVariant = Dot.Muted,
    surfaceContainer = Dot.Surface,
    surfaceContainerHigh = Dot.Surface2,
    surfaceContainerHighest = Dot.Surface2,
    surfaceContainerLow = Dot.Surface,
    outline = Dot.Line,
    outlineVariant = Dot.Line,
    error = Dot.Coral,
)

private val base = FontFamily.Default

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.04).em),
    headlineSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.02).em),
    titleMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-0.01).em),
    bodyMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.08.em),
    labelSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun DotTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
