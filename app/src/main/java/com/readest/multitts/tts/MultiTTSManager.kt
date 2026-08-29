package com.readest.multitts.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.readest.multitts.model.TTSEngineInfo

object MultiTTSManager {

    val KNOWN_MULTITTS_PACKAGES = listOf(
        "org.nobody.multitts",
        "com.github.samitooooo.multitts",
        "com.reee.multitts",
        "com.github.multitts",
        "com.multitts.android"
    )

    const val MULTITTS_GITHUB_URL = "https://github.com/samitooooo/multitts/releases"
    const val MULTITTS_MIRROR_URL = "https://ghproxy.net/https://github.com/samitooooo/multitts/releases"
    const val MULTITTS_VOICEPACKS_URL = "https://github.com/jing332/tts-server-android/releases"

    fun isMultiTTSInstalled(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in KNOWN_MULTITTS_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }
        return false
    }

    fun getInstalledMultiTTSPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_MULTITTS_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // Continue
            }
        }
        return null
    }

    fun getAvailableTTSEngines(context: Context, tts: TextToSpeech?): List<TTSEngineInfo> {
        val engines = mutableListOf<TTSEngineInfo>()
        val defaultSynth = Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) ?: ""

        val systemEngines = tts?.engines ?: emptyList()
        for (engine in systemEngines) {
            val isMulti = KNOWN_MULTITTS_PACKAGES.contains(engine.name) || engine.name.contains("multitts", ignoreCase = true)
            val isDef = engine.name == defaultSynth
            engines.add(
                TTSEngineInfo(
                    packageName = engine.name,
                    label = if (isMulti) "MultiTTS (${engine.label ?: "MultiTTS"})" else (engine.label ?: engine.name),
                    isMultiTts = isMulti,
                    isDefault = isDef
                )
            )
        }

        // If MultiTTS is installed but not returned in engines list yet, include it
        val multiPkg = getInstalledMultiTTSPackage(context)
        if (multiPkg != null && engines.none { it.packageName == multiPkg }) {
            engines.add(
                0,
                TTSEngineInfo(
                    packageName = multiPkg,
                    label = "MultiTTS Engine",
                    isMultiTts = true,
                    isDefault = (multiPkg == defaultSynth)
                )
            )
        }

        return engines
    }

    fun openSystemTtsSettings(context: Context) {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    fun openDownloadPage(context: Context, useMirror: Boolean = false) {
        val url = if (useMirror) MULTITTS_MIRROR_URL else MULTITTS_GITHUB_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openVoicePacksPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MULTITTS_VOICEPACKS_URL)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
