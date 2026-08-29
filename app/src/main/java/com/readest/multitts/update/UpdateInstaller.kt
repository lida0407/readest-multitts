package com.readest.multitts.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to Android's package installer.
 *
 * Android still shows its own confirmation screen, so this only gets the file
 * onto the device and opens the installer — it never installs silently.
 */
object UpdateInstaller {

    interface Progress {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
        fun onReady(file: File)
        fun onError(message: String)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun download(activity: Activity, url: String, fileName: String, progress: Progress) {
        cancelled = false
        Thread {
            try {
                // Each download starts clean: a half-finished APK from a dropped
                // connection would fail to install with a confusing parser error.
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, fileName)

                var connection = (URL(url).openConnection() as HttpURLConnection)
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Readest++")

                // GitHub asset URLs redirect to a different host, and
                // HttpURLConnection refuses to follow https→https across hosts.
                var redirects = 0
                while (connection.responseCode in 300..399 && redirects < 5) {
                    val next = connection.getHeaderField("Location") ?: break
                    connection.disconnect()
                    connection = (URL(next).openConnection() as HttpURLConnection)
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("User-Agent", "Readest++")
                    redirects++
                }

                if (connection.responseCode != 200) {
                    post(activity) { progress.onError("Download failed (${connection.responseCode})") }
                    return@Thread
                }

                val total = connection.contentLength.toLong()
                var written = 0L
                var lastPercent = -1

                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) {
                                target.delete()
                                return@Thread
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            val percent =
                                if (total > 0) (written * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                val snapshot = written
                                post(activity) { progress.onProgress(percent, snapshot, total) }
                            }
                        }
                    }
                }
                connection.disconnect()

                if (written == 0L) {
                    target.delete()
                    post(activity) { progress.onError("The download came back empty.") }
                    return@Thread
                }

                post(activity) { progress.onReady(target) }
            } catch (e: Exception) {
                post(activity) { progress.onError(e.message ?: "Download failed") }
            }
        }.start()
    }

    fun install(activity: Activity, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun post(activity: Activity, block: () -> Unit) {
        if (!activity.isFinishing) activity.runOnUiThread(block)
    }
}
