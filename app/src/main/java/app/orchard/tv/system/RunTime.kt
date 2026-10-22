package app.orchard.tv.system

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * How long the projector has been running with Aerial in front. A projector's light source ages in
 * hours, but no Android API reports lamp or laser hours, so Aerial keeps its own count: every
 * stretch in front is added up, and the total is kept across updates and reboots.
 *
 * Not a service: Home calls [onFront] and [onHidden] from its own lifecycle.
 */
object RunTime {
    private const val PREFS = "runtime"
    private const val KEY_TOTAL = "total_ms"
    private const val KEY_SESSIONS = "sessions"

    /** Total time in front, all sessions. */
    var totalMs by mutableStateOf(0L); private set

    /** When the current stretch in front began, 0 when Home is not in front. */
    private var frontSince = 0L
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        totalMs = prefs!!.getLong(KEY_TOTAL, 0L)
    }

    fun onFront() {
        if (frontSince == 0L) frontSince = SystemClock.elapsedRealtime()
    }

    /** Adds the stretch that just ended to the total. */
    fun onHidden() {
        if (frontSince == 0L) return
        val now = SystemClock.elapsedRealtime()
        val ran = (now - frontSince).coerceAtLeast(0L)
        frontSince = 0L
        // A pause shorter than a second is a repaint, not a session.
        if (ran < 1_000L) return
        totalMs += ran
        prefs?.edit {
            putLong(KEY_TOTAL, totalMs)
            putInt(KEY_SESSIONS, (prefs?.getInt(KEY_SESSIONS, 0) ?: 0) + 1)
        }
    }

    /** The stretch in front right now, 0 when Home is not in front. */
    val currentMs: Long get() = if (frontSince == 0L) 0L else SystemClock.elapsedRealtime() - frontSince

    val totalHours: Float get() = totalMs / 3_600_000f

    /** "123.4" for the total, "1 小时 05 分" style for anything shorter. */
    fun hours(total: Long): String = String.format(java.util.Locale.US, "%.1f", total / 3_600_000f)

    fun short(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes < 60) "$minutes 分" else "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}
