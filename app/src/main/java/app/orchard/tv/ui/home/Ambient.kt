package app.orchard.tv.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.theme.Type
import java.time.LocalDateTime
import java.util.Random
import kotlin.math.sin

/**
 * Ambient scenes for a projector in a dark room: something moving on the wall that is not a menu.
 * Drawn, not filmed, so there is nothing to download and no video decoder running all night.
 *
 * Left/Right changes scene, Back leaves; see MainActivity.
 */
val AMBIENT_SCENES = listOf("极光", "星野", "炉火")

private const val SCENE_COUNT = 3

fun ambientSceneCount() = SCENE_COUNT

fun ambientSceneName(index: Int) = AMBIENT_SCENES[index.mod(SCENE_COUNT)]

@Composable
fun Ambient(scene: Int, now: LocalDateTime, modifier: Modifier = Modifier) {
    val which = scene.mod(SCENE_COUNT)
    // Seconds since this page opened, read in the draw phase so the frame redraws without
    // recomposing anything.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            withFrameNanos { }
            time.floatValue = (System.nanoTime() - start) / 1_000_000_000f
        }
    }
    // Star positions and ember phases, made once.
    val stars = remember {
        val r = Random(7)
        List(170) {
            Triple(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat() * 2.2f)
        }
    }
    val embers = remember {
        val r = Random(11)
        List(34) { Triple(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.6f) }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time.floatValue
            val w = size.width
            val h = size.height
            when (which) {
                0 -> {
                    // Aurora: soft curtains. Each one is a band of thin strips whose alpha follows
                    // a bell, so the edges fall away instead of ending on a hard colour bar.
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF07131F), Color(0xFF01050A))))
                    val curtains = listOf(
                        Color(0xFF2BE6A6) to 0f,
                        Color(0xFF3AB0FF) to 1.4f,
                        Color(0xFF9B6BFF) to 2.6f,
                        Color(0xFF25C7B0) to 3.7f,
                        Color(0xFF7BE3FF) to 4.6f,
                    )
                    curtains.forEachIndexed { i, (color, phase) ->
                        val bandW = w * 0.17f
                        val baseX = w * (0.08f + 0.19f * i) + sin(t * 0.22f + phase) * w * 0.05f
                        val breathe = 0.11f + 0.08f * (0.5f + 0.5f * sin(t * 0.35f + phase))
                        rotate(degrees = -6f + 3f * sin(t * 0.18f + phase), pivot = Offset(w / 2f, h / 2f)) {
                            val strips = 40
                            for (j in 0 until strips) {
                                val u = j / (strips - 1f)
                                val bell = sin(u * 3.14159265f)
                                val a = breathe * bell * bell
                                if (a < 0.004f) continue
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.18f to color.copy(alpha = a * 0.45f),
                                        0.58f to color.copy(alpha = a),
                                        1f to Color.Transparent,
                                    ),
                                    topLeft = Offset(baseX + (u - 0.5f) * bandW, -h * 0.05f),
                                    size = Size(bandW / strips * 1.7f, h * 0.95f),
                                )
                            }
                        }
                    }
                    // A faint horizon, so the wall is not pure black under the curtains.
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF123244).copy(alpha = 0.22f))),
                        topLeft = Offset(0f, h * 0.72f),
                        size = Size(w, h * 0.28f),
                    )
                }
                1 -> {
                    // Starfield: slow drift, each star twinkling on its own clock.
                    drawRect(
                        Brush.radialGradient(
                            0f to Color(0xFF0A1428),
                            1f to Color(0xFF01030A),
                            center = Offset(w * 0.5f, h * 0.35f),
                            radius = w * 0.8f,
                        )
                    )
                    stars.forEach { (sx, sy, speed) ->
                        val drift = (t * 0.004f * speed) % 1f
                        val x = ((sx + drift) % 1f) * w
                        val y = sy * h
                        val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * speed + sx * 40f))
                        val r = 1f + 1.8f * twinkle
                        drawCircle(Color.White.copy(alpha = twinkle * 0.9f), radius = r, center = Offset(x, y))
                    }
                }
                else -> {
                    // Fireplace: a low, flickering warm glow with embers drifting up.
                    drawRect(Color(0xFF0A0503))
                    val flicker = 0.8f + 0.2f * (0.5f + 0.5f * sin(t * 6.7f) * sin(t * 2.9f))
                    drawRect(
                        Brush.radialGradient(
                            0f to Color(0xFFFFB05A).copy(alpha = 0.78f * flicker),
                            0.35f to Color(0xFFE2551A).copy(alpha = 0.46f * flicker),
                            0.7f to Color(0xFF8C2A08).copy(alpha = 0.22f * flicker),
                            1f to Color.Transparent,
                            center = Offset(w * 0.5f, h * 1.05f),
                            radius = h * 0.95f * flicker,
                        )
                    )
                    // A smaller tongue of flame, flickering out of phase with the big glow.
                    drawRect(
                        Brush.radialGradient(
                            0f to Color(0xFFFFD08A).copy(alpha = 0.30f),
                            1f to Color.Transparent,
                            center = Offset(w * (0.5f + 0.02f * sin(t * 4.3f)), h * 1.0f),
                            radius = h * 0.38f,
                        )
                    )
                    embers.forEach { (ex, ey, speed) ->
                        val rise = (t * 0.045f * speed + ey) % 1f
                        val y = h - rise * h * 0.78f
                        val x = w * (0.5f + (ex - 0.5f) * 0.5f) + sin(t * 1.1f + ex * 12f) * w * 0.02f
                        val fade = (1f - rise) * (1f - rise) * 0.9f
                        drawCircle(
                            Color(0xFFFFC06A).copy(alpha = fade),
                            radius = 1.6f + 3f * (1f - rise),
                            center = Offset(x, y),
                        )
                    }
                }
            }
        }

        BasicText(
            ambientSceneName(which),
            style = Type.Caption.copy(color = Color.White.copy(alpha = 0.35f)),
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
        )
    }
}
