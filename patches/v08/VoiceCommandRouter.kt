package com.example.privetchat

import java.util.Locale

/**
 * Offline, deterministic routing. ChatGPT is addressed only by the explicit GPT suffix;
 * Haier is addressed by the short wake phrase (in the same or following utterance).
 * No microphone audio or recognized text leaves the device through this router.
 */
internal object VoiceCommandRouter {
    data class Route(
        val openChat: Boolean = false,
        val tvCommand: String? = null,
        val waitForTv: Boolean = false,
        val addressed: Boolean = false
    )

    private val prefixes = listOf("привет чат", "привет чет")
    private val gptNames = listOf("гпт", "gpt", "джипити", "джи пи ти", "джи пити")
    private val commands = linkedMapOf(
        "включи" to "wake", "включи телевизор" to "wake",
        "разбуди телевизор" to "wake", "выключи" to "sleep",
        "выключи телевизор" to "sleep", "усыпи телевизор" to "sleep",
        "громче" to "volume_up", "телевизор громче" to "volume_up",
        "сделай громче" to "volume_up", "увеличь громкость" to "volume_up",
        "прибавь громкость" to "volume_up",
        "тише" to "volume_down", "телевизор тише" to "volume_down",
        "сделай тише" to "volume_down", "уменьши громкость" to "volume_down",
        "убавь громкость" to "volume_down",
        "домой" to "home", "телевизор домой" to "home",
        "на главную" to "home", "главная" to "home",
        "назад" to "back", "телевизор назад" to "back",
        "вернись" to "back",
        "вверх" to "up", "телевизор вверх" to "up",
        "вниз" to "down", "телевизор вниз" to "down",
        "влево" to "left", "телевизор влево" to "left",
        "вправо" to "right", "телевизор вправо" to "right",
        "окей" to "ok", "ок" to "ok", "телевизор окей" to "ok",
        "подтверди" to "ok", "выбери" to "ok"
    )

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), " ").trim()
            .replace(Regex("\\s+"), " ")

    private fun isGpt(text: String): Boolean = gptNames.any {
        text == it || text.startsWith("$it ")
    }

    fun route(transcript: String, waitingForTv: Boolean): Route {
        val words = normalize(transcript)
        if (words.isEmpty()) return Route()
        val prefix = prefixes.firstOrNull { words == it || words.startsWith("$it ") }
        if (prefix != null) {
            val tail = words.removePrefix(prefix).trim()
            if (isGpt(tail)) return Route(openChat = true, addressed = true)
            val command = commands[tail]
            return Route(tvCommand = command, waitForTv = command == null, addressed = true)
        }
        if (waitingForTv) {
            if (isGpt(words)) return Route(openChat = true, addressed = true)
            val command = commands[words]
            return Route(tvCommand = command, waitForTv = command == null,
                addressed = command != null)
        }
        return Route()
    }

    /** Vosk constrained grammar: both one-shot and two-utterance forms. */
    fun grammar(): String {
        val phrases = linkedSetOf<String>()
        phrases.addAll(prefixes)
        for (prefix in prefixes) {
            for (suffix in gptNames) phrases.add("$prefix $suffix")
            for (command in commands.keys) phrases.add("$prefix $command")
        }
        phrases.addAll(gptNames)
        phrases.addAll(commands.keys)
        phrases.add("[unk]")
        return phrases.joinToString(prefix = "[\\"", postfix = "\"]",
            separator = "\",\"")
    }
}
