package com.readest.multitts.update

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer release has been published.
 *
 * The repository is public, so this needs no credentials — an app that shipped a
 * token would be handing it to anyone who unpacked the APK.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/lida0407/readest-multitts/releases/latest"

    const val RELEASES_PAGE = "https://github.com/lida0407/readest-multitts/releases"

    data class Release(
        val version: String,
        val apkUrl: String?,
        val apkName: String?,
        val notes: String,
        val publishedAt: String
    )

    sealed class Result {
        data class UpToDate(val current: String) : Result()
        data class Available(val release: Release, val current: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun check(currentVersion: String): Result {
        return try {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Readest++/$currentVersion")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = connection.responseCode
            if (code == 404) return Result.Failed("No releases published yet.")
            if (code != 200) return Result.Failed("GitHub replied $code.")

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return Result.Failed("That release has no version tag.")

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkName: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }

            val release = Release(
                version = tag,
                apkUrl = apkUrl,
                apkName = apkName,
                notes = json.optString("body").take(600),
                publishedAt = json.optString("published_at").take(10)
            )

            if (isNewer(tag, currentVersion)) {
                Result.Available(release, currentVersion)
            } else {
                Result.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            Result.Failed(e.message ?: "Could not reach GitHub.")
        }
    }

    /**
     * Compares dotted versions numerically, so 1.10.0 counts as newer than 1.9.0 —
     * a plain string comparison would get that backwards.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
