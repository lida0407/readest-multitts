package com.readest.multitts.dict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Google translation, inline where possible and by handing off to the Translate
 * app where not.
 *
 * Inline results keep the reader in the book, which is the whole point of a
 * tap-a-word gesture; the handoff exists because the inline endpoint needs a
 * network and the installed app may not.
 */
object Translator {

    private const val TAG = "Translator"
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    data class Translation(val text: String, val sourceLanguage: String?, val romanization: String?)

    sealed class Result {
        data class Ok(val translation: Translation) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun translate(text: String, targetLanguage: String): Result {
        if (text.isBlank()) return Result.Failed("Nothing to translate.")
        return try {
            val url = URL(
                "$ENDPOINT?client=gtx&sl=auto&tl=${URLEncoder.encode(targetLanguage, "UTF-8")}" +
                    "&dt=t&dt=rm&q=${URLEncoder.encode(text, "UTF-8")}"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Readest++")
            }
            val code = connection.responseCode
            if (code != 200) return Result.Failed("Translation service replied $code.")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parse(body)?.let { Result.Ok(it) } ?: Result.Failed("Couldn't read the translation.")
        } catch (e: Exception) {
            Log.w(TAG, "Translate failed", e)
            Result.Failed(e.message ?: "No network.")
        }
    }

    /**
     * The response is a nested array whose first element holds the sentence
     * chunks; each chunk is [translated, original, …] with romanization tacked
     * on as a trailing chunk that has no translation of its own.
     */
    private fun parse(body: String): Translation? {
        return try {
            val root = JSONArray(body)
            val chunks = root.optJSONArray(0) ?: return null
            val builder = StringBuilder()
            var romanization: String? = null
            for (i in 0 until chunks.length()) {
                val chunk = chunks.optJSONArray(i) ?: continue
                val translated = chunk.optString(0, "")
                if (translated.isNotEmpty() && translated != "null") {
                    builder.append(translated)
                } else {
                    val rm = chunk.optString(2, "")
                    if (rm.isNotEmpty() && rm != "null") romanization = rm
                }
            }
            val source = root.optString(2, "").takeIf { it.isNotEmpty() && it != "null" }
            val text = builder.toString().trim()
            if (text.isEmpty()) null else Translation(text, source, romanization)
        } catch (e: Exception) {
            null
        }
    }

    /** Opens whatever translation app the device has, with [text] already filled in. */
    fun openExternal(context: Context, text: String, targetLanguage: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = Intent(Intent.ACTION_TRANSLATE).apply {
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }

        // Google Translate registers a PROCESS_TEXT handler on every version.
        val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            setPackage("com.google.android.apps.translate")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (process.resolveActivity(context.packageManager) != null) {
            context.startActivity(process)
            return true
        }

        return try {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://translate.google.com/?sl=auto&tl=$targetLanguage" +
                        "&text=${URLEncoder.encode(text, "UTF-8")}&op=translate"
                )
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(web)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Sensible default target: whatever the phone is set to. */
    fun deviceLanguage(): String = Locale.getDefault().language.ifBlank { "en" }

    val COMMON_TARGETS = listOf(
        "en" to "English",
        "zh-CN" to "中文 (简体)",
        "zh-TW" to "中文 (繁體)",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "ja" to "日本語",
        "ko" to "한국어",
        "pl" to "Polski",
        "ru" to "Русский",
        "pt" to "Português",
        "it" to "Italiano",
        "ar" to "العربية",
        "hi" to "हिन्दी"
    )
}
