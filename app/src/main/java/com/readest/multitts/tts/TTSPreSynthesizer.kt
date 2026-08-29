package com.readest.multitts.tts

import com.readest.multitts.model.Book
import com.readest.multitts.model.Chapter
import com.readest.multitts.model.SentenceItem
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

interface PreSynthesisProgressListener {
    fun onProgress(current: Int, total: Int, currentItemText: String)
    fun onChapterComplete(chapterIndex: Int, cachedCount: Int, totalBytes: Long)
    fun onAllComplete(totalChaptersCached: Int, totalBytes: Long)
    fun onError(errorMessage: String)

    /** Called as work advances so the position can be persisted and resumed later. */
    fun onCheckpoint(chapterIndex: Int, sentenceIndex: Int, processed: Int, total: Int) {}
}

class TTSPreSynthesizer(
    private val ttsController: TTSEngineController,
    private val audioCache: TTSLocalAudioCache
) {
    private companion object {
        const val TAG = "TTSPreSynth"
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var paused = false

    /**
     * How long recent successful items took. A voice the engine can't actually use
     * never calls back, and waiting a flat 20s+ per sentence for that is what makes
     * a bad run feel endless — so the timeout tracks observed speed instead.
     */
    private val recentDurations = ArrayDeque<Long>()

    private fun timeoutFor(text: String): Long {
        val sample = recentDurations.toList()
        if (sample.size < 3) return 20_000L + text.length * 150L
        val median = sample.sorted()[sample.size / 2]
        return (median * 4).coerceIn(6_000L, 45_000L)
    }

    private fun recordDuration(ms: Long) {
        recentDurations.addLast(ms)
        if (recentDurations.size > 12) recentDurations.removeFirst()
    }

    /**
     * A file can exist and still contain no audio when an engine "succeeds" without
     * producing anything; counting that as cached leaves silent gaps in playback.
     */
    private fun isRealAudio(file: File): Boolean {
        if (!file.exists() || file.length() <= 200) return false
        val info = WavFile.read(file) ?: return file.length() > 1024
        return info.dataLength > 0
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun isPaused(): Boolean = paused

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** Suspends the caching loop while paused; returns false if the job was cancelled. */
    private suspend fun awaitResume(): Boolean {
        while (paused) {
            if (!coroutineContext.isActive) return false
            delay(200)
        }
        return coroutineContext.isActive
    }

    fun preSynthesizeChapter(
        book: Book,
        chapterIndex: Int,
        sentences: List<SentenceItem>,
        listener: PreSynthesisProgressListener,
        startSentenceIndex: Int = 0
    ) {
        cancel()
        paused = false

        job = scope.launch {
            val total = sentences.size
            if (total == 0) {
                withContext(Dispatchers.Main) {
                    listener.onChapterComplete(chapterIndex, 0, 0L)
                    listener.onAllComplete(1, 0L)
                }
                return@launch
            }

            var cachedCount = 0
            var consecutiveFailures = 0
            val voiceId = ttsController.currentVoiceId ?: "default"
            // Cache keys use neutral rate/pitch — see AudioPlaybackService.playSentence
            val rate = 1.0f
            val pitch = 1.0f

            for ((idx, item) in sentences.withIndex()) {
                if (!isActive) break
                if (!awaitResume()) break
                if (idx < startSentenceIndex) continue

                withContext(Dispatchers.Main) {
                    listener.onProgress(idx + 1, total, item.text)
                    listener.onCheckpoint(chapterIndex, idx, idx + 1, total)
                }

                val isAlreadyCached = audioCache.isCached(
                    bookId = book.id,
                    chapterIndex = chapterIndex,
                    sentenceIndex = item.index,
                    voiceId = voiceId,
                    rate = rate,
                    pitch = pitch,
                    text = item.text
                )

                if (isAlreadyCached) {
                    cachedCount++
                    continue
                }

                val outputFile = audioCache.getAudioFile(
                    bookId = book.id,
                    chapterIndex = chapterIndex,
                    sentenceIndex = item.index,
                    voiceId = voiceId,
                    rate = rate,
                    pitch = pitch,
                    text = item.text
                )

                val startedAt = System.currentTimeMillis()
                val success = withTimeoutOrNull(timeoutFor(item.text)) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        val utteranceId = "precache_${book.id}_${chapterIndex}_${item.index}"
                        ttsController.synthesizeToFile(item.text, outputFile, utteranceId) { ok ->
                            if (cont.isActive) {
                                cont.resume(ok)
                            }
                        }
                    }
                } ?: false
                val elapsed = System.currentTimeMillis() - startedAt

                if (success && isRealAudio(outputFile)) {
                    recordDuration(elapsed)
                    android.util.Log.i(TAG, "cached ${item.index} in ${elapsed}ms")
                    cachedCount++
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    android.util.Log.w(TAG, "synthesis failed for ${item.index} after ${elapsed}ms (fail #$consecutiveFailures)")
                    if (consecutiveFailures >= 5) {
                        withContext(Dispatchers.Main) {
                            listener.onError("Synthesis keeps failing — check the MultiTTS engine, selected voice, or network, then retry.")
                        }
                        return@launch
                    }
                }

                delay(30)
            }

            val totalBytes = audioCache.getChapterCachedBytes(book.id, chapterIndex)
            withContext(Dispatchers.Main) {
                listener.onChapterComplete(chapterIndex, cachedCount, totalBytes)
                listener.onAllComplete(1, totalBytes)
            }
        }
    }

    fun preSynthesizeWholeBook(
        book: Book,
        chapters: List<Chapter>,
        listener: PreSynthesisProgressListener,
        startChapterIndex: Int = 0,
        startSentenceIndex: Int = 0
    ) {
        cancel()
        paused = false

        job = scope.launch {
            val chapterSentencesMap = mutableMapOf<Int, List<SentenceItem>>()
            var totalSentencesAll = 0

            for (ch in chapters) {
                val sList = SentenceSplitter.split(ch)
                chapterSentencesMap[ch.index] = sList
                totalSentencesAll += sList.size
            }

            // Everything before the checkpoint counts as already done
            var globalProcessed = chapters
                .filter { it.index < startChapterIndex }
                .sumOf { chapterSentencesMap[it.index]?.size ?: 0 } + startSentenceIndex

            var consecutiveFailures = 0
            val voiceId = ttsController.currentVoiceId ?: "default"
            val rate = 1.0f
            val pitch = 1.0f

            for (ch in chapters) {
                if (!isActive) break
                if (ch.index < startChapterIndex) continue
                val sList = chapterSentencesMap[ch.index] ?: emptyList()

                for (item in sList) {
                    if (!isActive) break
                    if (!awaitResume()) break
                    if (ch.index == startChapterIndex && item.index < startSentenceIndex) continue

                    globalProcessed++

                    withContext(Dispatchers.Main) {
                        listener.onProgress(globalProcessed, totalSentencesAll, "Ch ${ch.index + 1}: ${item.text}")
                        listener.onCheckpoint(ch.index, item.index, globalProcessed, totalSentencesAll)
                    }

                    val isAlreadyCached = audioCache.isCached(
                        bookId = book.id,
                        chapterIndex = ch.index,
                        sentenceIndex = item.index,
                        voiceId = voiceId,
                        rate = rate,
                        pitch = pitch,
                        text = item.text
                    )

                    if (isAlreadyCached) continue

                    val outputFile = audioCache.getAudioFile(
                        bookId = book.id,
                        chapterIndex = ch.index,
                        sentenceIndex = item.index,
                        voiceId = voiceId,
                        rate = rate,
                        pitch = pitch,
                        text = item.text
                    )

                    val startedAt = System.currentTimeMillis()
                    val success = withTimeoutOrNull(timeoutFor(item.text)) {
                        suspendCancellableCoroutine<Boolean> { cont ->
                            val utteranceId = "precache_${book.id}_${ch.index}_${item.index}"
                            ttsController.synthesizeToFile(item.text, outputFile, utteranceId) { ok ->
                                if (cont.isActive) cont.resume(ok)
                            }
                        }
                    } ?: false
                    val elapsed = System.currentTimeMillis() - startedAt

                    if (success && isRealAudio(outputFile)) {
                        recordDuration(elapsed)
                        if (globalProcessed % 20 == 0) {
                            val sample = recentDurations.toList()
                            val avg = if (sample.isEmpty()) 0 else sample.average().toInt()
                            android.util.Log.i(TAG, "ch ${ch.index} item ${item.index}: ${elapsed}ms (avg ${avg}ms)")
                        }
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        android.util.Log.w(TAG, "synthesis failed ch ${ch.index} item ${item.index} after ${elapsed}ms (fail #$consecutiveFailures)")
                        if (consecutiveFailures >= 5) {
                            withContext(Dispatchers.Main) {
                                listener.onError("Synthesis keeps failing — check the MultiTTS engine, selected voice, or network, then retry.")
                            }
                            return@launch
                        }
                    }

                    delay(30)
                }

                // Deliberately not measuring bytes here: it meant a directory scan at
                // every chapter boundary, and the scan grew with the cache.
                withContext(Dispatchers.Main) {
                    listener.onChapterComplete(ch.index, sList.size, 0L)
                }
            }

            val totalBytes = audioCache.getTotalCacheSizeBytes()
            withContext(Dispatchers.Main) {
                listener.onAllComplete(chapters.size, totalBytes)
            }
        }
    }

    fun cancel() {
        paused = false
        job?.cancel()
        job = null
    }
}
