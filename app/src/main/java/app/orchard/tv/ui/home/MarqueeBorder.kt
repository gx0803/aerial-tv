package app.orchard.tv.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.MarqueeTheme
import app.orchard.tv.ui.theme.LocalReducedMotion

/** How many colour stops make one lap of the rainbow. Smooth enough at TV size, cheap to build. */
private const val MARQUEE_STEPS = 48

/** One lap of the flowing light, in ms. */
private const val MARQUEE_PERIOD_MS = 8000

/** Paint one point of the chosen theme's hue window, [f] walking it 0..1. */
private fun themeColor(theme: MarqueeTheme, f: Float): Color {
    val hue = (theme.hueFrom + (theme.hueTo - theme.hueFrom) * f).mod(360f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, theme.saturation, 1f)))
}

/**
 * A flowing rainbow light around the four edges of Home (the "光影/跑马灯" border).
 *
 * Drawn as a rotating sweep gradient on four nested strokes: a wide faint one for the glow and a
 * thin bright one for the light itself, on a plain rectangle — the picture a projector throws is a
 * rectangle, so a rounded frame leaves four visibly empty corners. The phase is read in the draw
 * phase, so the animation redraws without recomposing Home. With reduced motion the light holds
 * still.
 *
 * Sits above the grid but under the screensaver, which is a full-screen film.
 */
@Composable
fun MarqueeBorder(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "marquee")
    val phaseState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(MARQUEE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marquee-phase",
    )
    val density = LocalDensity.current

    Canvas(modifier) {
        val theme = AerialSettings.marqueeTheme
        if (theme == MarqueeTheme.Off) return@Canvas
        // As close to the edge as the glow allows: the widest stroke reaches half its width
        // outwards, so this is how much of it the panel clips away.
        val inset = with(density) { 10.dp.toPx() }
        val boardW = size.width - inset * 2
        val boardH = size.height - inset * 2
        if (boardW <= 0f || boardH <= 0f) return@Canvas

        val phase = if (reduced) 0f else phaseState.value
        val center = Offset(size.width / 2f, size.height / 2f)

        // The whole lap of the theme's colours, shifted by the phase so the light runs around.
        val stops = Array(MARQUEE_STEPS + 1) { i ->
            val f = i / MARQUEE_STEPS.toFloat()
            f to themeColor(theme, (f - phase).mod(1f))
        }
        val brush = Brush.sweepGradient(*stops, center = center)
        val topLeft = Offset(inset, inset)
        val board = Size(boardW, boardH)
        // Night pulls the whole frame down a stop with the rest of Home.
        val night = if (AerialSettings.nightMode) 0.55f else 1f

        // Wide and faint outwards, thin and bright inwards: a neon tube with a bloom.
        val layers = listOf(
            with(density) { 28.dp.toPx() } to 0.08f,
            with(density) { 18.dp.toPx() } to 0.16f,
            with(density) { 10.dp.toPx() } to 0.34f,
            with(density) { 4.dp.toPx() } to 1f,
        )
        layers.forEach { (width, alpha) ->
            drawRect(
                brush = brush,
                topLeft = topLeft,
                size = board,
                alpha = alpha * night,
                style = Stroke(width = width),
            )
        }
    }
}
