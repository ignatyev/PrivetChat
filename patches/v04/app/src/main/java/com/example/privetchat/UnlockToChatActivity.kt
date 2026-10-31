package com.example.privetchat

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Non-sensitive overlay on the lock screen. Android still requires the user's
 * PIN, password or biometrics. The wake phrase doesn't need repeating.
 */
class UnlockToChatActivity : Activity() {
    private lateinit var keyguard: KeyguardManager
    private lateinit var message: TextView
    private var askedToUnlock = false
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        keyguard = getSystemService(KeyguardManager::class.java)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(36), dp(28), dp(36))
            setBackgroundColor(Color.rgb(15, 23, 42))
        }
        val title = TextView(this).apply {
            text = "Привет, Чат"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        message = TextView(this).apply {
            text = "Команда услышана. Разблокируйте телефон — ChatGPT и голосовой режим откроются автоматически."
            textSize = 17f
            setTextColor(Color.rgb(203, 213, 225))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(26))
        }
        val unlock = Button(this).apply {
            text = "Разблокировать и открыть ChatGPT"
            setOnClickListener { requestUnlock() }
        }
        val cancel = Button(this).apply {
            text = "Отмена"
            setOnClickListener { finish() }
        }
        container.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(unlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(container)
    }

    override fun onResume() {
        super.onResume()
        openAfterUnlock()
        if (!askedToUnlock && (keyguard.isDeviceLocked || keyguard.isKeyguardLocked)) {
            askedToUnlock = true
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && !launched) requestUnlock()
            }, 450L)
        }
    }

    private fun requestUnlock() {
        if (launched || isFinishing) return
        if (!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) {
            openAfterUnlock()
            return
        }
        message.text = "Подтвердите разблокировку обычным способом на экране Samsung."
        try {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    openAfterUnlock(1)
                }
                override fun onDismissCancelled() {
                    message.text = "Разблокировка отменена. Нажмите кнопку, чтобы повторить."
                }
                override fun onDismissError() {
                    message.text = "Автоматический запрос недоступен. Разблокируйте телефон обычным способом."
                }
            })
        } catch (_: Exception) {
            message.text = "Разблокируйте телефон обычным способом."
        }
    }

    private fun openAfterUnlock(retry: Int = 0) {
        if (launched || isFinishing || isDestroyed) return
        if (keyguard.isDeviceLocked || keyguard.isKeyguardLocked) {
            if (retry in 1..20) {
                window.decorView.postDelayed({ openAfterUnlock(retry + 1) }, 250L)
            }
            return
        }
        launched = true
        try {
            VoiceButtonAccessibilityService.armOnce(this)
            startActivity(ChatGptLauncher.intent(this))
            finish()
        } catch (_: Exception) {
            launched = false
            message.text = "Не удалось запустить ChatGPT. Проверьте, что он установлен."
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
