package com.readest.multitts.tts

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Turns the per-sentence cache into ordinary audio files: one AAC/M4A track per
 * chapter, written to Music/Readest++/<book>/ so any player (or a file manager,
 * or a USB copy to a computer) can use them.
 *
 * AAC rather than MP3: Android ships an AAC encoder in MediaCodec, but no MP3
 * encoder — MP3 would need a bundled native encoder. .m4a plays everywhere MP3 does.
 */
object AudioExporter {

    private const val TAG = "AudioExporter"
    private const val SILENCE_MS = 220L      // breathing room between sentences
    private const val BITRATE_MONO = 64_000
    private const val BITRATE_STEREO = 96_000

    interface Progress {
        fun onFile(current: Int, total: Int, name: String)
        fun onDone(files: Int, bytes: Long, location: String)
        fun onError(message: String)
    }

    data class ChapterJob(
        val displayIndex: Int,
        val title: String,
        val wavFiles: List<File>
    )

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * @return true when at least one chapter was written.
     */
    fun exportChapters(
        context: Context,
        bookTitle: String,
        jobs: List<ChapterJob>,
        progress: Progress
    ) {
        cancelled = false
        val usable = jobs.filter { it.wavFiles.isNotEmpty() }
        if (usable.isEmpty()) {
            progress.onError("No cached audio found for this selection. Cache it first, or check the voice it was cached with.")
            return
        }

        val folder = sanitize(bookTitle).ifBlank { "Readest" }
        var written = 0
        var totalBytes = 0L
        var location = ""

        for ((idx, job) in usable.withIndex()) {
            if (cancelled) break
            val name = buildString {
                append(String.format("%02d", job.displayIndex))
                append(" - ")
                append(sanitize(job.title).take(60).ifBlank { "Chapter ${job.displayIndex}" })
                append(".m4a")
            }
            progress.onFile(idx + 1, usable.size, name)

            try {
                val temp = File(context.cacheDir, "export_tmp.m4a")
                if (temp.exists()) temp.delete()

                val bytes = encodeToM4a(job.wavFiles, temp)
                if (bytes <= 0) continue

                val saved = publish(context, folder, name, temp)
                temp.delete()
                if (saved != null) {
                    written++
                    totalBytes += bytes
                    location = saved
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed for ${job.title}", e)
                progress.onError("Could not export ${job.title}: ${e.message}")
                return
            }
        }

        if (written > 0) {
            progress.onDone(written, totalBytes, location)
        } else if (!cancelled) {
            progress.onError("Nothing was exported.")
        }
    }

    /** Concatenate PCM from the WAVs and encode a single AAC track. */
    private fun encodeToM4a(wavFiles: List<File>, output: File): Long {
        val first = wavFiles.firstNotNullOfOrNull { WavFile.read(it)?.let { info -> it to info } } ?: return 0
        val (_, baseInfo) = first
        val sampleRate = baseInfo.sampleRate
        val channels = baseInfo.channels.coerceIn(1, 2)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels > 1) BITRATE_STEREO else BITRATE_MONO)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val bytesPerFrame = channels * 2 // 16-bit PCM
        val silence = ByteArray((sampleRate * bytesPerFrame * SILENCE_MS / 1000).toInt())
        var totalBytesFed = 0L

        try {
            val chunk = ByteArray(16 * 1024)

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else continue
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outIndex)
                            if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            fun feed(data: ByteArray, length: Int) {
                var offset = 0
                while (offset < length) {
                    if (cancelled) return
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex < 0) {
                        drain(false)
                        continue
                    }
                    val input: ByteBuffer = codec.getInputBuffer(inIndex) ?: continue
                    input.clear()
                    val count = minOf(input.capacity(), length - offset)
                    input.put(data, offset, count)
                    val ptsUs = totalBytesFed * 1_000_000L / (sampleRate.toLong() * bytesPerFrame)
                    codec.queueInputBuffer(inIndex, 0, count, ptsUs, 0)
                    totalBytesFed += count
                    offset += count
                    drain(false)
                }
            }

            for (wav in wavFiles) {
                if (cancelled) break
                val info = WavFile.read(wav) ?: continue
                // Mixing sample rates in one track would pitch-shift the audio
                if (info.sampleRate != sampleRate || info.channels.coerceIn(1, 2) != channels) {
                    Log.w(TAG, "Skipping ${wav.name}: ${info.sampleRate}Hz/${info.channels}ch differs from track")
                    continue
                }

                FileInputStream(wav).use { stream ->
                    stream.skip(info.dataOffset)
                    var remaining = info.dataLength
                    while (remaining > 0 && !cancelled) {
                        val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                        val read = stream.read(chunk, 0, toRead)
                        if (read <= 0) break
                        feed(chunk, read)
                        remaining -= read
                    }
                }
                if (silence.isNotEmpty()) feed(silence, silence.size)
            }

            // Signal end of stream
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val ptsUs = totalBytesFed * 1_000_000L / (sampleRate.toLong() * bytesPerFrame)
                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }

        return if (output.exists()) output.length() else 0
    }

    /** Copy the finished file somewhere the user can actually reach it. */
    private fun publish(context: Context, folder: String, fileName: String, source: File): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = "${Environment.DIRECTORY_MUSIC}/Readest++/$folder"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relative)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return relative
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Readest++/$folder"
        )
        dir.mkdirs()
        val target = File(dir, fileName)
        source.copyTo(target, overwrite = true)
        return "Music/Readest++/$folder"
    }

    private fun sanitize(name: String): String =
        name.replace("[\\\\/:*?\"<>|]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
}
