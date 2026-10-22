package app.orchard.tv.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.orchard.tv.remote.RemoteControl
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.PhoneServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The phone page's address, on the TV: scan it and the phone becomes a remote — a direction pad,
 * Home and Back, and a text field for whatever needs typing. The same page carries the config
 * backup, because a phone is the only keyboard in the room.
 *
 * The address carries a random token, so nothing else on the network can reach the TV by guessing;
 * the server outlives this screen by [KEEP_MS] so the loaded page keeps working.
 */
private const val KEEP_MS = 6 * 60 * 60 * 1000L

/** The white card the code sits on, and the white around it: 14 dp is more than the quiet zone. */
private val QR_CARD = 272.dp
private val QR_PAD = 14.dp
private val QR_INNER = QR_CARD - QR_PAD * 2

@Composable
fun RemotePad(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var url by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Open the shell first: it decides whether the phone can drive other apps, and the page says so.
        withContext(Dispatchers.IO) { RemoteControl.warmUp() }
        val address = withContext(Dispatchers.IO) { PhoneServer.start(context, "remote", PhoneServer.REMOTE) }
        url = address
        // Sized for the pixels it is drawn in: a small code stretched to fill the card has soft
        // module edges, and that alone is enough to keep a phone camera from reading it.
        val px = with(density) { QR_INNER.toPx() }.roundToInt()
        qr = address?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it, px).asImageBitmap() } }
        started = true
    }
    DisposableEffect(Unit) {
        // The page stays in the phone's browser after this screen closes; the server has to outlive
        // it, or the phone would go dead the moment the viewer goes back to the grid.
        onDispose { PhoneServer.stop(PhoneServer.REMOTE, KEEP_MS) }
    }

    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            BasicText("手机遥控", style = Type.LargeTitle.copy(color = Color.White))
            Spacer(Modifier.height(6.dp))
            BasicText(
                "手机连同一个 Wi-Fi，扫码后就是一个遥控器",
                style = Type.Callout.copy(color = Color.White.copy(alpha = 0.66f)),
            )
            Spacer(Modifier.height(26.dp))

            val image = qr
            when {
                // White card on purpose: a code needs light space around it to be found at all, and
                // the wallpaper behind is neither light nor flat.
                image != null -> Box(
                    Modifier.size(QR_CARD).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(QR_PAD),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        image,
                        contentDescription = url,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                started -> Box(
                    Modifier.size(QR_CARD).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        "没连上局域网\n先确认电视已联网",
                        style = Type.Callout.copy(color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center),
                    )
                }
                else -> Spacer(Modifier.size(QR_CARD))
            }

            Spacer(Modifier.height(20.dp))
            url?.let {
                BasicText(it, style = Type.Caption.copy(color = Color.White.copy(alpha = 0.5f)))
                Spacer(Modifier.height(10.dp))
            }
            BasicText(
                "这个地址固定不变：扫一次，把页面存进手机浏览器的收藏夹，以后开机直接打开就能遥控，不用再扫码。" +
                    "按返回键离开这一页，手机页面照样能用；只要无线 ADB 开着，方向键 / OK 在别的应用里也有效。",
                style = Type.Caption.copy(color = Color.White.copy(alpha = 0.55f), textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = 120.dp),
            )
        }
    }
}
