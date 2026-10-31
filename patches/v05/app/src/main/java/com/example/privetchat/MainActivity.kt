package com.example.privetchat

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * One permanent, user-controlled switch. First launch of v0.5 enables
 * background listening once the user has selected us as assistant and
 * granted microphone permission; explicit OFF remains OFF across restarts.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var assistantLabel: TextView
    private lateinit var toggle: Switch
    private var updatingToggle = false

    private val requestAudio = 101
    private val requestAssistantRole = 202

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AssistantModeService.STATUS) return
            status.text = intent.getStringExtra(AssistantModeService.EXTRA_STATUS) ?: ""
            updateToggle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        assistantLabel = findViewById(R.id.lastWords)
        toggle = findViewById(R.id.listenSwitch)

        findViewById<Button>(R.id.chooseAssistant).setOnClickListener { chooseAssistant() }
        findViewById<Button>(R.id.accessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.openChat).setOnClickListener {
            ChatGptLauncher.launch(this)
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            if (updatingToggle) return@setOnCheckedChangeListener
            if (checked && !canListen()) {
                updateToggle()
                askForRequiredPermissions()
            } else {
                setListening(checked)
                status.text = if (checked) "Включаю фоновое прослушивание…" else "Прослушивание выключено"
                updateToggle()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(AssistantModeService.STATUS),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, IntentFilter(AssistantModeService.STATUS))
        }
        initializeOnceWhenReady()
        updateStatus()
    }

    override fun onStop() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onStop()
    }

    private fun roleHeld(): Boolean {
        val rm = getSystemService(RoleManager::class.java)
        return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    private fun canListen(): Boolean =
        roleHeld() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun initializeOnceWhenReady() {
        val prefs = getSharedPreferences(AssistantModeService.PREF, MODE_PRIVATE)
        if (!prefs.contains(AssistantModeService.PREF_INITIALIZED) && canListen()) {
            prefs.edit()
                .putBoolean(AssistantModeService.PREF_INITIALIZED, true)
                .putBoolean(AssistantModeService.PREF_LISTEN, true)
                .apply()
            notifyService()
            status.text = "Прослушивание включено автоматически"
        }
    }

    private fun setListening(enabled: Boolean) {
        getSharedPreferences(AssistantModeService.PREF, MODE_PRIVATE)
            .edit()
            .putBoolean(AssistantModeService.PREF_INITIALIZED, true)
            .putBoolean(AssistantModeService.PREF_LISTEN, enabled)
            .apply()
        notifyService()
    }

    private fun notifyService() {
        sendBroadcast(Intent(AssistantModeService.CONTROL).setPackage(packageName))
    }

    private fun updateToggle() {
        val enabled = getSharedPreferences(AssistantModeService.PREF, MODE_PRIVATE)
            .getBoolean(AssistantModeService.PREF_LISTEN, false)
        updatingToggle = true
        toggle.isChecked = enabled
        updatingToggle = false
    }

    private fun updateStatus() {
        val enabled = getSharedPreferences(AssistantModeService.PREF, MODE_PRIVATE)
            .getBoolean(AssistantModeService.PREF_LISTEN, false)
        status.text = when {
            !roleHeld() -> "Выбери «Привет, Чат» системным помощником"
            !canListen() -> "Нужно разрешить микрофон"
            !enabled -> "Прослушивание выключено"
            AssistantModeService.listening -> "Слушаю «Привет, Чат» в фоне"
            else -> "Включено. Ожидаю завершения разговора или запуска микрофона…"
        }
        assistantLabel.text = "Системный помощник: " +
            (if (roleHeld()) "Привет, Чат" else "другой")
        updateToggle()
    }

    private fun askForRequiredPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestAudio)
            return
        }
        if (!roleHeld()) chooseAssistant()
    }

    private fun chooseAssistant() {
        val rm = getSystemService(RoleManager::class.java)
        try {
            if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                    requestAssistantRole)
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Выбери «Привет, Чат» системным помощником",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    @Deprecated("Legacy Activity result API used for Android assistant role dialog")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestAssistantRole) {
            if (roleHeld() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestAudio)
            } else {
                initializeOnceWhenReady()
                updateStatus()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
        permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestAudio) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (!roleHeld()) chooseAssistant()
                else initializeOnceWhenReady()
            } else {
                Toast.makeText(this, "Без микрофона фоновое распознавание невозможно",
                    Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }
    }
}
