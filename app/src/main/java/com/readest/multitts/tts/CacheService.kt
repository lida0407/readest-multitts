package com.readest.multitts.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.readest.multitts.MainActivity
import com.readest.multitts.R
import com.readest.multitts.model.Book
import com.readest.multitts.model.BookRepository
import com.readest.multitts.model.Chapter
import com.readest.multitts.reader.DocumentManager
import com.readest.multitts.reader.PdfParser
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs pre-caching as a foreground service so a whole-book job keeps going with the
 * screen off or the app in the background — previously the work died with the Activity.
 * Holds a partial wake lock for the duration and publishes progress to any UI listening.
 */
class CacheService : Service() {

    companion object {
        private const val TAG = "CacheService"
        const val CHANNEL_ID = "readest_multitts_cache_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.readest.multitts.CACHE_START"
        const val ACTION_PAUSE = "com.readest.multitts.CACHE_PAUSE"
        const val ACTION_RESUME = "com.readest.multitts.CACHE_RESUME"
        const val ACTION_STOP = "com.readest.multitts.CACHE_STOP"

        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_WHOLE_BOOK = "whole_book"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
        const val EXTRA_RESUME = "resume"

        data class State(
            val bookId: String? = null,
            val bookTitle: String = "",
            val running: Boolean = false,
            val paused: Boolean = false,
            val processed: Int = 0,
            val total: Int = 0,
            val currentText: String = "",
            val message: String? = null,
            val error: String? = null,
            /** Rolling average seconds per sentence, so a slow run is visible. */
            val secondsPerItem: Double = 0.0
        ) {
            val percent: Int get() = if (total > 0) (processed * 100 / total).coerceIn(0, 100) else 0

            /** Remaining time at the observed rate, or null while still measuring. */
            val etaText: String?
                get() {
                    if (secondsPerItem <= 0 || total <= processed) return null
                    val secs = ((total - processed) * secondsPerItem).toLong()
                    val h = secs / 3600
                    val m = (secs % 3600) / 60
                    return if (h > 0) "~${h}h ${m}m left" else "~${m}m left"
                }
        }

        @Volatile
        var state = State()
            private set

        private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

        fun addListener(listener: (State) -> Unit) {
            listeners.add(listener)
            listener(state)
        }

        fun removeListener(listener: (State) -> Unit) {
            listeners.remove(listener)
        }

        private fun publish(next: State) {
            state = next
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { runCatching { it(next) } }
            }
        }

        fun start(context: Context, bookId: String, wholeBook: Boolean, chapterIndex: Int, resume: Boolean) {
            val intent = Intent(context, CacheService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_WHOLE_BOOK, wholeBook)
                putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                putExtra(EXTRA_RESUME, resume)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, CacheService::class.java).apply { this.action = action })
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CacheService = this@CacheService
    }

    private val binder = LocalBinder()
    private lateinit var audioCache: TTSLocalAudioCache
    private lateinit var checkpoints: CacheCheckpointStore
    private lateinit var bookRepository: BookRepository
    private var ttsController: TTSEngineController? = null
    private var preSynthesizer: TTSPreSynthesizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null
    private var jobStartedAt = 0L
    private var itemsAtStart = 0

    override fun onCreate() {
        super.onCreate()
        audioCache = TTSLocalAudioCache(this)
        checkpoints = CacheCheckpointStore(this)
        bookRepository = BookRepository(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> {
                preSynthesizer?.pause()
                publish(state.copy(paused = true, message = "Paused · 已暂停"))
                updateNotification()
            }
            ACTION_RESUME -> {
                preSynthesizer?.resume()
                publish(state.copy(paused = false, message = null))
                updateNotification()
            }
            ACTION_STOP -> {
                stopWork("Stopped · 已停止（进度已保存）")
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (state.running) {
            Log.w(TAG, "Cache job already running; ignoring start")
            return
        }
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        val wholeBook = intent.getBooleanExtra(EXTRA_WHOLE_BOOK, false)
        val chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)

        val book = bookRepository.getAllBooks().firstOrNull { it.id == bookId } ?: return

        publish(
            State(
                bookId = bookId,
                bookTitle = book.title,
                running = true,
                message = "Preparing…"
            )
        )
        jobStartedAt = 0L
        itemsAtStart = 0
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        worker = Thread { runJob(book, wholeBook, chapterIndex, resume) }.also { it.start() }
    }

    private fun runJob(book: Book, wholeBook: Boolean, chapterIndex: Int, resume: Boolean) {
        val prefs = getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
        PdfParser.init(this)
        val chapters: List<Chapter> = try {
            DocumentManager.loadBookCached(this, File(book.filePath)).second
        } catch (e: Exception) {
            finishWithError("Could not read the book file: ${e.message}")
            return
        }

        Log.i(TAG, "job start: ${book.title}, wholeBook=$wholeBook, resume=$resume, chapters=${chapters.size}")
        val controller = TtsEngine.get(this).also { ttsController = it }
        controller.currentRate = prefs.getFloat("tts_rate", 1.0f)
        controller.currentPitch = prefs.getFloat("tts_pitch", 1.0f)
        val savedVoice = prefs.getString("tts_voice", null)
        val wantedEngine = prefs.getString("tts_engine", null)
            ?: MultiTTSManager.getInstalledMultiTTSPackage(this)

        val begin = { ready: Boolean ->
            if (!ready) {
                finishWithError("The speech engine did not start.")
            } else {
            Log.i(TAG, "engine ready; voice=$savedVoice")

            // Probing runs off the main thread: it blocks on the engine's callback
            Thread {
                val voice = ensureWorkingVoice(controller, savedVoice)
                if (voice == null) {
                    finishWithError(
                        "The selected voice produced no audio with this engine. Open Voice & playback and pick a different voice."
                    )
                    return@Thread
                }

            val synthesizer = TTSPreSynthesizer(controller, audioCache).also { preSynthesizer = it }
            val checkpoint = if (resume) checkpoints.get(book.id) else null

            val listener = object : PreSynthesisProgressListener {
                override fun onProgress(current: Int, total: Int, currentItemText: String) {
                    if (jobStartedAt == 0L) {
                        jobStartedAt = System.currentTimeMillis()
                        itemsAtStart = current
                    }
                    val done = current - itemsAtStart
                    val perItem = if (done > 0) {
                        (System.currentTimeMillis() - jobStartedAt) / 1000.0 / done
                    } else 0.0
                    if (current % 25 == 0) {
                        Log.i(TAG, "progress $current/$total · ${"%.1f".format(perItem)}s per sentence")
                    }
                    publish(
                        state.copy(
                            processed = current,
                            total = total,
                            currentText = currentItemText,
                            message = null,
                            secondsPerItem = perItem
                        )
                    )
                    if (current % 5 == 0) updateNotification()
                }

                override fun onCheckpoint(chapterIdx: Int, sentenceIndex: Int, processed: Int, total: Int) {
                    if (processed % 10 != 0 && processed != total) return
                    checkpoints.save(
                        CacheCheckpoint(
                            bookId = book.id,
                            bookTitle = book.title,
                            wholeBook = wholeBook,
                            chapterIndex = chapterIdx,
                            sentenceIndex = sentenceIndex,
                            processed = processed,
                            total = total,
                            voiceId = controller.currentVoiceId ?: "default"
                        )
                    )
                }

                override fun onChapterComplete(chapterIdx: Int, cachedCount: Int, totalBytes: Long) {}

                override fun onAllComplete(totalChaptersCached: Int, totalBytes: Long) {
                    checkpoints.clear(book.id)
                    publish(
                        state.copy(
                            running = false,
                            paused = false,
                            message = "✓ Finished · ${audioCache.getFormattedCacheSize()} cached"
                        )
                    )
                    stopSelfCleanly()
                }

                override fun onError(errorMessage: String) {
                    finishWithError(errorMessage)
                }
            }

            if (wholeBook) {
                synthesizer.preSynthesizeWholeBook(
                    book, chapters, listener,
                    startChapterIndex = checkpoint?.chapterIndex ?: 0,
                    startSentenceIndex = checkpoint?.sentenceIndex ?: 0
                )
            } else {
                val chapter = chapters.getOrNull(chapterIndex)
                if (chapter == null) {
                    finishWithError("That chapter is no longer in the book.")
                } else {
                    synthesizer.preSynthesizeChapter(
                        book, chapterIndex, SentenceSplitter.split(chapter), listener,
                        startSentenceIndex = if (checkpoint?.chapterIndex == chapterIndex) checkpoint.sentenceIndex else 0
                    )
                }
            }
            }.start()
            }
        }

        // Reuse the live connection when it is already on the right engine
        if (controller.isInitialized.get() && controller.currentEnginePackage == wantedEngine) {
            begin(true)
        } else {
            controller.initEngine(wantedEngine) { ready -> begin(ready) }
        }
    }

    /**
     * A voice that the engine cannot actually speak never calls back, so every
     * sentence burns the full timeout — hours of "caching" that produces nothing.
     * One short probe up front catches that, and we fall back to a voice that works.
     */
    private fun ensureWorkingVoice(controller: TTSEngineController, savedVoice: String?): String? {
        if (probeVoice(controller, savedVoice)) return savedVoice ?: controller.currentVoiceId
        // One retry — a single failure is often the engine still waking up, and the
        // cache belongs to this voice
        if (probeVoice(controller, savedVoice)) return savedVoice ?: controller.currentVoiceId

        Log.w(TAG, "voice '$savedVoice' produced no audio; looking for a working one")

        val candidates = controller.getVoices()
            .map { it.id }
            .filter { it != savedVoice }
            .distinct()
            .take(4)

        val searchDeadline = System.currentTimeMillis() + 25_000
        for ((i, candidate) in candidates.withIndex()) {
            if (!state.running) return null
            if (System.currentTimeMillis() > searchDeadline) {
                Log.w(TAG, "voice search timed out")
                break
            }
            publish(state.copy(message = "Checking voices… (${i + 1}/${candidates.size})"))
            if (probeVoice(controller, candidate)) {
                getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
                    .edit().putString("tts_voice", candidate).apply()
                Log.i(TAG, "switched to working voice: $candidate")
                publish(state.copy(message = "Switched to a working voice: $candidate"))
                return candidate
            }
        }

        // Nothing verified. A probe can fail for reasons that don't repeat (engine
        // busy, a moment without network), so start anyway rather than refusing —
        // the run stops itself after five consecutive failures.
        Log.w(TAG, "no voice verified; proceeding with '$savedVoice' anyway")
        savedVoice?.let { controller.setVoice(it) }
        publish(state.copy(message = "Couldn't verify a voice — starting anyway"))
        return savedVoice ?: controller.currentVoiceId ?: "default"
    }

    /** Synthesize a couple of words and check real audio comes back. */
    private fun probeVoice(controller: TTSEngineController, voiceId: String?): Boolean {
        // A previous request that never completed stays queued and swallows the next
        // ones, which made good voices look broken.
        controller.stop()
        voiceId?.let { controller.setVoice(it) }
        Thread.sleep(250)
        val probeFile = File(cacheDir, "voice_probe.wav")
        // The cache directory can be wiped by "clear cache" while we're running
        probeFile.parentFile?.mkdirs()
        probeFile.delete()

        val latch = CountDownLatch(1)
        var reportedOk = false
        controller.synthesizeToFile(
            "test 测试",
            probeFile,
            "probe_${System.currentTimeMillis()}"
        ) { ok ->
            reportedOk = ok
            latch.countDown()
        }

        val answered = try {
            latch.await(6, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        val usable = answered && reportedOk && probeFile.exists() && probeFile.length() > 200
        Log.i(TAG, "probe voice=$voiceId answered=$answered ok=$reportedOk bytes=${probeFile.length()}")
        probeFile.delete()
        if (!usable) controller.stop()
        return usable
    }

    private fun finishWithError(message: String) {
        publish(state.copy(running = false, paused = false, error = message))
        stopSelfCleanly()
    }

    private fun stopWork(message: String) {
        preSynthesizer?.cancel()
        publish(state.copy(running = false, paused = false, message = message))
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        releaseWakeLock()
        ttsController = null
        TtsEngine.releaseIfIdle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Readest::CacheService").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // safety cap: a whole book should never take longer
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock release failed", e)
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline audio caching",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress while narration is pre-synthesized for offline listening"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val s = state
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun action(requestCode: Int, act: String) = PendingIntent.getService(
            this, requestCode,
            Intent(this, CacheService::class.java).apply { action = act },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book_audio)
            .setContentTitle(
                if (s.paused) "Paused · ${s.bookTitle}" else "Caching audio · ${s.bookTitle}"
            )
            .setContentText(
                if (s.total > 0) buildString {
                    append("${s.processed} / ${s.total} (${s.percent}%)")
                    if (s.secondsPerItem > 0) append(" · ${"%.1f".format(s.secondsPerItem)}s/sentence")
                    s.etaText?.let { append(" · $it") }
                } else (s.message ?: "Preparing…")
            )
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setProgress(s.total.coerceAtLeast(1), s.processed, s.total == 0)

        if (s.paused) {
            builder.addAction(android.R.drawable.ic_media_play, "Resume", action(11, ACTION_RESUME))
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", action(12, ACTION_PAUSE))
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(13, ACTION_STOP))

        return builder.build()
    }

    private fun updateNotification() {
        if (!state.running) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "system FGS timeout — stopping and keeping the checkpoint")
        stopWork("Paused by Android (time limit) · 可继续")
    }

    override fun onDestroy() {
        preSynthesizer?.cancel()
        releaseWakeLock()
        ttsController = null
        TtsEngine.releaseIfIdle()
        super.onDestroy()
    }
}
