package com.readest.multitts.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * A short two-note cue played at a chapter boundary, so a listener with the screen
 * off knows a chapter ended and the next one is starting.
 *
 * Generated rather than shipped as an asset: it works with no network, no TTS engine,
 * and never gets caught by the synthesis watchdog.
 */
object ChapterChime {

    private const val TAG = "ChapterChime"
    private const val SAMPLE_RATE = 44100

    /** Descending pair — "that chapter is done". */
    fun playChapterEnd() = play(listOf(784f to 130, 587f to 190))

    /** Rising pair — "here comes the next one". */
    fun playChapterStart() = play(listOf(587f to 120, 784f to 200))

    private fun play(notes: List<Pair<Float, Int>>) {
        try {
            val samples = buildTone(notes)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    try { t?.release() } catch (_: Exception) {}
                }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "Could not play chapter cue", e)
        }
    }

    private fun buildTone(notes: List<Pair<Float, Int>>): ShortArray {
        val total = notes.sumOf { SAMPLE_RATE * it.second / 1000 }
        val out = ShortArray(total)
        var offset = 0
        for ((freq, ms) in notes) {
            val count = SAMPLE_RATE * ms / 1000
            for (i in 0 until count) {
                // Soft attack/decay so it reads as a chime, not a beep
                val progress = i.toFloat() / count
                val envelope = when {
                    progress < 0.08f -> progress / 0.08f
                    progress > 0.55f -> ((1f - progress) / 0.45f).coerceAtLeast(0f)
                    else -> 1f
                }
                val value = sin(2.0 * PI * freq * i / SAMPLE_RATE) * envelope * 0.28
                out[offset + i] = (value * Short.MAX_VALUE).toInt().toShort()
            }
            offset += count
        }
        return out
    }
}
