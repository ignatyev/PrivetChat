#!/usr/bin/env python3
"""v0.11: keep voice TV session active across service and ChatGPT restarts until 'выход'."""
from pathlib import Path

service = Path("app/src/main/java/com/example/privetchat/AssistantModeService.kt")
s = service.read_text(encoding="utf-8")

def swap(old, new, where):
    global s
    count = s.count(old)
    assert count == 1, f"{where}: expected one anchor, found {count}"
    s = s.replace(old, new, 1)

swap(
    '        const val PREF_INITIALIZED = "background_listening_initialized_v05"',
    '        const val PREF_INITIALIZED = "background_listening_initialized_v05"\n        private const val PREF_TV_MODE = "tv_control_latched"',
    "TV session preference key")
swap(
    '        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)\n        pausedForChat = prefs.getBoolean(PREF_PAUSED, false)',
    '        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)\n        tvModeActive = prefs.getBoolean(PREF_TV_MODE, false)\n        pausedForChat = prefs.getBoolean(PREF_PAUSED, false)',
    "restore TV mode after Android restarts voice assistant")
swap(
    '''        if (route.openChat) {
            triggered = true
            tvModeActive = false
            main.post {''',
    '''        if (route.openChat) {
            triggered = true
            // ChatGPT temporarily takes the mic; TV control resumes afterwards.
            main.post {''',
    "ChatGPT voice handoff must not erase TV session")
swap(
    '''        if (route.exitTvMode) {
            tvModeActive = false
            main.post {''',
    '''        if (route.exitTvMode) {
            tvModeActive = false
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(PREF_TV_MODE, false).apply()
            main.post {''',
    "explicit exit persist")
swap(
    '''        if (route.enterTvMode || route.tvCommands.isNotEmpty()) {
            val wasActive = tvModeActive
            tvModeActive = true
            main.post {''',
    '''        if (route.enterTvMode || route.tvCommands.isNotEmpty()) {
            val wasActive = tvModeActive
            tvModeActive = true
            if (!wasActive) {
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean(PREF_TV_MODE, true).apply()
            }
            main.post {''',
    "persist TV session activation")
swap(
    '''            if (!enabled()) {
                tvModeActive = false
                clearTvCommands()
                pausedForChat = false''',
    '''            if (!enabled()) {
                tvModeActive = false
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean(PREF_TV_MODE, false).apply()
                clearTvCommands()
                pausedForChat = false''',
    "explicit application off clears TV mode")
swap(
    '''    fun pauseForChat() {
        tvModeActive = false
        clearTvCommands()
        if (!pausedForChat) {''',
    '''    fun pauseForChat() {
        // Preserve user's TV session while ChatGPT has exclusive microphone use.
        clearTvCommands()
        if (!pausedForChat) {''',
    "pause TV command dispatch without ending TV session")
service.write_text(s, encoding="utf-8")

gradle = Path("app/build.gradle")
g = gradle.read_text(encoding="utf-8")
assert "versionCode 10" in g and "versionName '0.10-continuous-tv'" in g
g = g.replace("versionCode 10", "versionCode 11", 1)
g = g.replace("versionName '0.10-continuous-tv'",
              "versionName '0.11-persistent-tv'", 1)
gradle.write_text(g, encoding="utf-8")

layout = Path("app/src/main/res/layout/activity_main.xml")
ui = layout.read_text(encoding="utf-8")
ui = ui.replace("Привет, Чат · v0.10", "Привет, Чат · v0.11")
layout.write_text(ui, encoding="utf-8")

print("v0.11: persistent TV mode until voice exit; stable signing remains")
