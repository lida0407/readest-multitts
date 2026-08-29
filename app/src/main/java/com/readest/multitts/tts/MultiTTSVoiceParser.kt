package com.readest.multitts.tts

import android.os.Environment
import com.readest.multitts.model.TTSVoiceInfo
import java.io.File
import java.util.Locale

object MultiTTSVoiceParser {

    fun parseMultiTTSVoices(): List<TTSVoiceInfo> {
        val voices = mutableListOf<TTSVoiceInfo>()

        val candidatePaths = listOf(
            File(Environment.getExternalStorageDirectory(), "Android/data/org.nobody.multitts/files/voice/config.yaml"),
            File(Environment.getExternalStorageDirectory(), "Android/data/com.github.samitooooo.multitts/files/voice/config.yaml"),
            File(Environment.getExternalStorageDirectory(), "MultiTTS/voice/config.yaml")
        )

        val configFile = candidatePaths.firstOrNull { it.exists() && it.canRead() }
        if (configFile != null) {
            try {
                var currentEngine = "MultiTTS"
                var currentCode = ""
                var currentName = ""
                var currentLocale = Locale.SIMPLIFIED_CHINESE

                configFile.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (rawLine.startsWith("microsoft:") || rawLine.startsWith("isstts:") || rawLine.startsWith("sherpa:") || rawLine.startsWith("google:")) {
                        currentEngine = when {
                            rawLine.startsWith("microsoft:") -> "Microsoft"
                            rawLine.startsWith("isstts:") -> "IssTTS"
                            rawLine.startsWith("sherpa:") -> "Sherpa"
                            else -> "Google"
                        }
                    } else if (line.startsWith("code:")) {
                        currentCode = line.substringAfter("code:").trim().trim('"', '\'')
                    } else if (line.startsWith("name:")) {
                        currentName = line.substringAfter("name:").trim().trim('"', '\'')
                    } else if (line.startsWith("locale:")) {
                        val locStr = line.substringAfter("locale:").trim().trim('"', '\'')
                        currentLocale = Locale.forLanguageTag(locStr)
                    } else if (line.startsWith("volume:") || line.startsWith("type:")) {
                        if (currentCode.isNotEmpty() && currentName.isNotEmpty()) {
                            // Sherpa voices run on-device; the rest need the network
                            val offline = currentEngine.equals("Sherpa", ignoreCase = true)
                            val displayName = buildString {
                                append("$currentName ($currentEngine - ${currentLocale.displayLanguage})")
                                if (offline) append("  ⬇ offline 离线")
                            }
                            voices.add(
                                TTSVoiceInfo(
                                    id = currentCode,
                                    name = displayName,
                                    locale = currentLocale,
                                    enginePackage = "org.nobody.multitts",
                                    isMultiTts = true,
                                    isOffline = offline
                                )
                            )
                            currentCode = ""
                            currentName = ""
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Add built-in defaults if no YAML could be read
        if (voices.isEmpty()) {
            voices.add(TTSVoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓 (Microsoft - 中文)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("zh-CN-YunxiNeural", "云希 (Microsoft - 中文)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("huidi_assist", "慧迪 (IssTTS - 助理)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("yezi_emotion", "叶子 (IssTTS - 情感)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("lingxiaoqizhuli", "聆小琪 (IssTTS - 助理)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("lingxiaoyao_comic", "聆小瑶 (IssTTS - 二次元)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("lingxiaoyun", "聆小芸 (IssTTS - 助理)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("lingyouyou", "聆佑佑 (IssTTS - 童声)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("lingfeichen_emo", "聆飞晨 (IssTTS - 情感男声)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("lingfeizhe", "聆飞哲 (IssTTS - 情感男声)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("xiaobei", "小北 (IssTTS - 东北方言)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("nannan", "楠楠 (IssTTS - 童声)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("doudou", "豆豆 (IssTTS - 童声)", Locale.SIMPLIFIED_CHINESE, "org.nobody.multitts", true))
            voices.add(TTSVoiceInfo("kitten-nano-en-v0_1-fp16", "Kitten (Sherpa - English)  ⬇ offline 离线", Locale.US, "org.nobody.multitts", true, isOffline = true))
        }

        return voices
    }
}
