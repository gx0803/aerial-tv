package app.orchard.tv.remote

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The two things the phone's page needs that Android only hands an accessibility service: Home and
 * Back while another app is in front, and a way to write into the text field that has focus there.
 *
 * Kept apart from Home Guard on purpose. That one is bound for the life of the TV and is documented
 * as never reading screen content; this one is bound only while the phone page is being used, and
 * reads nothing but the field it is about to fill.
 */
class RemoteService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // Bound for the focused field only; nothing here listens to what is typed elsewhere.
    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    /** Home and Back; no other key can be pressed inside another app. */
    fun global(key: RemoteKey) {
        val action = when (key) {
            RemoteKey.Home -> GLOBAL_ACTION_HOME
            RemoteKey.Back -> GLOBAL_ACTION_BACK
            else -> return
        }
        runCatching { performGlobalAction(action) }
    }

    /** Replaces the text of the field that has focus; false when there is none or it refuses. */
    fun write(text: String): Boolean {
        val field = focusedField() ?: return false
        return setText(field, text)
    }

    /**
     * Takes back the last character of the field that has focus.
     *
     * Written as "the same text without its last character" rather than as a Backspace key: a key
     * only reaches the view Android handed the focus to, while the text goes in as one action and
     * lands the same way in fields that draw their own cursor. False when nothing is focused or the
     * field will not say what it holds, so the caller can press a real key instead.
     */
    fun eraseLast(): Boolean {
        val field = focusedField() ?: return false
        val now = field.text?.toString() ?: return false
        if (now.isEmpty()) return true
        return setText(field, now.dropLast(1))
    }

    /** The field a phone page would type into, or null when nothing in front takes text. */
    private fun focusedField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return null
        return field.takeIf { it.isEditable }
    }

    private fun setText(field: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .onFailure { Log.w(TAG, "could not write into the focused field", it) }
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "AerialRemote"

        @Volatile
        var instance: RemoteService? = null
            private set
    }
}
