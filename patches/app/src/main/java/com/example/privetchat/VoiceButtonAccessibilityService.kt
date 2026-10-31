package com.example.privetchat

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale

/**
 * Optional, user-enabled local UI automation. This service reacts only to
 * ChatGPT window events. It never reads or stores chat messages.
 *
 * ChatGPT does not expose a documented Android Intent for starting Voice,
 * so we can only click a clearly-labelled Voice conversation button.
 * We deliberately do not guess screen coordinates or click the dictation mic.
 */
class VoiceButtonAccessibilityService : AccessibilityService() {
    companion object {
        private const val CHATGPT = "com.openai.chatgpt"
        private const val PREF = "assistant_settings"
        private const val KEY_ARMED_AT = "voice_launch_armed_at"
        private const val EXPIRE_MS = 20_000L

        fun armOnce(context: Context) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong(KEY_ARMED_AT, SystemClock.elapsedRealtime())
                .apply()
        }
    }

    private var lastAttempt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != CHATGPT) return
        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        val armedAt = prefs.getLong(KEY_ARMED_AT, 0L)
        if (armedAt == 0L) return

        val now = SystemClock.elapsedRealtime()
        if (now < armedAt || now - armedAt > EXPIRE_MS) {
            prefs.edit().remove(KEY_ARMED_AT).apply()
            return
        }
        if (now - lastAttempt < 170) return
        lastAttempt = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != CHATGPT) return

        val candidate = findVoiceControl(root)
        if (candidate != null && clickClickableAncestor(candidate)) {
            prefs.edit().remove(KEY_ARMED_AT).apply()
            sendBroadcast(
                Intent(AssistantModeService.STATUS)
                    .setPackage(packageName)
                    .putExtra(AssistantModeService.EXTRA_STATUS, "Нажата кнопка голосового разговора ChatGPT")
            )
        }
    }

    private fun findVoiceControl(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val displayHeight = resources.displayMetrics.heightPixels

        while (q.isNotEmpty() && seen++ < 400) {
            val node = q.removeFirst()
            val label = listOfNotNull(node.contentDescription, node.text)
                .joinToString(" ").lowercase(Locale.ROOT)
            val score = voiceLabelScore(label)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (score > bestScore && bounds.centerY() > displayHeight / 3 &&
                bounds.width() > 0 && bounds.height() > 0) {
                best = node
                bestScore = score
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { q.add(it) }
            }
        }
        return best
    }

    private fun voiceLabelScore(value: String): Int {
        if (value.isBlank()) return 0
        // This microphone is for one-off speech-to-text, not a conversation.
        if (listOf("диктов", "dictat", "voice typing", "speech to text",
                "микрофон", "microphone", "record audio", "записать аудио")
                .any { it in value }) return 0

        return when {
            listOf("voice conversation", "voice chat", "start voice",
                    "voice mode", "talk with chatgpt", "talk to chatgpt",
                    "голосовой разговор", "голосовой режим", "голосовой чат",
                    "начать голос", "поговорить с chatgpt", "общаться голосом")
                .any { it in value } -> 12
            value.trim() in setOf("voice", "голос") -> 5
            else -> 0
        }
    }

    private fun clickClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            if (current == null) return false
            if (current!!.isClickable && current!!.isEnabled) {
                return current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current!!.parent
        }
        return false
    }

    override fun onInterrupt() = Unit
}
