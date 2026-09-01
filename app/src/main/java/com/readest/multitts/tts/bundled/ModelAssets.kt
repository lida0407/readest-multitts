package com.readest.multitts.tts.bundled

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Unpacks a bundled voice from the APK onto the filesystem.
 *
 * sherpa-onnx can read a model straight out of assets, but espeak-ng — which the
 * English voice needs for pronunciation — opens its data files with plain file
 * IO and aborts the process when handed an asset path. Extracting everything
 * keeps one predictable code path rather than a mix of the two.
 */
object ModelAssets {

    private const val TAG = "ModelAssets"

    /**
     * Returns the directory holding [voice]'s files, extracting it the first
     * time. Extraction is marked complete only at the end, so an install
     * interrupted halfway is redone rather than half-used.
     */
    @Synchronized
    fun ensure(context: Context, voice: BundledVoices.Voice, onProgress: (String) -> Unit = {}): File? {
        // The shared pronunciation data is extracted once and reused, so adding a
        // second Piper voice costs only its model.
        voice.sharedDataDir?.let { shared ->
            if (extract(context, shared, onProgress) == null) return null
        }
        return extract(context, voice.assetDir, onProgress)
    }

    /** Where [assetDir] was unpacked, once it has been. */
    fun dirFor(context: Context, assetDir: String) = File(context.filesDir, assetDir)

    private fun extract(context: Context, assetDir: String, onProgress: (String) -> Unit): File? {
        val target = File(context.filesDir, assetDir)
        val marker = File(target, ".complete")
        if (marker.exists()) return target

        return try {
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            onProgress("Preparing voice data…")
            copyTree(context, assetDir, target)
            marker.writeText(assetDir)
            Log.i(TAG, "Extracted $assetDir to $target")
            target
        } catch (e: Exception) {
            Log.e(TAG, "Could not extract $assetDir", e)
            target.deleteRecursively()
            null
        }
    }

    private fun copyTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // A leaf: assets.list() returns nothing for files.
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyTree(context, "$assetPath/$child", File(target, child))
        }
    }

    fun isExtracted(context: Context, voice: BundledVoices.Voice): Boolean =
        File(File(context.filesDir, voice.assetDir), ".complete").exists()

    fun sizeOnDisk(context: Context, voice: BundledVoices.Voice): Long {
        val dir = File(context.filesDir, voice.assetDir)
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
