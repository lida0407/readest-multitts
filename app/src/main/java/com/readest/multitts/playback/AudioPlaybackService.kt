package com.readest.multitts.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.readest.multitts.MainActivity
import com.readest.multitts.R
import com.readest.multitts.model.Book
import com.readest.multitts.model.Chapter
import com.readest.multitts.model.SentenceItem
import com.readest.multitts.tts.TTSEngineController
import com.readest.multitts.tts.TTSLocalAudioCache
import com.readest.multitts.tts.SentenceSplitter
import com.readest.multitts.tts.TTSPlaybackListener
import java.io.File

interface PlaybackEventListener {
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onSentenceChanged(sentenceIndex: Int, text: String, isCached: Boolean)
    fun onChapterFinished(chapterIndex: Int)
    fun onPlaybackError(message: String)

    /** The service moved to the next chapter on its own; the reader should follow. */
    fun onChapterAdvanced(chapterIndex: Int)
}

class AudioPlaybackService : Service(), TTSPlaybackListener {

    companion object {
        private const val TAG = "AudioPlaybackService"
        const val CHANNEL_ID = "readest_multitts_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.readest.multitts.PLAY_PAUSE"
        const val ACTION_NEXT = "com.readest.multitts.NEXT"
        const val ACTION_PREV = "com.readest.multitts.PREV"
        const val ACTION_STOP = "com.readest.multitts.STOP"
    }

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null

    private lateinit var audioCache: TTSLocalAudioCache
    var ttsController: TTSEngineController? = null

    private var currentBook: Book? = null
    private var currentChapterIndex: Int = 0
    private var currentChapterTitle: String = ""
    private var totalChapters: Int = 0
    private var sentences: List<SentenceItem> = emptyList()
    private var currentIndex: Int = 0

    /** Full chapter list so narration can roll on without the reader UI. */
    private var chapters: List<Chapter> = emptyList()

    val chapterIndex: Int get() = currentChapterIndex

    /** The book this service is narrating, for restoring the reader from a tap. */
    val nowPlayingBook: Book? get() = currentBook
    val nowPlayingSentence: Int get() = currentIndex

    var isPlaying: Boolean = false
        private set

    var playbackEventListener: PlaybackEventListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepTimer: CountDownTimer? = null

    private var consecutiveTtsErrors = 0
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying) pausePlayback()
            }
        }
    }

    // Some engines (MultiTTS with online-only voices, offline) never deliver onDone/onError.
    // Without this watchdog, playback hangs forever on one sentence.
    private val ttsWatchdog = Runnable {
        Log.e(TAG, "TTS watchdog fired — engine produced no completion callback")
        onUtteranceError("watchdog", "TTS timed out")
    }

    private fun armTtsWatchdog(text: String) {
        mainHandler.removeCallbacks(ttsWatchdog)
        val timeoutMs = (8000L + text.length * 150L).coerceAtMost(45000L)
        mainHandler.postDelayed(ttsWatchdog, timeoutMs)
    }

    private fun cancelTtsWatchdog() {
        mainHandler.removeCallbacks(ttsWatchdog)
    }

    /** Give the service the whole book so it can advance chapters by itself. */
    fun setBook(book: Book, allChapters: List<Chapter>) {
        currentBook = book
        chapters = allChapters
        totalChapters = allChapters.size
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        audioCache = TTSLocalAudioCache(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        setupMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Headset / Bluetooth / watch transport buttons
        mediaSession?.let { androidx.media.session.MediaButtonReceiver.handleIntent(it, intent) }
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNextSentence()
            ACTION_PREV -> playPreviousSentence()
            ACTION_STOP -> pausePlayback()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Book Narration Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for Readest MultiTTS background narration"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ReadestMultiTTS").apply {
            // Filled in again on every metadata update so it always points at the
            // book currently playing
            setSessionActivity(openBookIntent())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (!isPlaying) togglePlayPause() }
                override fun onPause() { pausePlayback() }
                override fun onSkipToNext() { playNextSentence() }
                override fun onSkipToPrevious() { playPreviousSentence() }
                override fun onStop() { pausePlayback() }
                // The lock-screen scrubber addresses sentences, not milliseconds
                override fun onSeekTo(pos: Long) { playSentence(pos.toInt()) }
                override fun onFastForward() { playSentence((currentIndex + 5).coerceAtMost(sentences.size - 1)) }
                override fun onRewind() { playSentence((currentIndex - 5).coerceAtLeast(0)) }
            })
            isActive = true
        }
    }

    fun setPlaylist(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        items: List<SentenceItem>,
        startIndex: Int = 0,
        totalChapters: Int = this.totalChapters
    ) {
        currentBook = book
        currentChapterIndex = chapterIndex
        currentChapterTitle = chapterTitle
        sentences = items
        this.totalChapters = totalChapters
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        updateMetadata()
    }

    /**
     * Feeds the lock screen / Bluetooth / watch a real "now playing" card:
     * book as the track, chapter as the artist line, and chapter progress
     * expressed as sentences so the scrubber shows where you are.
     */
    private fun updateMetadata() {
        val book = currentBook ?: return
        val artwork = try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (e: Exception) {
            null
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentChapterTitle)
            .putString(
                MediaMetadataCompat.METADATA_KEY_ALBUM,
                if (totalChapters > 0) "Chapter ${currentChapterIndex + 1} of $totalChapters" else book.author
            )
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, book.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentChapterTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, sentences.size.toLong().coerceAtLeast(1L))
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, sentences.size.toLong())
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (currentIndex + 1).toLong())
            .apply {
                artwork?.let {
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                }
            }
            .build()

        mediaSession?.setMetadata(metadata)
        mediaSession?.setSessionActivity(openBookIntent())
    }

    fun playSentence(index: Int) {
        if (index !in sentences.indices) return
        currentIndex = index
        val item = sentences[index]
        val book = currentBook ?: return

        val voiceId = ttsController?.currentVoiceId ?: "default"

        // Cache is keyed at neutral 1.0/1.0 — playback speed is applied by MediaPlayer,
        // so changing the speed slider never invalidates cached audio.
        val cachedFile = audioCache.resolveForRead(
            bookId = book.id,
            chapterIndex = currentChapterIndex,
            sentenceIndex = item.index,
            voiceId = voiceId,
            rate = 1.0f,
            pitch = 1.0f,
            text = item.text
        )

        val isCached = cachedFile != null

        requestAudioFocus()
        isPlaying = true
        updateMetadata()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        updateNotification(item.text, isCached)

        mainHandler.post {
            playbackEventListener?.onSentenceChanged(item.index, item.text, isCached)
            playbackEventListener?.onPlaybackStateChanged(true)
        }

        if (cachedFile != null) {
            // PLAY VIA ZERO-CPU LOCAL MEDIA PLAYER (Save battery!)
            cancelTtsWatchdog()
            playCachedAudioFile(cachedFile)
        } else {
            // Synthesize and play via TTS Controller
            ttsController?.listener = this
            armTtsWatchdog(item.text)
            ttsController?.speak(item.text, "live_${book.id}_${currentChapterIndex}_${item.index}")
        }
    }

    private fun playCachedAudioFile(file: File) {
        try {
            consecutiveTtsErrors = 0
            stopCurrentAudio()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(applicationContext, Uri.fromFile(file))
                setOnCompletionListener {
                    onSentencePlaybackCompleted()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    // Fallback to TTS engine
                    ttsController?.speak(sentences[currentIndex].text, "fallback")
                    true
                }
                prepare()
                try {
                    // Apply the user's speed/pitch to cached audio (cache itself is neutral 1.0)
                    playbackParams = playbackParams
                        .setSpeed(ttsController?.currentRate ?: 1.0f)
                        .setPitch(ttsController?.currentPitch ?: 1.0f)
                } catch (e: Exception) {
                    Log.w(TAG, "Playback speed not supported for this file", e)
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing cached audio", e)
            ttsController?.speak(sentences[currentIndex].text, "fallback")
        }
    }

    private fun onSentencePlaybackCompleted() {
        if (!isPlaying) return
        if (currentIndex + 1 < sentences.size) {
            playSentence(currentIndex + 1)
            return
        }

        // Chapter finished. Roll into the next one here rather than relying on the
        // Activity, so listening continues even when the reader UI is gone.
        val next = currentChapterIndex + 1
        if (isPlaying && next < chapters.size) {
            val chapter = chapters[next]
            val nextSentences = SentenceSplitter.split(chapter)
            if (nextSentences.isNotEmpty()) {
                // Audible cue: a chapter just ended, another is starting. With the
                // screen off this is the only signal the listener gets.
                ChapterChime.playChapterEnd()
                currentChapterIndex = next
                currentChapterTitle = chapter.title
                sentences = nextSentences
                currentIndex = 0
                updateMetadata()
                updateNotification("${chapter.title} · Ch ${next + 1}", false)
                mainHandler.post { playbackEventListener?.onChapterAdvanced(next) }
                // Let the end cue finish, sound the start cue, then read on
                mainHandler.postDelayed({
                    if (!isPlaying) return@postDelayed
                    ChapterChime.playChapterStart()
                    mainHandler.postDelayed({ if (isPlaying) playSentence(0) }, 420)
                }, 500)
                return
            }
        }

        isPlaying = false
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        updateNotification("Finished", false)
        mainHandler.post {
            playbackEventListener?.onPlaybackStateChanged(false)
            playbackEventListener?.onChapterFinished(currentChapterIndex)
        }
    }

    fun togglePlayPause() {
        if (isPlaying) {
            pausePlayback()
        } else {
            if (sentences.isNotEmpty()) {
                playSentence(currentIndex)
            }
        }
    }

    fun pausePlayback() {
        isPlaying = false
        cancelTtsWatchdog()
        abandonAudioFocus()
        stopCurrentAudio()
        ttsController?.stop()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification(sentences.getOrNull(currentIndex)?.text ?: "Paused", false)
        mainHandler.post {
            playbackEventListener?.onPlaybackStateChanged(false)
        }
    }

    /** Move the play position without starting playback (used by the scrubber). */
    fun seekTo(index: Int) {
        if (index !in sentences.indices) return
        currentIndex = index
        updatePlaybackState(
            if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        )
    }

    fun playNextSentence() {
        if (currentIndex + 1 < sentences.size) {
            playSentence(currentIndex + 1)
        }
    }

    fun playPreviousSentence() {
        if (currentIndex - 1 >= 0) {
            playSentence(currentIndex - 1)
        }
    }

    private fun stopCurrentAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaPlayer = null
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sleepTimer?.cancel()
        if (minutes <= 0) return

        val millis = minutes * 60 * 1000L
        sleepTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                pausePlayback()
            }
        }.start()
    }

    private fun updatePlaybackState(state: Int) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_STOP
            )
            // Position is the sentence index; duration is the sentence count, so the
            // lock-screen scrubber reads as "where am I in this chapter".
            .setState(state, currentIndex.toLong(), if (isPlaying) ttsController?.currentRate ?: 1.0f else 0f)
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    /** Intent that reopens the reader on whatever is currently being narrated. */
    private fun openBookIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            currentBook?.let {
                putExtra(MainActivity.EXTRA_OPEN_BOOK_ID, it.id)
                putExtra(MainActivity.EXTRA_OPEN_CHAPTER, currentChapterIndex)
                putExtra(MainActivity.EXTRA_OPEN_SENTENCE, currentIndex)
            }
        }
        return PendingIntent.getActivity(
            this, 100, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlaybackService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updateNotification(sentenceText: String, isCached: Boolean) {
        val bookTitle = currentBook?.title ?: "Readest++"
        val chapterLine = if (totalChapters > 0) {
            "$currentChapterTitle · Ch ${currentChapterIndex + 1}/$totalChapters"
        } else currentChapterTitle
        val statusLine = buildString {
            append(if (isCached) "⚡ Offline audio" else "Live narration")
            if (sentences.isNotEmpty()) append(" · ${currentIndex + 1}/${sentences.size}")
        }

        val contentIntent = openBookIntent()

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val artwork = try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (e: Exception) {
            null
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book_audio)
            .setLargeIcon(artwork)
            .setContentTitle(bookTitle)
            .setContentText(chapterLine)
            .setSubText(statusLine)
            .setTicker(sentenceText.take(60))
            .setContentIntent(contentIntent)
            .setDeleteIntent(servicePendingIntent(4, ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, "Previous", servicePendingIntent(3, ACTION_PREV))
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", servicePendingIntent(1, ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next", servicePendingIntent(2, ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", servicePendingIntent(4, ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(servicePendingIntent(4, ACTION_STOP))
            )
            .setOngoing(isPlaying)
            .build()

        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // Paused: drop out of the foreground but leave the player card in the
            // shade so listening can be resumed from the lock screen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    // Note: only a successful completion resets the error counter — an engine can
    // report onStart and then stall, so starting is not proof of success.
    override fun onUtteranceStart(utteranceId: String) {}

    override fun onUtteranceDone(utteranceId: String) {
        cancelTtsWatchdog()
        consecutiveTtsErrors = 0
        onSentencePlaybackCompleted()
    }

    override fun onUtteranceError(utteranceId: String, errorMessage: String) {
        Log.e(TAG, "TTS utterance error: $errorMessage")
        cancelTtsWatchdog()
        consecutiveTtsErrors++
        if (consecutiveTtsErrors >= 3) {
            // Stop instead of silently skipping through the whole chapter (e.g. offline with an online-only voice)
            consecutiveTtsErrors = 0
            pausePlayback()
            mainHandler.post {
                playbackEventListener?.onPlaybackError(
                    "语音合成失败 TTS failed repeatedly — check the MultiTTS engine, voice pack, or network. Cached chapters still play offline."
                )
            }
        } else {
            onSentencePlaybackCompleted()
        }
    }

    override fun onRangeStart(utteranceId: String, start: Int, end: Int) {}

    override fun onDestroy() {
        stopCurrentAudio()
        abandonAudioFocus()
        ttsController?.stop()
        mediaSession?.release()
        sleepTimer?.cancel()
        super.onDestroy()
    }
}
