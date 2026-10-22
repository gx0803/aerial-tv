package app.orchard.tv.remote

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** A key the phone's page can send, as the key the remote would have pressed. */
enum class RemoteKey(val code: Int) {
    Up(KeyEvent.KEYCODE_DPAD_UP),
    Down(KeyEvent.KEYCODE_DPAD_DOWN),
    Left(KeyEvent.KEYCODE_DPAD_LEFT),
    Right(KeyEvent.KEYCODE_DPAD_RIGHT),
    Ok(KeyEvent.KEYCODE_DPAD_CENTER),
    Back(KeyEvent.KEYCODE_BACK),
    Home(KeyEvent.KEYCODE_HOME),
    Menu(KeyEvent.KEYCODE_MENU),
    VolumeUp(KeyEvent.KEYCODE_VOLUME_UP),
    VolumeDown(KeyEvent.KEYCODE_VOLUME_DOWN),
    PlayPause(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);

    companion object {
        fun of(name: String?): RemoteKey? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * The page in the viewer's hand: presses arrive on an HTTP thread and are handed to whatever can
 * act on them.
 *
 * While Aerial is the app in front, a press becomes a real key event through its own
 * `dispatchKeyEvent`, so the grid, Control Center and the tools behave exactly as they do under the
 * remote. Anywhere else only Home and Back can be delivered, through the accessibility service:
 * Android gives no app a way to press Left or Right inside another app.
 */
object RemoteControl {
    private val main = Handler(Looper.getMainLooper())

    /** Set by MainActivity between onResume and onPause; null while another app is in front. */
    @Volatile
    private var presser: ((RemoteKey) -> Unit)? = null

    /** True while Aerial answers the phone itself, which is what the page shows. */
    @Volatile
    var inFront: Boolean = false
        private set

    fun attach(press: (RemoteKey) -> Unit) {
        presser = press
        inFront = true
    }

    fun detach() {
        presser = null
        inFront = false
    }

    /**
     * A press from the phone.
     *
     * The TV's own adb shell injects into whatever window holds focus, so it lands in Aerial, in
     * another app, and in a system panel floating over either — the last of those never pauses
     * Aerial, so "is Aerial on screen" is not a safe way to pick a path. Without the shell a press
     * falls back to Aerial's own dispatch when it is in front, and to the accessibility service,
     * which can still do Home and Back anywhere.
     */
    fun send(key: RemoteKey) {
        if (AdbBridge.connected) {
            AdbBridge.press(key.code) { fallback(key) }
            return
        }
        fallback(key)
    }

    private fun fallback(key: RemoteKey) {
        val press = presser
        if (press != null) {
            main.post { press(key) }
            return
        }
        AdbBridge.press(key.code) { main.post { RemoteService.instance?.global(key) } }
    }

    /** Opens the shell before the first press, so the phone page can say whether it is there. */
    fun warmUp() = AdbBridge.warmUp()

    /**
     * Text typed on the phone, into the field that has focus in whatever app is in front. The
     * accessibility service writes the whole string, Chinese included, into any standard field;
     * where it cannot, the shell types ASCII only.
     */
    fun type(text: String): Boolean {
        val service = RemoteService.instance
        if (service != null && runBlocking(Dispatchers.Main) { service.write(text) }) return true
        return AdbBridge.typeAscii(text)
    }

    /** Empties the field that has focus; false when nothing in front takes text. */
    fun clearText(): Boolean = type("")

    /**
     * Takes back one character. The accessibility service writes back the whole text minus its
     * last character, which is the only way that works in fields drawing their own cursor; a real
     * Backspace key through the shell is the fallback when there is no such service.
     */
    fun eraseLast(): Boolean {
        val service = RemoteService.instance
        if (service != null && runBlocking(Dispatchers.Main) { service.eraseLast() }) return true
        if (!AdbBridge.connected) return false
        AdbBridge.press(KeyEvent.KEYCODE_DEL)
        return true
    }

    /**
     * Opens the firmware's own brightness bar, on the remote's side-key route.
     *
     * On a projector like the Dangbei D7XPG there is no Android channel to the light engine at all:
     * /sys/class/backlight is empty, the 1..10 slider writes nothing to Settings or to a system
     * property, and `screen_brightness` moves what Android draws without moving the lamp. The
     * number that does reach the lamp belongs to the vendor's own HAL service
     * (xgimi.hardware.gmpf.IDisplayManager), which no app is given a way to call.
     *
     * What the firmware does answer to is its own panel. The remote's side key opens it — Linux key
     * 64, which Vendor_1d5a_Product_c081.kl maps to KEYCODE_MOVE_HOME (122) and *not* to
     * KEYCODE_F6, so going by Generic.kl here gets nothing. Brightness is the third tile and OK
     * opens its slider, which then answers Left and Right like any other key.
     *
     * The bar stays up until someone presses Back, so the page hands the keys back to the viewer
     * straight away and does not send one itself: a Back on Aerial's home screen starts the
     * screensaver, and in another app it would navigate away.
     */
    fun openBrightness(): Boolean {
        if (!AdbBridge.connected) return false
        return AdbBridge.run(
            "input keyevent ${KeyEvent.KEYCODE_MOVE_HOME}" +
                "; sleep 1" +
                "; input keyevent ${KeyEvent.KEYCODE_DPAD_RIGHT}" +
                "; sleep 0.4" +
                "; input keyevent ${KeyEvent.KEYCODE_DPAD_RIGHT}" +
                "; sleep 0.4" +
                "; input keyevent ${KeyEvent.KEYCODE_DPAD_CENTER}",
        )
    }

    /**
     * Walks the firmware's panel to its HDMI list and picks one, so the phone page's two buttons
     * do the whole trip.
     *
     * Same reasoning as [openBrightness]: the inputs are listed by the TV input framework
     * (com.xgimi.tvinput registers HDMI 1–3) but the passthrough Intent no app on this projector
     * answers, and the session that actually shows an input belongs to com.xgimi.xhplayer, a system
     * app. The panel is the one route that works.
     *
     * Side key (122) opens it, Down off the top row (which lands on 图像模式), Right to 输入源, and
     * OK — without that last press nothing appears. The list then defaults to HDMI 1; HDMI 2 is
     * one Down further. Each press is its own step with a beat between, since the panel is slow to
     * react to a key sent while it is still opening.
     */
    private fun walkToHdmi(): String =
        "input keyevent ${KeyEvent.KEYCODE_MOVE_HOME}" +
            "; sleep 1" +
            "; input keyevent ${KeyEvent.KEYCODE_DPAD_DOWN}" +
            "; sleep 0.4" +
            "; input keyevent ${KeyEvent.KEYCODE_DPAD_RIGHT}" +
            "; sleep 0.4" +
            "; input keyevent ${KeyEvent.KEYCODE_DPAD_CENTER}"

    fun chooseHdmi1(): Boolean {
        if (!AdbBridge.connected) return false
        return AdbBridge.run(
            walkToHdmi() +
                "; sleep 0.6" +
                "; input keyevent ${KeyEvent.KEYCODE_DPAD_CENTER}",
        )
    }

    fun chooseHdmi2(): Boolean {
        if (!AdbBridge.connected) return false
        return AdbBridge.run(
            walkToHdmi() +
                "; sleep 0.6" +
                "; input keyevent ${KeyEvent.KEYCODE_DPAD_DOWN}" +
                "; sleep 0.4" +
                "; input keyevent ${KeyEvent.KEYCODE_DPAD_CENTER}",
        )
    }

    fun hasKeyboard(): Boolean = RemoteService.instance != null

    /** True when keys can reach another app at all: through the shell, or Home/Back through access. */
    fun hasShell(): Boolean = AdbBridge.connected
}
