package com.readest.multitts.update

import android.util.Log
import org.json.JSONArray
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

    // The whole list, not /releases/latest: two tracks are published from this
    // repo, and /latest would happily hand the bundled-voices build to someone
    // running the standard one, or the other way round.
    private const val RELEASES_URL =
        "https://api.github.com/repos/lida0407/readest-multitts/releases?per_page=30"

    const val RELEASES_PAGE = "https://github.com/lida0407/readest-multitts/releases"

    /**
     * Tags carry their track as a suffix — `v1.17.0` for standard, and
     * `v1.17.0-bundled` for the build that ships its own voices.
     */
    private const val BUNDLED_SUFFIX = "-bundled"

    private fun trackOf(tag: String): String =
        if (tag.endsWith(BUNDLED_SUFFIX)) "bundled" else "standard"

    private fun versionOf(tag: String): String =
        tag.removePrefix("v").removeSuffix(BUNDLED_SUFFIX)

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

    fun check(currentVersion: String, track: String = "standard"): Result {
        return try {
            val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
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

            val releaseInfo = selectRelease(body, track)
                ?: return Result.Failed("No $track release has been published yet.")
            val tag = releaseInfo.version

            if (isNewer(tag, currentVersion)) {
                Result.Available(releaseInfo, currentVersion)
            } else {
                Result.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            Result.Failed(e.message ?: "Could not reach GitHub.")
        }
    }

    /**
     * Picks the newest release belonging to [track] from a GitHub releases list.
     *
     * Kept separate from the request so the choice can be tested: handing someone
     * the wrong track's APK fails at install time with a bare parser error, long
     * after the mistake was made.
     */
    fun selectRelease(body: String, track: String): Release? {
        val releases = JSONArray(body)
        var best: JSONObject? = null
        var bestVersion = ""
        for (i in 0 until releases.length()) {
            val entry = releases.getJSONObject(i)
            if (entry.optBoolean("draft")) continue
            val tag = entry.optString("tag_name")
            if (tag.isEmpty() || trackOf(tag) != track) continue
            val version = versionOf(tag)
            if (version.isEmpty()) continue
            // Releases arrive newest-first by date, but a hotfix can be published
            // after a larger version; compare the numbers rather than trust order.
            if (best == null || isNewer(version, bestVersion)) {
                best = entry
                bestVersion = version
            }
        }
        val release = best ?: return null

        var apkUrl: String? = null
        var apkName: String? = null
        val assets = release.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                // A release can carry both tracks' APKs; take only this one's.
                val assetIsBundled = name.contains("bundled", ignoreCase = true)
                if (assetIsBundled != (track == "bundled")) continue
                apkName = name
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }

        return Release(
            version = bestVersion,
            apkUrl = apkUrl,
            apkName = apkName,
            notes = release.optString("body").take(600),
            publishedAt = release.optString("published_at").take(10)
        )
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
