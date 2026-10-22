package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.remote.ConfigBackup
import app.orchard.tv.remote.RemoteControl
import app.orchard.tv.remote.RemoteHistory
import app.orchard.tv.remote.RemoteKey
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.currentNetwork
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.weather.Weather
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * The page a phone opens after scanning the TV's QR code: choose a wallpaper and the screensaver,
 * send photos, give an app's tile a picture of one's own, and name the weather's city. Choosing
 * pictures or typing a place with a remote is slow; with a phone it is not.
 *
 * It listens on the local network only while the wallpaper sheet is open on the TV (and a few
 * minutes after, so an upload can finish), and every address carries a random token shown only in
 * the QR code, so another device on the network cannot use it by guessing.
 */
class PhoneServer private constructor(private val context: Context, private val token: String) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        val uri = session.uri
        if (!uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        return runCatching {
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                path == "/api/state" -> json(state())
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                session.method == Method.POST && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.select(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/screensaver" -> {
                    val mode = ScreensaverMode.of(form(session)["mode"])
                    onMain { Wallpapers.chooseScreensaver(mode) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/focus" -> {
                    val style = FocusStyle.of(form(session)["style"])
                    onMain { AerialSettings.chooseFocusStyle(style) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.deletePhoto(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/upload" -> upload(session)
                path.startsWith("/tile/") -> tile(java.net.URLDecoder.decode(path.removePrefix("/tile/"), "UTF-8"))
                session.method == Method.POST && path == "/api/icon" -> icon(session)
                session.method == Method.POST && path == "/api/icon/reset" -> {
                    val pkg = form(session)["app"].orEmpty()
                    if (installed(pkg)) { CustomIcons.clear(pkg); onMain { CustomIcons.changed() } }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/city" -> {
                    val name = form(session)["name"].orEmpty()
                    val found = name.isNotBlank() && runBlocking(Dispatchers.Main) { Weather.choose(name) }
                    json(state().put("found", found))
                }
                session.method == Method.POST && path == "/api/weather/auto" -> {
                    onMain { Weather.useNetworkLocation() }
                    json(state())
                }
                // The remote page in the viewer's hand: keys, typing, and a copy of the setup.
                path == "/remote" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", asset("web/remote.html"))
                path == "/api/remote/state" -> json(remoteState())
                session.method == Method.POST && path == "/api/remote/key" -> {
                    val key = RemoteKey.of(form(session)["key"])
                    if (key == null) text(Response.Status.BAD_REQUEST, "unknown key")
                    else { RemoteControl.send(key); json(remoteState()) }
                }
                session.method == Method.POST && path == "/api/remote/text" -> {
                    val text = form(session)["text"].orEmpty()
                    val written = RemoteControl.type(text)
                    // Only what actually reached a field is worth offering again.
                    if (written) RemoteHistory.add(context, text)
                    json(remoteState().put("written", written))
                }
                // Editing what is already in the field, which a phone keyboard cannot do: typing
                // replaces the whole thing, so correcting one character means correcting it here.
                session.method == Method.POST && path == "/api/remote/backspace" ->
                    json(remoteState().put("written", RemoteControl.eraseLast()))
                session.method == Method.POST && path == "/api/remote/clear" ->
                    json(remoteState().put("written", RemoteControl.clearText()))
                // The lamp on a projector answers only its maker's own bar; this opens it.
                session.method == Method.POST && path == "/api/remote/brightness" ->
                    json(remoteState().put("opened", RemoteControl.openBrightness()))
                // Likewise the inputs: this walks the firmware's panel to its HDMI list.
                session.method == Method.POST && path == "/api/remote/input" ->
                    json(remoteState().put("opened", RemoteControl.openInputSource()))
                path == "/api/remote/history" -> json(RemoteHistory.json(context))
                session.method == Method.POST && path == "/api/remote/history/delete" -> {
                    RemoteHistory.remove(context, form(session)["text"].orEmpty())
                    json(RemoteHistory.json(context))
                }
                session.method == Method.POST && path == "/api/remote/history/clear" -> {
                    RemoteHistory.clear(context)
                    json(RemoteHistory.json(context))
                }
                path == "/api/config/export" -> {
                    val body = ConfigBackup.export(context).toString(2)
                    val bytes = body.toByteArray()
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
                        addHeader("Content-Disposition", "attachment; filename=\"aerial-config.json\"")
                    }
                }
                session.method == Method.POST && path == "/api/config/import" -> {
                    val doc = runCatching { JSONObject(form(session)["json"].orEmpty()) }.getOrNull()
                    if (doc == null) json(remoteState().put("applied", -1))
                    else json(remoteState().put("applied", ConfigBackup.restore(context, doc)))
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            Log.w(TAG, "request failed", it)
            text(Response.Status.INTERNAL_ERROR, "error")
        }
    }

    private fun page(): String = asset("web/index.html").replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    private fun asset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }

    /** What the phone page shows: which keys can land right now, and whether typing can. */
    private fun remoteState(): JSONObject = JSONObject()
        .put("inFront", RemoteControl.inFront)
        .put("keyboard", RemoteControl.hasKeyboard())
        .put("shell", RemoteControl.hasShell())
        .put("device", Build.MODEL.replace("_", " "))

    private fun state(): JSONObject {
        val list = JSONArray()
        val selected = Wallpapers.selectedId
        Wallpapers.items.forEach { w ->
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("selected", selected)
            .put("latestDaily", Wallpapers.LATEST_DAILY)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("focusStyle", AerialSettings.focusStyle.key)
            .put("wallpapers", list)
            .put("apps", JSONArray().apply {
                AppCatalog.loaded.sortedBy { it.label }.forEach { a ->
                    // The stamp makes the phone fetch the tile again once its picture changes.
                    put(JSONObject().put("key", a.key).put("label", a.label).put("custom", CustomIcons.has(a.key)).put("stamp", CustomIcons.stamp(a.key)))
                }
            })
            .put("weather", JSONObject().apply {
                put("place", Weather.place ?: "")
                put("chosen", Weather.chosen)
                Weather.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()); put("day", it.day) }
            })
    }

    private fun installed(pkg: String) = CustomIcons.isValidKey(pkg) && AppCatalog.loaded.any { it.key == pkg }

    private fun tile(pkg: String): Response {
        val app = AppCatalog.loaded.firstOrNull { it.key == pkg } ?: return text(Response.Status.NOT_FOUND, "")
        val bmp = TileArtFactory.flatten(app.art)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun icon(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val pkg = session.parameters["app"]?.firstOrNull().orEmpty()
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app")
        val f = files.values.map(::File).firstOrNull { it.exists() && it.length() > 0 }
            ?: return text(Response.Status.BAD_REQUEST, "no picture")
        val saved = f.inputStream().use { CustomIcons.save(pkg, it, context.cacheDir) }
        if (saved) onMain { CustomIcons.changed() }
        return json(state().put("saved", saved))
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(java.net.URLDecoder.decode(id, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        var added = 0
        // NanoHTTPD stores each part in a temp file; several photos arrive as photo, photo1, photo2...
        files.values.forEach { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                val id = f.inputStream().use { input -> runBlocking(Dispatchers.Main) { Wallpapers.addPhoto(input) } }
                if (id != null) added++
            }
        }
        return json(state().put("added", added))
    }

    // NanoHTTPD writes the whole body to the cache before we see it; keep a stray or hostile
    // upload from filling the TV's storage (#57).
    private fun tooLarge(session: IHTTPSession): Boolean =
        (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        const val PORT = 8790
        private const val TAG = "AerialPhone"
        /** Enough for a batch of phone photos at full size. */
        private const val MAX_UPLOAD = 200L * 1024 * 1024
        /** The standard's quiet zone, in modules, around a QR code. */
        private const val QUIET_MODULES = 4

        /** Who has the page open; the server stays while any of them does. */
        const val SHEET = "sheet"
        const val REMOTE = "remote"

        private const val PREFS = "remote"
        private const val KEY_TOKEN = "token"
        private const val KEY_ALWAYS = "always_on"

        @Volatile
        private var running: PhoneServer? = null

        /**
         * Whether the remote page outlives the screen it was opened from.
         *
         * On by default, because the page is meant to sit in the phone's browser bookmarks: an
         * address that changes, or one that dies when the QR screen closes, cannot be bookmarked.
         * The viewer can turn it off, and then the server follows the screen again.
         */
        @Volatile
        var alwaysOn: Boolean = true
            private set

        private val holders = mutableSetOf<String>()

        /**
         * Set once the remote page has been reached. The wallpaper sheet shares this server, so its
         * exit must not take the phone's bookmarked page down with it.
         */
        @Volatile
        private var remoteWanted = false

        private val main = Handler(Looper.getMainLooper())
        private val stopper = Runnable { shutdown() }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The address never changes, so a bookmarked link keeps working across restarts. */
        private fun token(context: Context): String {
            val store = prefs(context)
            store.getString(KEY_TOKEN, null)?.takeIf { it.length == 8 }?.let { return it }
            return newToken().also { store.edit().putString(KEY_TOKEN, it).apply() }
        }

        fun keepsAlwaysOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ALWAYS, true)

        fun setAlwaysOn(context: Context, on: Boolean) {
            alwaysOn = on
            if (!on) remoteWanted = false
            prefs(context).edit().putBoolean(KEY_ALWAYS, on).apply()
            if (!on) shutdownIfIdle()
        }

        /** Starts the server if needed and returns the address to put in the QR code, or null offline. */
        @Synchronized
        fun start(context: Context, page: String = "", holder: String = SHEET): String? {
            val ip = lanAddress(context) ?: return null
            alwaysOn = keepsAlwaysOn(context)
            if (holder == REMOTE) remoteWanted = true
            holders += holder
            val server = running ?: PhoneServer(context.applicationContext, token(context)).also {
                runCatching { it.start(SOCKET_READ_TIMEOUT, true) }.onFailure { return null }
                running = it
            }
            val url = "http://$ip:$PORT/${server.token}/$page"
            Log.i(TAG, "page for $holder at $url")
            return url
        }

        /**
         * Brings the page back by itself, so a bookmarked address works again after Aerial was
         * stopped and revived without anyone walking over to the TV to show a QR code. The shell
         * comes up with it: a page opened straight from the bookmarks never passes the QR screen,
         * and without the shell its arrow keys could only reach Aerial's own window.
         */
        @Synchronized
        fun boot(context: Context): String? {
            if (!keepsAlwaysOn(context)) return null
            RemoteControl.warmUp()
            return start(context, "remote", REMOTE)
        }

        /**
         * Gives the server up on behalf of [holder]; it only stops once nobody holds it. [delayMs]
         * keeps it up a while longer: the remote page lives in the phone's browser, and once it is
         * loaded it has to work while the viewer moves around the TV, long after the QR is gone.
         * The wallpaper sheet shares the same server, so neither may close it under the other.
         */
        @Synchronized
        fun stop(holder: String = SHEET, delayMs: Long = 0L) {
            holders -= holder
            if (holders.isNotEmpty()) return
            // Left on, the page stays reachable for as long as this process lives: the phone holds
            // it open in a tab or a bookmark, and it must not go dead because some other screen closed.
            if (alwaysOn && remoteWanted) return
            main.removeCallbacks(stopper)
            if (delayMs > 0) main.postDelayed(stopper, delayMs) else stopper.run()
        }

        @Synchronized
        private fun shutdownIfIdle() {
            if (holders.isEmpty() && !alwaysOn) shutdown()
        }

        @Synchronized
        private fun shutdown() {
            running?.stop()
            running = null
        }

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService<ConnectivityManager>() ?: return null
            val props = cm.getLinkProperties(cm.currentNetwork() ?: return null) ?: return null
            return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        /**
         * A QR code, dark modules on white, with the four-module quiet zone the standard asks for.
         * Without that margin a code is drawn but will not scan: the camera finds the three corner
         * squares by the contrast between them and the empty space around them.
         *
         * [minPixels] sizes the bitmap to the space it is drawn in. ZXing's minimal matrix is a few
         * hundred pixels across; stretched to fill a TV-sized box, every module gets a soft edge and
         * phones struggle, so the scale is picked to match the screen instead of upscaling.
         */
        fun qr(text: String, minPixels: Int = 0): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to QUIET_MODULES, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val scale = if (minPixels > 0) maxOf(1, (minPixels + n - 1) / n) else 8
            val bmp = createBitmap(n * scale, n * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.BLACK)
                else for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.WHITE)
            }
            return bmp
        }
    }
}
