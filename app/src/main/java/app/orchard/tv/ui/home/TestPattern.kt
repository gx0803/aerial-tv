package app.orchard.tv.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.min

/**
 * Projector test pictures: a grid for keystone and overscan, flat fields for dead pixels and
 * colour, a grey ramp for banding, bars for saturation, and a line pair set for focus. A
 * projector has to be aimed and focused by eye, and nothing else on the TV shows what to look at.
 *
 * Keys are the remote's: Left/Right (or Up/Down) step, Back leaves; see MainActivity.
 */
private val NAMES = listOf(
    "网格 · 梯形与过扫描",
    "纯白 · 亮度均匀性",
    "纯红 · 坏点",
    "纯绿 · 坏点",
    "纯蓝 · 坏点",
    "纯黑 · 漏光",
    "灰阶 · 渐变断层",
    "彩条 · 色彩饱和",
    "线对 · 对焦",
)

private const val PATTERN_COUNT = 9

fun patternCount() = PATTERN_COUNT

fun patternName(index: Int) = NAMES[index.mod(PATTERN_COUNT)]

@Composable
fun TestPattern(index: Int, modifier: Modifier = Modifier) {
    val which = index.mod(PATTERN_COUNT)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            when (which) {
                0 -> {
                    // A 24 × 14 grid plus a frame inset by 5%: if the frame is not fully visible,
                    // the source is overscanning, and if the outer lines bow, keystone is off.
                    val inset = min(w, h) * 0.05f
                    val cw = (w - inset * 2) / 24f
                    val ch = (h - inset * 2) / 14f
                    for (i in 0..24) {
                        val x = inset + cw * i
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(x, inset), Offset(x, h - inset), 1f)
                    }
                    for (j in 0..14) {
                        val y = inset + ch * j
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(inset, y), Offset(w - inset, y), 1f)
                    }
                    drawRect(Color.White, Offset(inset, inset), Size(w - inset * 2, h - inset * 2), style = Stroke(2f))
                    drawLine(Color(0xFFFF3B30), Offset(w / 2 - 40, h / 2), Offset(w / 2 + 40, h / 2), 2f)
                    drawLine(Color(0xFFFF3B30), Offset(w / 2, h / 2 - 40), Offset(w / 2, h / 2 + 40), 2f)
                    // Corner ticks: the four corners of the panel itself.
                    val t = 26f
                    listOf(
                        Offset(0f, 0f), Offset(w - t, 0f), Offset(0f, h - t), Offset(w - t, h - t),
                    ).forEach { o ->
                        drawRect(
                            Color(0xFF34C759),
                            Offset(o.x, o.y),
                            Size(t, 3f),
                        )
                    }
                }
                1 -> drawRect(Color.White)
                2 -> drawRect(Color(0xFFFF0000))
                3 -> drawRect(Color(0xFF00FF00))
                4 -> drawRect(Color(0xFF0000FF))
                5 -> drawRect(Color.Black)
                6 -> {
                    val steps = 16
                    val sw = w / steps
                    for (i in 0 until steps) {
                        val v = i / (steps - 1f)
                        drawRect(Color(v, v, v), Offset(sw * i, 0f), Size(sw + 1f, h))
                    }
                }
                7 -> {
                    val bars = listOf(
                        Color.White, Color(0xFFFFFF00), Color(0xFF00FFFF), Color(0xFF00FF00),
                        Color(0xFFFF00FF), Color(0xFFFF0000), Color(0xFF0000FF), Color.Black,
                    )
                    val bw = w / bars.size
                    bars.forEachIndexed { i, c -> drawRect(c, Offset(bw * i, 0f), Size(bw + 1f, h * 0.75f)) }
                    // A low-contrast strip under the bars, where crushed blacks show up.
                    for (i in 0 until bars.size) {
                        val v = if (i % 2 == 0) 0.08f else 0.02f
                        drawRect(
                            Color(v, v, v),
                            Offset(bw * i, h * 0.75f),
                            Size(bw + 1f, h * 0.25f),
                        )
                    }
                }
                8 -> {
                    // Eight strips of falling line pitch: the finest strip that still reads as
                    // separate lines is where focus peaks.
                    val cx = w / 2
                    val cy = h / 2
                    val pitches = listOf(5f, 4f, 3.2f, 2.5f, 2f, 1.6f, 1.2f, 0.9f)
                    val strip = h * 0.05f
                    pitches.forEachIndexed { i, p ->
                        val top = cy - strip * 4f + strip * i
                        var x = w * 0.12f
                        while (x < w * 0.88f) {
                            drawLine(Color.White, Offset(x, top), Offset(x, top + strip * 0.78f), p)
                            x += p * 2.4f
                        }
                    }
                    drawLine(Color(0xFFFF3B30), Offset(cx - 34f, cy - strip * 4.6f), Offset(cx - 34f, cy + strip * 4.6f), 2f)
                    drawLine(Color(0xFFFF3B30), Offset(cx + 34f, cy - strip * 4.6f), Offset(cx + 34f, cy + strip * 4.6f), 2f)
                }
            }
        }

        // The name, and how to leave: small, top left, over any pattern.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(18.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            BasicText(
                "${patternName(which)}   ·   ←→ 切换   ·   返回键退出",
                style = Type.Caption.copy(color = Color.White.copy(alpha = 0.9f)),
            )
        }
    }
}
