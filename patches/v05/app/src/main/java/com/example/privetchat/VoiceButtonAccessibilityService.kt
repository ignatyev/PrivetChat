package com.example.privetchat

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Click ChatGPT's voice conversation button only when requested, then detect
 * its return to the ordinary chat UI. Does not store text or inspect other apps.
 */
class VoiceButtonAccessibilityService : AccessibilityService() {
    companion object {
        private const val CHATGPT = "com.openai.chatgpt"
        private const val PREF = "assistant_settings"
        private const val KEY_ARMED_AT = "voice_launch_armed_at"
        private const val EXPIRE_MS = 20_000L

        fun armOnce(context: Context) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong(KEY_ARMED_AT, SystemClock.elapsedRealtime()).apply()
        }
    }

    private var lastAttempt = 0L
    private var clickedAt = 0L
    private var buttonDisappeared = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != CHATGPT) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != CHATGPT) return

        val now = SystemClock.elapsedRealtime()
        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        val armedAt = prefs.getLong(KEY_ARMED_AT, 0L)

        if (armedAt > 0 && now >= armedAt && now - armedAt < EXPIRE_MS) {
            if (now - lastAttempt < 170L) return
            lastAttempt = now
            val button = findVoiceControl(root)
            if (button != null && clickClickableAncestor(button)) {
                prefs.edit().remove(KEY_ARMED_AT).apply()
                clickedAt = now
                buttonDisappeared = false
                sendBroadcast(Intent(AssistantModeService.STATUS).setPackage(packageName)
                    .putExtra(AssistantModeService.EXTRA_STATUS,
                        "Запущен голосовой режим ChatGPT"))
                return
            }
        } else if (armedAt > 0) {
            prefs.edit().remove(KEY_ARMED_AT).apply()
        }

        // When the voice button disappears and later returns, the voice UI was
        // probably closed. Audio capture check in AssistantModeService is still
        // required to avoid reclaiming the mic too early.
        if (clickedAt > 0 && now - clickedAt > 1500L) {
            val voiceButtonVisible = findVoiceControl(root) != null
            if (!voiceButtonVisible) buttonDisappeared = true
            if (voiceButtonVisible && buttonDisappeared && now - clickedAt > 6500L) {
                clickedAt = 0L
                buttonDisappeared = false
                sendBroadcast(Intent(AssistantModeService.CONTROL).setPackage(packageName)
                    .putExtra(AssistantModeService.EXTRA_CHAT_ENDED, true))
            }
        }
    }

    private fun findVoiceControl(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val displayHeight = resources.displayMetrics.heightPixels
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var examined = 0

        while (queue.isNotEmpty() && examined++ < 400) {
            val node = queue.removeFirst()
            val label = listOfNotNull(node.contentDescription, node.text)
                .joinToString(" ").lowercase(Locale.ROOT)
            val score = voiceLabelScore(label)
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (score > bestScore && rect.centerY() > displayHeight / 3 &&
                rect.width() > 0 && rect.height() > 0) {
                best = node
                bestScore = score
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun voiceLabelScore(value: String): Int {
        if (value.isBlank()) return 0
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
            val item = current ?: return false
            if (item.isClickable && item.isEnabled) {
                return item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = item.parent
        }
        return false
    }

    override fun onInterrupt() = Unit
}
