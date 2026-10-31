package com.example.privetchat

import java.util.Locale

/** Offline command routing with a latched TV mode; explicit GPT always takes priority. */
internal object VoiceCommandRouter {
    data class Route(
        val openChat: Boolean = false,
        val enterTvMode: Boolean = false,
        val exitTvMode: Boolean = false,
        val tvCommands: List<String> = emptyList(),
        val addressed: Boolean = false
    )

    private val prefixes = listOf("привет чат", "привет чет")
    private val gptNames = listOf("гпт", "gpt", "джипити", "джи пи ти", "джи пити")
    private val exits = setOf("выход", "выйти", "заверши управление",
        "закончи управление", "стоп управление")
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
        "назад" to "back", "телевизор назад" to "back", "вернись" to "back",
        "вверх" to "up", "телевизор вверх" to "up",
        "вниз" to "down", "телевизор вниз" to "down",
        "влево" to "left", "телевизор влево" to "left",
        "вправо" to "right", "телевизор вправо" to "right",
        "окей" to "ok", "ок" to "ok", "телевизор окей" to "ok",
        "подтверди" to "ok", "выбери" to "ok"
    )
    private val directions = linkedMapOf("вверх" to "up", "вниз" to "down",
        "влево" to "left", "вправо" to "right")
    private val shortCommands = directions + mapOf("окей" to "ok")

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), " ").trim()
            .replace(Regex("\\s+"), " ")

    private fun isGpt(text: String): Boolean = gptNames.any {
        text == it || text.startsWith("$it ")
    }

    private fun parseCommands(text: String): List<String> {
        commands[text]?.let { return listOf(it) }
        val tokens = text.split(" ")
        if (tokens.size in 2..5 && tokens.all { it in shortCommands }) {
            return tokens.map { shortCommands.getValue(it) }
        }
        return emptyList()
    }

    fun route(transcript: String, tvModeActive: Boolean): Route {
        val words = normalize(transcript)
        if (words.isEmpty()) return Route()
        val prefix = prefixes.firstOrNull { words == it || words.startsWith("$it ") }
        if (prefix != null) {
            val tail = words.removePrefix(prefix).trim()
            if (isGpt(tail)) return Route(openChat = true, addressed = true)
            if (tail in exits) return Route(exitTvMode = true, addressed = true)
            return Route(enterTvMode = true, tvCommands = parseCommands(tail),
                addressed = true)
        }

        if (!tvModeActive) return Route()
        if (words in exits) return Route(exitTvMode = true, addressed = true)
        // Bare "GPT" never opens ChatGPT; only the explicit long wake phrase does.
        return Route(tvCommands = parseCommands(words), addressed = true)
    }

    /** Finite Vosk grammar for wake words, commands and short command sequences. */
    fun grammar(): String {
        val phrases = linkedSetOf<String>()
        phrases.addAll(prefixes)
        phrases.addAll(gptNames)
        phrases.addAll(commands.keys)
        phrases.addAll(exits)
        for (prefix in prefixes) {
            for (gpt in gptNames) phrases.add("$prefix $gpt")
            for (command in commands.keys) phrases.add("$prefix $command")
            for (exit in exits) phrases.add("$prefix $exit")
        }
        // Supports a five-command sequence, e.g. "вверх вверх вниз вправо влево".
        var sequences = listOf("")
        for (length in 1..5) {
            sequences = sequences.flatMap { before ->
                directions.keys.map { word -> if (before.isEmpty()) word else "$before $word" }
            }
            for (sequence in sequences) {
                phrases.add(sequence)
                if (length <= 3) {
                    for (prefix in prefixes) phrases.add("$prefix $sequence")
                }
            }
        }
        // Mixed navigation and OK in short utterances.
        for (first in shortCommands.keys) {
            for (second in shortCommands.keys) {
                phrases.add("$first $second")
                for (third in shortCommands.keys) {
                    phrases.add("$first $second $third")
                }
            }
        }
        phrases.add("[unk]")
        return phrases.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
    }
}
