package app.orchard.tv.guard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.util.Log
import app.orchard.tv.dream.AerialDream
import app.orchard.tv.remote.AdbBridge
import app.orchard.tv.remote.RemoteService

/**
 * Turns Home Guard back on after the firmware turned it off.
 *
 * Android removes an app's accessibility services from the enabled list whenever the app is
 * force-stopped, and Xiaomi's firmware force-stops every third-party app when the TV goes to
 * standby. With WRITE_SECURE_SETTINGS (a development permission tools/install.sh grants over ADB;
 * it cannot be granted any other way) Aerial can put the service back each time it starts.
 */
object GuardSetup {
    private const val TAG = "AerialGuard"

    /** elapsedRealtime of the last time the guard had to be switched back on. */
    @Volatile
    var lastRevivedAt = Long.MIN_VALUE / 2
        private set

    private const val PREFS = "system"

    private const val KEY_REVIVALS = "revivals"

    private const val REVIVAL_WINDOW_MS = 10 * 60 * 1000L
    private const val KEY_KEEP_ADB = "keep_adb"
    private const val KEY_OWN_SCREENSAVER = "own_screensaver"
    private const val KEY_HOME_IN_FRONT = "home_in_front"

    /**
     * Kept on disk while Aerial's home screen is in front, so a new process can tell how the last
     * one ended: still set means it died in front (a crash, an update, the firmware killing it),
     * and whatever the viewer sees now was uncovered rather than chosen.
     */
    fun setHomeInFront(context: Context, inFront: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOME_IN_FRONT, inFront).apply()

    fun wasHomeInFront(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME_IN_FRONT, false)

    /**
     * Some firmwares (Xiaomi) switch ADB debugging off on their own, usually around standby.
     * When the owner opts in (tools/install.sh does, with KEEP_ADB=1), Aerial switches it back on.
     */
    /**
     * Notes that the notification listener brought Aerial back after it was stopped. LeTV's eUI
     * (X43, issue #63) stops Aerial every few seconds to minutes; each time Aerial came back, saw
     * nothing in front it knew, took Home, and pulled the viewer out of the app they were in.
     */
    fun noteRevival(context: Context) {
        // Brought back with the screen off: a standby kill (Xiaomi), not eUI stopping Aerial in use.
        // Counting those, a few standbys in a row made the guard leave PatchWall in front at wake.
        if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) return
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = recentRevivals(context, now) + now
        prefs.edit().putString(KEY_REVIVALS, recent.joinToString(",")).apply()
    }

    /** Whether the firmware has stopped Aerial three times or more in the last ten minutes. */
    fun revivedOften(context: Context): Boolean = recentRevivals(context, System.currentTimeMillis()).size >= 3

    private fun recentRevivals(context: Context, now: Long): List<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REVIVALS, null).orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }.filter { now - it in 0..REVIVAL_WINDOW_MS }

    fun keepAdb(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_ADB, false)

    fun setKeepAdb(context: Context, keep: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_ADB, keep).apply()
        if (keep) ensureAdb(context)
    }

    /**
     * Home Guard is bound before the user is unlocked (Direct Boot). Until then the preferences
     * below, in credential storage, cannot be read; the guard catches up on unlock.
     */
    fun isUserUnlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            context.getSystemService(UserManager::class.java).isUserUnlocked

    fun ensureAdb(context: Context) {
        if (!isUserUnlocked(context) || !keepAdb(context)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1) return
        runCatching {
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            Log.i(TAG, "ADB debugging had been switched off; turned it back on")
        }.onFailure { Log.w(TAG, "could not turn ADB back on", it) }
    }

    /**
     * Enabled but not running: seen on TCL after Aerial is updated, where Android keeps the
     * setting but never binds the new version. Writing the list without Aerial and then with it
     * makes Android bind it again.
     */
    fun rebindIfStalled(context: Context) {
        if (HomeGuardService.isRunning) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
        val resolver = context.contentResolver
        val entries = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
        val others = entries.filter { ComponentName.unflattenFromString(it)?.flattenToString() != me }
        if (others.size == entries.size) return // not enabled at all; ensureEnabled handles that
        runCatching {
            // A guard that had to be bound again counts as revived: whatever is in front came up
            // while it was not watching (MiTV-MFTR0, Android 11: woken from standby after Aerial's
            // screensaver, PatchWall stayed in front once the guard was back, issue #47).
            lastRevivedAt = SystemClock.elapsedRealtime()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            // Two writes in a row are coalesced and Android sees no change; give it a moment between.
            // Read the list again then, so a service another app added meanwhile is kept (#57).
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    val now = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                        .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != me }
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (now + me).joinToString(":"))
                }
            }, 1500)
            Log.i(TAG, "Home Guard was enabled but not running; asked Android to bind it again")
        }
    }

    /**
     * Whether Aerial owns the TV's system screensaver (tools/install.sh sets it when it writes
     * screensaver_components; tools/restore.sh clears it). TCL MT5879 writes its own screensaver
     * back on every boot, so Aerial puts itself back each time it starts.
     */
    fun setOwnScreensaver(context: Context, own: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWN_SCREENSAVER, own).apply()
        if (own) keepScreensaver(context)
    }

    private fun keepScreensaver(context: Context) {
        if (!isUserUnlocked(context)) return
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "screensaver_components"
        val current = Settings.Secure.getString(resolver, key).orEmpty()
        val mine = ComponentName(context, AerialDream::class.java).flattenToString()
        // 1.6 and earlier: the class was OrchardDream, and install.sh had claimed the screensaver.
        val renamed = "${context.packageName}/${context.packageName}.dream.OrchardDream" in current
        if (renamed) prefs.edit().putBoolean(KEY_OWN_SCREENSAVER, true).apply()
        if (!prefs.getBoolean(KEY_OWN_SCREENSAVER, false) || current == mine) return
        runCatching {
            Settings.Secure.putString(resolver, key, mine)
            Log.i(TAG, "the system screensaver was $current; put Aerial's back")
        }.onFailure { Log.w(TAG, "could not set the system screensaver", it) }
    }

    /** Returns whether Home Guard had been switched off and was put back. */
    fun ensureEnabled(context: Context): Boolean {
        ensureAdb(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        keepScreensaver(context)
        RevivalSync.ensure(context)
        val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
        // The phone remote's service rides along: it is only useful once enabled, and turning it on
        // here costs nothing while that page is closed (it does nothing until bound).
        val remote = ComponentName(context, RemoteService::class.java).flattenToString()
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val guardOn = entries.any { ComponentName.unflattenFromString(it)?.flattenToString() == me }
        val remoteOn = entries.any { ComponentName.unflattenFromString(it)?.flattenToString() == remote }
        val missing = listOfNotNull(me.takeUnless { guardOn }, remote.takeUnless { remoteOn })
        return runCatching {
            if (missing.isNotEmpty()) {
                if (!guardOn) lastRevivedAt = SystemClock.elapsedRealtime()
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (entries + missing).joinToString(":"))
                Log.i(TAG, "accessibility services had been switched off; turned them back on: $missing")
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            !guardOn
        }.onFailure { Log.w(TAG, "could not re-enable Home Guard", it) }.getOrDefault(false)
    }

    private const val KEY_HOME_SET_AT = "home_set_at"

    /** How often the Home choice is written again while it keeps getting lost. */
    private const val HOME_RECHECK_MS = 60_000L

    /**
     * Makes Aerial the activity Home resolves to.
     *
     * With two launchers installed and neither chosen, Home opens the firmware's "open with"
     * chooser and Back from an app lands on the stock launcher — which is what made it look like
     * Aerial had quit and had to be started by hand. Only the shell can set this (there is no API
     * an app may call for it), so it goes through Aerial's local adb.
     *
     * Written again on every start, because a force-stop takes the choice away with it: XGIMI's
     * firmware force-stops Aerial whenever another app grows past what 1.8 GB of RAM allows, and
     * the next Home press after that went to the chooser again.
     */
    fun claimHome(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        if (now - prefs.getLong(KEY_HOME_SET_AT, Long.MIN_VALUE / 2) < HOME_RECHECK_MS) return
        val isHome = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull() == context.packageName
        if (isHome) {
            prefs.edit().putLong(KEY_HOME_SET_AT, now).apply()
            return
        }
        val ran = runCatching {
            AdbBridge.run("cmd package set-home-activity --user 0 ${context.packageName}/.MainActivity")
        }.getOrDefault(false)
        if (ran) {
            prefs.edit().putLong(KEY_HOME_SET_AT, now).apply()
            Log.i(TAG, "Home had no default; asked the shell to make Aerial it")
        }
    }

    private const val KEY_HELD_AT = "held_at"

    /** How often the background protections are written again. */
    private const val HOLD_RECHECK_MS = 10 * 60 * 1000L

    /**
     * Asks the system to leave Aerial alone while it is in the background.
     *
     * XGIMI's firmware stops background apps itself, from inside system_server: dumpsys activity
     * exit-info shows reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) "from pid 783", and it
     * fires about once a minute, whether or not RAM is short — the last one took Aerial when it was
     * down to 48 MB. Nothing an app can do makes it immune, but two things the shell can write make
     * the system treat it as one it should not touch: the battery-optimisation whitelist and the
     * background AppOp. Both are re-checked periodically rather than trusted once, since an update
     * and some firmware resets drop them again.
     */
    fun holdBackground(context: Context) {
        val me = context.packageName
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        if (now - prefs.getLong(KEY_HELD_AT, Long.MIN_VALUE / 2) < HOLD_RECHECK_MS) return
        // Only the shell may write these, so it goes through Aerial's local adb; without a shell
        // this does nothing and is tried again next time one is up.
        val listed = runCatching { AdbBridge.run("cmd deviceidle whitelist +$me") }.getOrDefault(false)
        if (!listed) return
        runCatching { AdbBridge.run("cmd appops set $me RUN_IN_BACKGROUND allow") }
        prefs.edit().putLong(KEY_HELD_AT, now).apply()
        Log.i(TAG, "asked the system to stop treating Aerial as a background app to free up")
    }
}
