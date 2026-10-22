package app.orchard.tv.remote

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * The TV's own adb daemon, reached over loopback.
 *
 * Android gives an app no way to press a key inside another app: `android.permission.INJECT_EVENTS`
 * is signature-only, so an accessibility service can offer Home and Back and nothing else. This
 * firmware, like most Chinese TV builds, runs adbd with `ro.adb.secure=0` on every interface — a
 * plain local socket is handed a shell session with no key at all, and the shell user may inject
 * input. That is exactly the power the ADB switch in 应用管家 hands out; here it does one job,
 * pressing the keys the phone sends, and only while another app is in front.
 *
 * The connection is lazy and dies quietly: with ADB switched off the socket is refused, the phone
 * falls back to Home and Back through the accessibility service, and the next press tries again.
 */
object AdbBridge {
    private const val TAG = "AerialAdbBridge"
    private const val PORT = 5555
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 256 * 1024

    private const val A_CNXN = 0x4e584e43 // "CNXN"
    private const val A_OPEN = 0x4e45504f // "OPEN"
    private const val A_OKAY = 0x59414b4f // "OKAY"
    private const val A_CLSE = 0x45534c43 // "CLSE"
    private const val A_WRTE = 0x45545257 // "WRTE"
    private const val A_AUTH = 0x48545541 // "AUTH"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aerial-adb").apply { isDaemon = true } }

    private val lock = Any()
    private val writeLock = Any()

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var localId = 1
    private var remoteId = 0

    /** True while a shell stream is open; this is what the phone page reports. */
    @Volatile
    var connected = false
        private set

    /** Opens the shell ahead of the first press, so the phone page can report whether it is there. */
    fun warmUp() = io.execute { write("true") }

    /** Sends a key press and release. [onFailure] runs when there is no shell to send it through. */
    fun press(code: Int, onFailure: (() -> Unit)? = null) = io.execute {
        if (!write("input keyevent $code")) onFailure?.invoke()
    }

    /**
     * Types into whatever has focus. `input text` takes ASCII only — the accessibility path is
     * tried first for anything else, and this covers apps whose fields a node action cannot reach.
     */
    fun typeAscii(text: String): Boolean {
        if (text.isEmpty() || text.any { it.code > 126 }) return false
        val quoted = text.replace("'", "").replace(" ", "%s")
        return io.submit<Boolean> { write("input text '$quoted'") }.get()
    }

    /**
     * Runs one shell command and forgets about it. Used for the settings only the shell can change,
     * such as which activity is Home; the caller reads the result back from the system.
     */
    fun run(command: String): Boolean = io.submit<Boolean> { write(command) }.get()

    private fun write(command: String): Boolean = synchronized(lock) {
        if (!ensureOpen()) return false
        runCatching {
            send(A_WRTE, localId, remoteId, (command + "\n").toByteArray())
            true
        }.onFailure {
            Log.w(TAG, "shell write failed", it)
            close()
        }.getOrDefault(false)
    }

    private fun ensureOpen(): Boolean {
        if (connected && socket?.isConnected == true) return true
        close()
        return open()
    }

    private fun open(): Boolean {
        var ok = false
        runCatching {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", PORT), 700)
            s.soTimeout = 2000
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()

            send(A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            if (!awaitConnection() || !awaitShell()) return@runCatching
            // An interactive shell can sit silent for a long time; only the handshake is timed.
            s.soTimeout = 0
            connected = true
            startPump()
            ok = true
        }.onFailure { Log.i(TAG, "no local adb (wireless debugging off?): ${it.message}") }
        if (!ok) close()
        return ok
    }

    private fun awaitConnection(): Boolean {
        while (true) {
            val m = read()
            if (m.cmd == A_CNXN) return true
            // A secure daemon asks for a signature we do not have; nothing to do but give up.
            if (m.cmd == A_AUTH) {
                Log.i(TAG, "adbd wants a key; leaving keys to the accessibility service")
                return false
            }
        }
    }

    private fun awaitShell(): Boolean {
        localId++
        send(A_OPEN, localId, 0, "shell:\u0000".toByteArray())
        while (true) {
            val m = read()
            if (m.cmd == A_OKAY) {
                remoteId = m.arg0
                Log.i(TAG, "shell open through the TV's own adb")
                return true
            }
            if (m.cmd == A_CLSE) return false
        }
    }

    /**
     * Drains what the shell sends and acknowledges it: without the acknowledgements the daemon stops
     * taking our writes after a few kilobytes of unread output.
     */
    private fun startPump() {
        Thread {
            runCatching {
                var alive = true
                while (alive) {
                    val m = read()
                    when (m.cmd) {
                        A_WRTE -> send(A_OKAY, localId, remoteId, ByteArray(0))
                        A_CLSE -> alive = false
                    }
                }
            }.onFailure { }
            connected = false
            runCatching { socket?.close() }
        }.apply {
            isDaemon = true
            name = "aerial-adb-pump"
        }.start()
    }

    private fun close() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private class Message(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(): Message {
        val i = input ?: error("closed")
        val header = ByteArray(24)
        readFully(i, header)
        val cmd = intAt(header, 0)
        val arg0 = intAt(header, 4)
        val arg1 = intAt(header, 8)
        val length = intAt(header, 12)
        val data = ByteArray(length)
        if (length > 0) readFully(i, data)
        return Message(cmd, arg0, arg1, data)
    }

    private fun send(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val o = output ?: error("closed")
        val header = ByteArray(24)
        intTo(header, 0, cmd)
        intTo(header, 4, arg0)
        intTo(header, 8, arg1)
        intTo(header, 12, payload.size)
        intTo(header, 16, CRC32().apply { update(payload) }.value.toInt())
        intTo(header, 20, cmd.inv())
        synchronized(writeLock) {
            o.write(header)
            if (payload.isNotEmpty()) o.write(payload)
            o.flush()
        }
    }

    private fun readFully(i: InputStream, into: ByteArray) {
        var at = 0
        while (at < into.size) {
            val n = i.read(into, at, into.size - at)
            if (n <= 0) error("end of stream")
            at += n
        }
    }

    private fun intAt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun intTo(b: ByteArray, at: Int, value: Int) {
        b[at] = value.toByte()
        b[at + 1] = (value shr 8).toByte()
        b[at + 2] = (value shr 16).toByte()
        b[at + 3] = (value shr 24).toByte()
    }
}
