package com.example.privetchat

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.widget.TextView

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/** Keep the hotword request pending while the device unlocks. */
class AssistantSession(service: AssistantSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantModeService.instance?.pauseForChat()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        try {
            if (keyguard?.isDeviceLocked == true || keyguard?.isKeyguardLocked == true) {
                val intent = Intent(context, UnlockToChatActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startAssistantActivity(intent)
            } else {
                VoiceButtonAccessibilityService.armOnce(context)
                startAssistantActivity(ChatGptLauncher.intent(context))
            }
            finish()
        } catch (error: Exception) {
            val label = TextView(context).apply {
                text = "Не удалось открыть помощника: ${error.javaClass.simpleName}"
                textSize = 18f
                setPadding(48, 36, 48, 36)
                gravity = Gravity.CENTER
            }
            setContentView(label)
        }
    }
}
