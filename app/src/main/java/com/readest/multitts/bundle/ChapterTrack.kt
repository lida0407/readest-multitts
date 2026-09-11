package com.readest.multitts.bundle

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.readest.multitts.tts.WavFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * One chapter of narration as a single AAC track, and back again.
 *
 * A bundle carries chapters rather than the tens of thousands of per-sentence
 * clips they are made of: the clips are uncompressed WAV, and a long book runs
 * to gigabytes, which is not a thing anyone uploads. Encoded at 64 kbps the same
 * book is tens of megabytes.
 *
 * Boundaries are recorded as PCM byte offsets rather than timestamps, because a
 * byte offset is exact and a timestamp has to be rounded to a frame. The 220 ms
 * of silence between sentences also means that the small delay an AAC encoder
 * adds lands inside the gap rather than clipping a word.
 */
object ChapterTrack {

    private const val TAG = "ChapterTrack"
    private const val SILENCE_MS = 220L
    private const val BITRATE_MONO = 64_000
    private const val BITRATE_STEREO = 96_000
    private const val TIMEOUT_US = 10_000L

    data class Boundary(val sentenceIndex: Int, val startByte: Long, val endByte: Long)

    data class Encoded(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val boundaries: List<Boundary>
    )

    /**
     * Encodes the given clips, in order, into one AAC file.
     *
     * Clips whose format differs from the first are skipped rather than mixed:
     * concatenating a different sample rate into one track pitch-shifts it.
     */
    fun encode(
        clips: List<Pair<Int, File>>,
        output: File,
        isCancelled: () -> Boolean = { false }
    ): Encoded? {
        val base = clips.firstNotNullOfOrNull { (_, f) -> WavFile.read(f) } ?: return null
        val sampleRate = base.sampleRate
        val channels = base.channels.coerceIn(1, 2)
        val bytesPerFrame = channels * 2

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
        val info = MediaCodec.BufferInfo()

        val silence = ByteArray((sampleRate * bytesPerFrame * SILENCE_MS / 1000).toInt())
        val boundaries = mutableListOf<Boundary>()
        var fed = 0L

        try {
            fun drain(end: Boolean) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (end) TIMEOUT_US else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return else continue
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && muxerStarted &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            fun feed(data: ByteArray, length: Int) {
                var offset = 0
                while (offset < length) {
                    if (isCancelled()) return
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index < 0) {
                        drain(false)
                        continue
                    }
                    val input: ByteBuffer = codec.getInputBuffer(index) ?: continue
                    input.clear()
                    val count = minOf(input.capacity(), length - offset)
                    input.put(data, offset, count)
                    codec.queueInputBuffer(
                        index, 0, count,
                        fed * 1_000_000L / (sampleRate.toLong() * bytesPerFrame), 0
                    )
                    fed += count
                    offset += count
                    drain(false)
                }
            }

            val chunk = ByteArray(16 * 1024)
            for ((sentenceIndex, clip) in clips) {
                if (isCancelled()) break
                val clipInfo = WavFile.read(clip) ?: continue
                if (clipInfo.sampleRate != sampleRate || clipInfo.channels.coerceIn(1, 2) != channels) {
                    Log.w(TAG, "Skipping ${clip.name}: format differs from the track")
                    continue
                }
                val start = fed
                FileInputStream(clip).use { stream ->
                    stream.skip(clipInfo.dataOffset)
                    var remaining = clipInfo.dataLength
                    while (remaining > 0 && !isCancelled()) {
                        val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                        val read = stream.read(chunk, 0, toRead)
                        if (read <= 0) break
                        feed(chunk, read)
                        remaining -= read
                    }
                }
                boundaries.add(Boundary(sentenceIndex, start, fed))
                if (silence.isNotEmpty()) feed(silence, silence.size)
            }

            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index, 0, 0,
                    fed * 1_000_000L / (sampleRate.toLong() * bytesPerFrame),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }

        if (!output.exists() || output.length() == 0L || boundaries.isEmpty()) return null
        return Encoded(sampleRate, channels, base.bitsPerSample, boundaries)
    }

    /**
     * Decodes the track and hands back each sentence's slice as a WAV file.
     *
     * [destination] is asked for a file per sentence and may return null to skip
     * one, which is what happens when a clip is already cached.
     */
    fun decodeInto(
        track: File,
        boundaries: List<Boundary>,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        destination: (sentenceIndex: Int) -> File?,
        isCancelled: () -> Boolean = { false }
    ): Int {
        val extractor = MediaExtractor()
        extractor.setDataSource(track.absolutePath)
        val audioTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            return 0
        }
        extractor.selectTrack(audioTrack)
        val inputFormat = extractor.getTrackFormat(audioTrack)
        val codec = MediaCodec.createDecoderByType(
            inputFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC
        )
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        // Boundaries are in the order they were written, so one cursor walks them.
        val ordered = boundaries.sortedBy { it.startByte }
        var cursor = 0
        var written = 0
        var position = 0L
        var sink: Sink? = null

        val info = MediaCodec.BufferInfo()
        var inputDone = false

        try {
            while (!isCancelled()) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(pcm)

                        var offset = 0
                        while (offset < pcm.size && cursor < ordered.size) {
                            val boundary = ordered[cursor]
                            when {
                                // Still before this sentence: discard the gap.
                                position < boundary.startByte -> {
                                    val skip = minOf(
                                        (boundary.startByte - position).toInt(), pcm.size - offset
                                    )
                                    offset += skip
                                    position += skip
                                }

                                position < boundary.endByte -> {
                                    if (sink == null) {
                                        sink = destination(boundary.sentenceIndex)?.let { Sink(it) }
                                            ?: Sink(null)
                                    }
                                    val take = minOf(
                                        (boundary.endByte - position).toInt(), pcm.size - offset
                                    )
                                    sink?.write(pcm, offset, take)
                                    offset += take
                                    position += take
                                }

                                else -> {
                                    sink?.finish(sampleRate, channels, bitsPerSample)?.let { written++ }
                                    sink = null
                                    cursor++
                                }
                            }
                        }
                        if (cursor >= ordered.size) {
                            // Everything wanted has been written; the tail is silence.
                            codec.releaseOutputBuffer(outIndex, false)
                            break
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }
            sink?.finish(sampleRate, channels, bitsPerSample)?.let { written++ }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
        return written
    }

    /**
     * Collects one sentence's PCM and closes it as a WAV.
     *
     * A null file means "decode past this one" — the bytes still have to be
     * consumed to stay aligned with the track, they are simply thrown away.
     */
    private class Sink(private val file: File?) {
        private val stream = file?.let { FileOutputStream(it) }
        private var bytes = 0

        init {
            // Space for the header, backfilled once the length is known.
            stream?.write(ByteArray(44))
        }

        fun write(data: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            stream?.write(data, offset, length)
            bytes += length
        }

        fun finish(sampleRate: Int, channels: Int, bitsPerSample: Int): File? {
            stream?.close()
            val target = file ?: return null
            if (bytes == 0) {
                target.delete()
                return null
            }
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(0)
                raf.write(WavFile.header(sampleRate, channels, bitsPerSample, bytes))
            }
            return target
        }
    }
}
