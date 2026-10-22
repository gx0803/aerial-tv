package app.orchard.tv.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.orchard.tv.R

/**
 * Keeps Aerial out of the one process state this firmware clears.
 *
 * Some firmwares (Dangbei's, on the D7XPG) stop background apps on a timer of their own, from
 * inside system_server: `dumpsys activity exit-info` records reason=10 (USER REQUESTED),
 * subreason=21 (FORCE STOP), `from pid 783`, and the tell-tale `state=empty` — the killer only
 * looks at processes Android has parked as empty. Measured on that TV:
 *
 *     0   while Aerial's screensaver (DreamActivity) is in front
 *     100 while Aerial's home screen is in front
 *     900 while another app is in front  (a cached activity; one step short of empty)
 *
 * Nothing an app can do makes it immune — the battery-optimisation whitelist and the
 * RUN_IN_BACKGROUND AppOp that GuardSetup writes do not stop a force-stop — but a process holding
 * a foreground service is never parked as empty in the first place, so it is never selected.
 *
 * The notification is the price of that, and on a TV it has to be one nobody sees: the channel is
 * IMPORTANCE_MIN, the lowest there is, which means no sound, no badge, nothing in the status bar
 * and, above all, no heads-up banner over whatever the viewer is watching. It sits in the
 * notification drawer and nowhere else.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        started = true
        hold()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        hold()
        // Restarted after the firmware stopped Aerial, with the notification put back, rather than
        // left running with nothing holding the process up.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        started = false
        super.onDestroy()
    }

    private fun hold() {
        channel()
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(getString(R.string.keep_alive_text))
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .setShowWhen(false)
            .build()
        runCatching { ServiceCompat.startForeground(this, ID, note, serviceType()) }
            .onFailure {
                // Android 12+ refuses a foreground service started from the background. The home
                // screen starting one is never refused; the guard can be, and it is retried from
                // the home screen on the next start instead.
                Log.w(TAG, "could not raise the keep-alive service", it)
                started = false
                stopSelf()
            }
    }

    /**
     * Android 14 asks every foreground service to say what it is for. Keeping a home screen and its
     * phone page reachable is exactly `specialUse`; older Androids do not know the flag and a
     * service started without one is fine there.
     */
    private fun serviceType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService<NotificationManager>()?.let { nm ->
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.keep_alive_channel), NotificationManager.IMPORTANCE_MIN).apply {
                    description = getString(R.string.keep_alive_text)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }

    companion object {
        private const val TAG = "AerialKeepAlive"
        private const val CHANNEL = "keep_alive"
        private const val ID = 1

        @Volatile
        private var started = false

        /**
         * Started from wherever Aerial is in front — the home screen, its screensaver, or the guard
         * being bound — because that is the only place Android 12+ will let a foreground service
         * start from. Once it is up, the process keeps it: on the D7XPG the home screen's own
         * restart had been the only thing bringing Aerial back after each stop.
         */
        fun start(context: Context) {
            if (started) return
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    // A service already running answers startService with onStartCommand; this is
                    // only for Android versions that have no startForegroundService (below 26).
                    runCatching { context.startService(intent) }.onFailure { e ->
                        Log.w(TAG, "could not start the keep-alive service", e)
                    }
                }
        }
    }
}
