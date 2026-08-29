package com.readest.multitts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.readest.multitts.databinding.ActivityMainBinding
import com.readest.multitts.model.Book
import com.readest.multitts.model.Bookmark
import com.readest.multitts.model.BookRepository
import com.readest.multitts.model.BookmarkRepository
import com.readest.multitts.model.Chapter
import com.readest.multitts.model.SentenceItem
import com.readest.multitts.playback.AudioPlaybackService
import com.readest.multitts.playback.PlaybackEventListener
import com.readest.multitts.reader.DocumentManager
import com.readest.multitts.reader.PdfParser
import com.readest.multitts.reader.ReaderBridge
import com.readest.multitts.reader.ReaderBridgeListener
import com.readest.multitts.tts.CacheCheckpointStore
import com.readest.multitts.tts.MultiTTSManager
import com.readest.multitts.tts.TTSEngineController
import com.readest.multitts.tts.TTSLocalAudioCache
import com.readest.multitts.tts.TTSPreSynthesizer
import com.readest.multitts.tts.TtsEngine
import com.readest.multitts.ui.BooksAdapter
import com.readest.multitts.ui.CacheManagerBottomSheet
import com.readest.multitts.ui.ClickFeedback
import com.readest.multitts.ui.ContentsBottomSheet
import com.readest.multitts.ui.MultiTTSDownloadDialog
import com.readest.multitts.ui.ReaderSettingsBottomSheet
import com.readest.multitts.ui.TTSControlBottomSheet
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), ReaderBridgeListener, PlaybackEventListener {

    companion object {
        const val EXTRA_OPEN_BOOK_ID = "open_book_id"
        const val EXTRA_OPEN_CHAPTER = "open_chapter"
        const val EXTRA_OPEN_SENTENCE = "open_sentence"

        private const val SORT_LAST_READ = "last_read"
        private const val SORT_TITLE = "title"
        private const val SORT_ADDED = "added"
    }

    private lateinit var binding: ActivityMainBinding

    private lateinit var bookRepository: BookRepository
    private lateinit var bookmarkRepository: BookmarkRepository
    private lateinit var ttsController: TTSEngineController
    private lateinit var audioCache: TTSLocalAudioCache
    private lateinit var preSynthesizer: TTSPreSynthesizer
    private lateinit var checkpointStore: CacheCheckpointStore
    private lateinit var booksAdapter: BooksAdapter

    private var playbackService: AudioPlaybackService? = null
    private var isServiceBound = false

    private var currentBook: Book? = null
    private var chaptersList: List<Chapter> = emptyList()
    private var currentChapterIndex = 0
    private var currentSentences: List<SentenceItem> = emptyList()

    private var currentFontSize = 19
    private var currentTheme = "theme-light"
    private var currentReadingMode = "paginated"
    private var areToolbarsVisible = true
    private var pendingStartSentence = 0
    private var visibleSentenceIndex = 0
    private var enteringChapterBackwards = false
    private var isScrubbing = false
    private var keepScreenOnWhileCaching = true
    private var librarySort = SORT_LAST_READ

    private val prefs by lazy { getSharedPreferences("reader_settings", MODE_PRIVATE) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.LocalBinder
            playbackService = binder.getService().apply {
                ttsController = this@MainActivity.ttsController
                playbackEventListener = this@MainActivity
                // A book may already be open by the time the binding lands
                currentBook?.let { book ->
                    if (chaptersList.isNotEmpty()) setBook(book, chaptersList)
                }
            }
            isServiceBound = true

            // Opened while something is being narrated: show that book, not the shelf
            val playing = playbackService?.nowPlayingBook
            if (currentBook == null && playing != null && playbackService?.isPlaying == true) {
                openSavedBook(
                    playing.copy(
                        currentChapterIndex = playbackService?.chapterIndex ?: playing.currentChapterIndex,
                        currentSentenceIndex = playbackService?.nowPlayingSentence ?: 0
                    )
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importBookFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The bottom sheets are constructor-injected fragments; they cannot be restored
        // by the framework after process death, so drop any saved fragment state.
        savedInstanceState?.remove("android:support:fragments")
        savedInstanceState?.remove("android:fragments")
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PdfParser.init(this)
        bookRepository = BookRepository(this)
        bookmarkRepository = BookmarkRepository(this)
        audioCache = TTSLocalAudioCache(this)
        ttsController = TtsEngine.get(this)
        TtsEngine.activityAttached = true
        preSynthesizer = TTSPreSynthesizer(ttsController, audioCache)
        checkpointStore = CacheCheckpointStore(this)

        currentFontSize = prefs.getInt("font_size", 19)
        currentTheme = prefs.getString("theme", "theme-light") ?: "theme-light"
        currentReadingMode = prefs.getString("reading_mode", "paginated") ?: "paginated"
        keepScreenOnWhileCaching = prefs.getBoolean("keep_awake_caching", true)
        librarySort = prefs.getString("library_sort", SORT_LAST_READ) ?: SORT_LAST_READ

        setupLibraryRecyclerView()
        setupTTS()
        setupWebView()
        setupButtons()
        startPlaybackService()

        showLibraryView()
        refreshShelfTitles()
        handleIncomingIntent(intent)
    }

    /**
     * Books imported before metadata parsing show their download file name
     * ("1787386369102___z-lib.org"). Adopt the real titles in the background.
     */
    private fun refreshShelfTitles() {
        Thread {
            var changed = false
            for (book in bookRepository.getAllBooks()) {
                val refreshed = DocumentManager.refreshedTitleFor(File(book.filePath), book.title) ?: continue
                bookRepository.saveBook(
                    book.copy(title = refreshed.first, author = refreshed.second ?: book.author)
                )
                changed = true
            }
            if (changed) runOnUiThread { refreshLibraryView() }
        }.start()
    }

    private fun setupLibraryRecyclerView() {
        booksAdapter = BooksAdapter(
            books = emptyList(),
            onBookClicked = { book ->
                openSavedBook(book)
            },
            onBookDelete = { book ->
                bookRepository.deleteBook(book.id)
                audioCache.clearBookCache(book.id)
                bookmarkRepository.removeForBook(book.id)
                checkpointStore.clear(book.id)
                refreshLibraryView()
                Toast.makeText(this, "Deleted ${book.title}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvRecentBooks.layoutManager = LinearLayoutManager(this)
        binding.rvRecentBooks.adapter = booksAdapter
    }

    private fun sortLabel(): String = when (librarySort) {
        SORT_TITLE -> "Title A–Z 书名 ▾"
        SORT_ADDED -> "Recently added 最近添加 ▾"
        else -> "Last read 最近阅读 ▾"
    }

    private fun sortedBooks(books: List<Book>): List<Book> = when (librarySort) {
        // Locale-aware so Chinese titles sort sensibly rather than by code point
        SORT_TITLE -> books.sortedWith(compareBy(java.text.Collator.getInstance()) { it.title })
        SORT_ADDED -> books // manifest order: newest import first
        else -> books.sortedByDescending { it.lastReadTimestamp }
    }

    private fun showSortMenu() {
        val menu = android.widget.PopupMenu(this, binding.btnSortBooks)
        menu.menu.add(0, 0, 0, "Last read · 最近阅读")
        menu.menu.add(0, 1, 1, "Title A–Z · 书名")
        menu.menu.add(0, 2, 2, "Recently added · 最近添加")
        menu.setOnMenuItemClickListener { item ->
            librarySort = when (item.itemId) {
                1 -> SORT_TITLE
                2 -> SORT_ADDED
                else -> SORT_LAST_READ
            }
            prefs.edit().putString("library_sort", librarySort).apply()
            refreshLibraryView()
            true
        }
        menu.show()
    }

    private fun refreshLibraryView() {
        val books = sortedBooks(bookRepository.getAllBooks())
        binding.btnSortBooks.text = sortLabel()
        if (books.isNotEmpty()) {
            binding.rvRecentBooks.visibility = View.VISIBLE
            binding.tvEmptyShelf.visibility = View.GONE
            booksAdapter.updateData(books)
        } else {
            binding.rvRecentBooks.visibility = View.GONE
            binding.tvEmptyShelf.visibility = View.VISIBLE
        }
        binding.tvShelfLabel.text = "MY SHELF 我的书架 · ${books.size}"
    }

    private fun setupTTS() {
        val multiPkg = MultiTTSManager.getInstalledMultiTTSPackage(this)
        val isMultiInstalled = multiPkg != null

        val statusText = if (isMultiInstalled) "MultiTTS ✓" else "Get MultiTTS"
        binding.chipMultiTtsStatus.text = statusText
        binding.btnHeroMultiTts.text = statusText

        // Restore the voice setup from last time rather than resetting to defaults
        val savedEngine = prefs.getString("tts_engine", null)
        val savedVoice = prefs.getString("tts_voice", null)
        ttsController.currentRate = prefs.getFloat("tts_rate", 1.0f)
        ttsController.currentPitch = prefs.getFloat("tts_pitch", 1.0f)

        val applySavedVoice = {
            ttsController.setSpeechRate(ttsController.currentRate)
            ttsController.setSpeechPitch(ttsController.currentPitch)
            savedVoice?.let { ttsController.setVoice(it) }
            binding.tvTtsSpeed.text = String.format(Locale.US, "%.1fx", ttsController.currentRate)
        }

        val engineToUse = savedEngine ?: multiPkg
        ttsController.initEngine(engineToUse) { ready ->
            if (ready) {
                applySavedVoice()
            } else if (engineToUse != multiPkg) {
                // The remembered engine is gone (uninstalled); fall back instead of staying mute
                prefs.edit().remove("tts_engine").apply()
                ttsController.initEngine(multiPkg) { fallbackReady ->
                    if (fallbackReady) applySavedVoice()
                }
            }
        }
    }

    private fun setupWebView() {
        binding.readerWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        binding.readerWebView.addJavascriptInterface(ReaderBridge(this), "AndroidBridge")
        binding.readerWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (chaptersList.isNotEmpty()) {
                    loadCurrentChapterIntoWebView()
                }
            }
        }

        binding.readerWebView.loadUrl("file:///android_asset/reader/index.html")
    }

    private fun setupButtons() {
        binding.btnImportBook.setOnClickListener {
            openFilePicker()
        }

        binding.btnMainImport.setOnClickListener {
            openFilePicker()
        }

        binding.chipMultiTtsStatus.setOnClickListener {
            val dialog = MultiTTSDownloadDialog(this) {
                setupTTS()
            }
            dialog.show()
        }

        binding.chipCachedPlayback.setOnClickListener {
            showCacheManager()
        }

        binding.btnSortBooks.setOnClickListener {
            showSortMenu()
        }

        binding.btnHeroMultiTts.setOnClickListener {
            val dialog = MultiTTSDownloadDialog(this) {
                setupTTS()
            }
            dialog.show()
        }

        binding.tvTtsSpeed.setOnClickListener {
            val presets = listOf(1.0f, 1.2f, 1.5f, 2.0f, 0.8f)
            val next = presets[(presets.indexOfFirst { kotlin.math.abs(it - ttsController.currentRate) < 0.05f } + 1)
                .coerceAtLeast(0) % presets.size]
            ttsController.setSpeechRate(next)
            prefs.edit().putFloat("tts_rate", next).apply()
            binding.tvTtsSpeed.text = String.format(Locale.US, "%.1fx", next)
        }

        binding.btnReaderSettings.setOnClickListener {
            showReaderSettingsBottomSheet()
        }

        binding.btnTtsMenu.setOnClickListener {
            showTtsBottomSheet()
        }

        binding.btnTtsPanelExpand.setOnClickListener {
            showTtsBottomSheet()
        }

        binding.btnBack.setOnClickListener {
            showLibraryView()
        }

        binding.btnContents.setOnClickListener {
            showContentsSheet(startOnBookmarks = false)
        }

        binding.btnBookmark.setOnClickListener {
            toggleBookmarkAtCurrentPosition()
        }

        binding.btnBookmark.setOnLongClickListener {
            showContentsSheet(startOnBookmarks = true)
            true
        }

        binding.fabTtsPlayPause.setOnClickListener {
            playbackService?.togglePlayPause()
        }

        binding.btnTtsNext.setOnClickListener {
            playbackService?.playNextSentence()
        }

        binding.btnTtsPrev.setOnClickListener {
            playbackService?.playPreviousSentence()
        }

        setupChapterScrubber()

        // Give every control visible press feedback (the scrubber drags, so skip it)
        ClickFeedback.applyToTree(binding.root)
    }

    /**
     * The mini-player bar is a chapter scrubber: drag to jump to any sentence,
     * with a second track showing how much of the chapter is cached offline.
     */
    private fun setupChapterScrubber() {
        binding.sliderTtsProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = sentenceIndexForScrub(progress)
                binding.tvScrubberLabel.text = scrubberLabel(target)
                currentSentences.getOrNull(target)?.let {
                    binding.tvCurrentSentence.text = it.text
                }
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar) {
                isScrubbing = true
            }

            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                isScrubbing = false
                val target = sentenceIndexForScrub(bar.progress)
                if (currentSentences.isEmpty()) return
                val wasPlaying = playbackService?.isPlaying == true
                if (wasPlaying) {
                    playbackService?.playSentence(target)
                } else {
                    // Not playing: just move the reader there
                    playbackService?.seekTo(target)
                    binding.readerWebView.evaluateJavascript("ReaderApp.goToSentence($target)", null)
                    binding.tvCurrentSentence.text = currentSentences.getOrNull(target)?.text ?: ""
                }
            }
        })
    }

    private fun sentenceIndexForScrub(progress: Int): Int {
        val count = currentSentences.size
        if (count <= 1) return 0
        return ((progress / 1000f) * (count - 1)).toInt().coerceIn(0, count - 1)
    }

    private fun scrubberLabel(sentenceIndex: Int): String {
        val chapter = chaptersList.getOrNull(currentChapterIndex)?.title ?: ""
        return if (currentSentences.isEmpty()) chapter
        else "$chapter · sentence ${sentenceIndex + 1}/${currentSentences.size}"
    }

    private fun updateScrubber(sentenceIndex: Int) {
        if (isScrubbing) return
        val count = currentSentences.size
        binding.sliderTtsProgress.progress =
            if (count <= 1) 0 else (sentenceIndex * 1000 / (count - 1)).coerceIn(0, 1000)
        binding.tvScrubberLabel.text = scrubberLabel(sentenceIndex)
    }

    /** Fills the secondary track with the share of this chapter already cached. */
    private fun refreshCachedAhead() {
        val book = currentBook ?: return
        val sentences = currentSentences
        val chapterIndex = currentChapterIndex
        if (sentences.isEmpty()) return
        Thread {
            val voice = ttsController.currentVoiceId ?: "default"
            val cached = sentences.count { item ->
                audioCache.isCached(book.id, chapterIndex, item.index, voice, 1.0f, 1.0f, item.text)
            }
            val percent = cached * 1000 / sentences.size
            runOnUiThread {
                if (currentChapterIndex == chapterIndex) {
                    binding.sliderTtsProgress.secondaryProgress = percent
                }
            }
        }.start()
    }

    private fun openFilePicker() {
        val mimeTypes = arrayOf(
            "text/plain",
            "application/epub+zip",
            "application/pdf",
            "application/x-mobipocket-ebook",
            "application/octet-stream",
            "*/*"
        )
        filePickerLauncher.launch(mimeTypes)
    }

    private fun startPlaybackService() {
        val intent = Intent(this, AudioPlaybackService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        // Coming from the lock screen / media notification: reopen that book
        val bookId = intent?.getStringExtra(EXTRA_OPEN_BOOK_ID)
        if (bookId != null) {
            intent.removeExtra(EXTRA_OPEN_BOOK_ID)
            val book = bookRepository.getAllBooks().firstOrNull { it.id == bookId }
            if (book != null) {
                if (currentBook?.id == book.id && chaptersList.isNotEmpty()) {
                    showReaderView()
                } else {
                    val chapter = intent.getIntExtra(EXTRA_OPEN_CHAPTER, book.currentChapterIndex)
                    val sentence = intent.getIntExtra(EXTRA_OPEN_SENTENCE, book.currentSentenceIndex)
                    openSavedBook(book.copy(currentChapterIndex = chapter, currentSentenceIndex = sentence))
                }
                return
            }
        }

        val dataUri = intent?.data ?: intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        dataUri?.let { importBookFromUri(it) }
    }

    private fun importBookFromUri(uri: Uri) {
        try {
            val fileName = getFileName(uri) ?: "imported_book_${System.currentTimeMillis()}"
            val persistentFile = bookRepository.getPersistentFileForBook(fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(persistentFile).use { output ->
                    input.copyTo(output)
                }
            }

            val (book, chapters) = DocumentManager.loadBookCached(this, persistentFile)

            // Re-importing the same file should reopen it, not add a second shelf entry
            val duplicate = bookRepository.findDuplicate(book.title, book.format, persistentFile.length())
            if (duplicate != null && duplicate.filePath != persistentFile.absolutePath) {
                persistentFile.delete()
                Toast.makeText(this, "Already in your library: ${duplicate.title}", Toast.LENGTH_SHORT).show()
                openSavedBook(duplicate)
                return
            }

            bookRepository.saveBook(book)
            refreshLibraryView()

            openLoadedBook(book, chapters, 0)
            Toast.makeText(this, "Added to Library: ${book.title}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load document: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSavedBook(book: Book) {
        val file = File(book.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "File not found on device", Toast.LENGTH_SHORT).show()
            return
        }

        // Parsing a long book takes seconds; do it off the main thread so the app
        // stays responsive instead of freezing on the shelf.
        showOpeningOverlay(true, book.title)
        Thread {
            try {
                val (parsed, chapters) = DocumentManager.loadBookCached(this, file)
                runOnUiThread {
                    showOpeningOverlay(false, null)

                    var current = book
                    if (parsed.title != book.title || chapters.size != book.totalChapters) {
                        current = book.copy(
                            title = parsed.title,
                            author = parsed.author,
                            totalChapters = chapters.size
                        )
                        bookRepository.saveBook(current)
                        refreshLibraryView()
                    }

                    applyVoiceForBook(current)
                    pendingStartSentence = current.currentSentenceIndex.coerceAtLeast(0)
                    openLoadedBook(current, chapters, current.currentChapterIndex)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    showOpeningOverlay(false, null)
                    Toast.makeText(this, "Error opening book: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showOpeningOverlay(visible: Boolean, title: String?) {
        binding.openingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && title != null) {
            binding.tvOpeningLabel.text = "Opening ${title.take(40)}…"
        }
    }

    /**
     * Cached audio is keyed by voice, so a book should reopen with the voice its
     * clips were made with — otherwise everything silently re-synthesizes.
     */
    private fun applyVoiceForBook(book: Book) {
        val remembered = prefs.getString(bookVoiceKey(book.id), null)
            ?: checkpointStore.get(book.id)?.voiceId
            ?: return
        if (remembered == ttsController.currentVoiceId) return
        ttsController.setVoice(remembered)
        prefs.edit().putString("tts_voice", remembered).apply()
    }

    private fun bookVoiceKey(bookId: String) = "book_voice_$bookId"

    private fun openLoadedBook(book: Book, chapters: List<Chapter>, startChapter: Int) {
        currentBook = book
        chaptersList = chapters
        currentChapterIndex = startChapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))

        playbackService?.setBook(book, chapters)
        showReaderView()
        loadCurrentChapterIntoWebView()
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) name = it.getString(idx)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) name = name?.substring(cut + 1)
        }
        return name
    }

    private fun showReaderView() {
        binding.libraryContainer.visibility = View.GONE
        binding.readerWebView.visibility = View.VISIBLE
        binding.appBarLayout.visibility = View.VISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnReaderSettings.visibility = View.VISIBLE
        binding.btnContents.visibility = View.VISIBLE
        binding.btnBookmark.visibility = View.VISIBLE
        binding.btnImportBook.visibility = View.GONE
        binding.chipMultiTtsStatus.visibility = View.GONE
        binding.ttsMiniPlayerCard.visibility = View.VISIBLE
        areToolbarsVisible = true

        binding.tvAppTitle.text = currentBook?.title ?: "Readest++"
        binding.tvSubtitle.text = "${currentBook?.format} · Ch ${currentChapterIndex + 1}/${chaptersList.size}"
    }

    private fun showLibraryView() {
        binding.readerWebView.visibility = View.GONE
        binding.libraryContainer.visibility = View.VISIBLE
        binding.appBarLayout.visibility = View.GONE
        binding.btnBack.visibility = View.GONE
        binding.btnReaderSettings.visibility = View.GONE
        binding.btnContents.visibility = View.GONE
        binding.btnBookmark.visibility = View.GONE
        binding.btnImportBook.visibility = View.VISIBLE
        binding.chipMultiTtsStatus.visibility = View.VISIBLE
        binding.ttsMiniPlayerCard.visibility = View.GONE

        binding.tvAppTitle.text = "Readest++"
        binding.tvSubtitle.text = "MultiTTS 离线朗读"
        playbackService?.pausePlayback()
        refreshLibraryView()
    }

    private fun loadCurrentChapterIntoWebView() {
        if (chaptersList.isEmpty() || currentChapterIndex !in chaptersList.indices) return
        val chapter = chaptersList[currentChapterIndex]

        // Persist reading progress (keep the resumed sentence position instead of resetting it)
        currentBook?.let { book ->
            bookRepository.updateProgress(book.id, currentChapterIndex, pendingStartSentence)
        }

        val payload = mapOf(
            "bookTitle" to (currentBook?.title ?: ""),
            "chapterIndex" to currentChapterIndex,
            "chapterTitle" to chapter.title,
            "totalChapters" to chaptersList.size,
            "paragraphs" to chapter.paragraphs,
            // Flipping backwards should open the previous chapter at its last page
            "landOnLastPage" to enteringChapterBackwards
        )
        enteringChapterBackwards = false

        val json = Gson().toJson(payload)
        runOnUiThread {
            applyReaderSettings()
            binding.readerWebView.evaluateJavascript("ReaderApp.loadChapterData($json)", null)
            binding.tvSubtitle.text = "${currentBook?.format} · ${chapter.title} (${currentChapterIndex + 1}/${chaptersList.size})"
        }
    }

    private fun applyReaderSettings() {
        binding.readerWebView.evaluateJavascript("ReaderApp.setTheme('$currentTheme')", null)
        binding.readerWebView.evaluateJavascript("ReaderApp.setFontSize($currentFontSize)", null)
        binding.readerWebView.evaluateJavascript("ReaderApp.setReadingMode('$currentReadingMode')", null)
        pushReaderBottomInset()
    }

    // Keep the web reader's page area clear of the floating mini player
    private fun pushReaderBottomInset() {
        binding.ttsMiniPlayerCard.post {
            val visible = binding.ttsMiniPlayerCard.visibility == View.VISIBLE
            val density = resources.displayMetrics.density
            val insetCssPx = if (visible) {
                ((binding.ttsMiniPlayerCard.height + 24 * density) / density).toInt()
            } else 0
            binding.readerWebView.evaluateJavascript("ReaderApp.setBottomInset($insetCssPx)", null)
        }
    }

    // ---- Contents & bookmarks -------------------------------------------------

    private fun showContentsSheet(startOnBookmarks: Boolean) {
        val book = currentBook ?: return
        val sheet = ContentsBottomSheet(
            chapters = chaptersList,
            currentChapterIndex = currentChapterIndex,
            bookmarks = bookmarkRepository.getForBook(book.id),
            startOnBookmarks = startOnBookmarks,
            onChapterSelected = { index -> jumpToChapter(index, 0) },
            onBookmarkSelected = { bm -> jumpToChapter(bm.chapterIndex, bm.sentenceIndex) },
            onBookmarkDeleted = { bm ->
                bookmarkRepository.remove(bm.id)
                refreshBookmarkIcon()
            }
        )
        sheet.show(supportFragmentManager, "ContentsBottomSheet")
    }

    private fun jumpToChapter(chapterIndex: Int, sentenceIndex: Int) {
        if (chapterIndex !in chaptersList.indices) return
        pendingStartSentence = sentenceIndex
        if (chapterIndex == currentChapterIndex) {
            binding.readerWebView.evaluateJavascript("ReaderApp.goToSentence($sentenceIndex)", null)
            pendingStartSentence = 0
            refreshBookmarkIcon()
        } else {
            currentChapterIndex = chapterIndex
            loadCurrentChapterIntoWebView()
        }
    }

    private fun toggleBookmarkAtCurrentPosition() {
        val book = currentBook ?: return
        val chapterTitle = chaptersList.getOrNull(currentChapterIndex)?.title ?: "Chapter ${currentChapterIndex + 1}"
        val existing = bookmarkRepository.find(book.id, currentChapterIndex, visibleSentenceIndex)

        if (existing != null) {
            bookmarkRepository.remove(existing.id)
            Toast.makeText(this, "Bookmark removed · 已删除书签", Toast.LENGTH_SHORT).show()
        } else {
            val excerpt = currentSentences.getOrNull(visibleSentenceIndex)?.text ?: chapterTitle
            bookmarkRepository.add(book.id, currentChapterIndex, chapterTitle, visibleSentenceIndex, excerpt)
            Toast.makeText(this, "Bookmarked · 已添加书签", Toast.LENGTH_SHORT).show()
        }
        refreshBookmarkIcon()
    }

    private fun refreshBookmarkIcon() {
        val book = currentBook ?: return
        val marked = bookmarkRepository.find(book.id, currentChapterIndex, visibleSentenceIndex) != null
        binding.btnBookmark.setImageResource(
            if (marked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
        )
        binding.btnBookmark.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (marked) R.color.accent else R.color.text_secondary
            )
        )
    }

    /**
     * Cached audio is keyed by voice, so switching voices makes an existing cache
     * unusable for playback. Say so rather than silently re-synthesizing everything.
     */
    private fun warnIfCacheBelongsToAnotherVoice(previousVoice: String?, newVoice: String) {
        val book = currentBook ?: return
        if (previousVoice == null || previousVoice == newVoice) return
        Thread {
            val hasCache = audioCache.listBookCaches().any { it.bookId == book.id && it.bytes > 0 }
            if (hasCache) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Offline audio for this book was cached with the previous voice — it will re-synthesize with the new one.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun showCacheManager() {
        val sheet = CacheManagerBottomSheet(
            audioCache = audioCache,
            bookRepository = bookRepository,
            // The cache key includes the voice, so the manager needs the candidates
            // to work out which voice a book was cached with.
            voiceCandidates = buildList {
                ttsController.currentVoiceId?.let { add(it) }
                addAll(ttsController.getVoices().map { it.id })
                add("default")
            }
        )
        sheet.show(supportFragmentManager, "CacheManagerBottomSheet")
    }

    private fun applyKeepScreenOn(active: Boolean) {
        if (active && keepScreenOnWhileCaching) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showReaderSettingsBottomSheet() {
        val sheet = ReaderSettingsBottomSheet(
            currentFontSize = currentFontSize,
            currentTheme = currentTheme,
            currentMode = currentReadingMode,
            onThemeSelected = { themeName ->
                currentTheme = themeName
                prefs.edit().putString("theme", themeName).apply()
                binding.readerWebView.evaluateJavascript("ReaderApp.setTheme('$themeName')", null)
            },
            onFontSizeChanged = { sizePx ->
                currentFontSize = sizePx
                prefs.edit().putInt("font_size", sizePx).apply()
                binding.readerWebView.evaluateJavascript("ReaderApp.setFontSize($sizePx)", null)
            },
            onModeChanged = { mode ->
                currentReadingMode = mode
                prefs.edit().putString("reading_mode", mode).apply()
                binding.readerWebView.evaluateJavascript("ReaderApp.setReadingMode('$mode')", null)
            }
        )
        sheet.show(supportFragmentManager, "ReaderSettingsBottomSheet")
    }

    private fun showTtsBottomSheet() {
        val sheet = TTSControlBottomSheet(
            ttsController = ttsController,
            audioCache = audioCache,
            preSynthesizer = preSynthesizer,
            currentBook = currentBook,
            allChapters = chaptersList,
            currentChapterIndex = currentChapterIndex,
            currentSentences = currentSentences,
            onEngineChanged = { pkg ->
                prefs.edit().putString("tts_engine", pkg).apply()
                ttsController.initEngine(pkg) { ready ->
                    if (ready) {
                        ttsController.setSpeechRate(ttsController.currentRate)
                        ttsController.setSpeechPitch(ttsController.currentPitch)
                    }
                }
            },
            onVoiceChanged = { voiceId ->
                val previous = prefs.getString("tts_voice", null)
                prefs.edit().putString("tts_voice", voiceId).apply()
                // Tie the choice to this book so reopening it keeps its cache usable
                currentBook?.let { prefs.edit().putString(bookVoiceKey(it.id), voiceId).apply() }
                ttsController.setVoice(voiceId)
                warnIfCacheBelongsToAnotherVoice(previous, voiceId)
            },
            onRateChanged = { rate ->
                prefs.edit().putFloat("tts_rate", rate).apply()
                ttsController.setSpeechRate(rate)
                binding.tvTtsSpeed.text = String.format(Locale.US, "%.1fx", rate)
            },
            onPitchChanged = { pitch ->
                prefs.edit().putFloat("tts_pitch", pitch).apply()
                ttsController.setSpeechPitch(pitch)
            },
            onSleepTimerChanged = { minutes ->
                prefs.edit().putInt("sleep_timer", minutes).apply()
                playbackService?.setSleepTimerMinutes(minutes)
                if (minutes > 0) {
                    Toast.makeText(this, "Sleep timer set for $minutes minutes", Toast.LENGTH_SHORT).show()
                }
            },
            keepAwakeEnabled = keepScreenOnWhileCaching,
            onKeepAwakeChanged = { enabled ->
                keepScreenOnWhileCaching = enabled
                prefs.edit().putBoolean("keep_awake_caching", enabled).apply()
                applyKeepScreenOn(preSynthesizer.isRunning() && !preSynthesizer.isPaused())
            },
            onCachingActiveChanged = { active -> applyKeepScreenOn(active) },
            onManageCache = { showCacheManager() },
            checkpoints = checkpointStore,
            savedLanguage = prefs.getString("tts_language", "auto") ?: "auto",
            savedWholeBookScope = prefs.getBoolean("cache_scope_whole", false),
            savedSleepTimer = prefs.getInt("sleep_timer", 0),
            onLanguageChanged = { tag -> prefs.edit().putString("tts_language", tag).apply() },
            onScopeChanged = { whole -> prefs.edit().putBoolean("cache_scope_whole", whole).apply() }
        )
        sheet.show(supportFragmentManager, "TTSControlBottomSheet")
    }

    // Reader Bridge Callbacks
    override fun onReaderReady() {
        if (chaptersList.isNotEmpty()) {
            runOnUiThread { loadCurrentChapterIntoWebView() }
        }
    }

    override fun onChapterLoaded(chapterIndex: Int, chapterTitle: String, sentences: List<String>) {
        val items = sentences.mapIndexed { idx, s ->
            SentenceItem(
                index = idx,
                text = s,
                paragraphIndex = 0
            )
        }
        currentSentences = items

        val startIndex = pendingStartSentence.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val service = playbackService
        if (service != null && service.isPlaying && service.chapterIndex == chapterIndex) {
            // The service is already narrating this chapter — don't reset its playlist
            pendingStartSentence = 0
            return
        }
        currentBook?.let { book ->
            service?.setPlaylist(book, chapterIndex, chapterTitle, items, startIndex, chaptersList.size)
        }

        if (startIndex > 0) {
            runOnUiThread {
                // Scroll to where reading stopped and flash it briefly. The persistent
                // karaoke highlight is reserved for audio that is actually playing.
                binding.readerWebView.evaluateJavascript("ReaderApp.goToSentence($startIndex)", null)
            }
        }
        pendingStartSentence = 0
        runOnUiThread {
            updateScrubber(startIndex)
            refreshCachedAhead()
        }
    }

    override fun onChapterAdvanced(chapterIndex: Int) {
        runOnUiThread {
            // Audio already moved on; bring the page with it
            currentChapterIndex = chapterIndex
            loadCurrentChapterIntoWebView()
        }
    }

    override fun onSentenceClicked(sentenceIndex: Int, text: String) {
        playbackService?.playSentence(sentenceIndex)
    }

    override fun requestNextChapter() {
        runOnUiThread {
            if (currentChapterIndex + 1 < chaptersList.size) {
                currentChapterIndex++
                loadCurrentChapterIntoWebView()
            } else {
                Toast.makeText(this, "Reached end of book", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun requestPrevChapter() {
        runOnUiThread {
            if (currentChapterIndex - 1 >= 0) {
                enteringChapterBackwards = true
                currentChapterIndex--
                loadCurrentChapterIntoWebView()
            } else {
                Toast.makeText(this, "At first chapter", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, firstVisibleSentence: Int) {
        visibleSentenceIndex = firstVisibleSentence
        runOnUiThread {
            refreshBookmarkIcon()
            // Remember where the eye is, not just where narration is
            currentBook?.let { book ->
                bookRepository.updateProgress(book.id, currentChapterIndex, firstVisibleSentence)
            }
        }
    }

    override fun toggleToolbars() {
        runOnUiThread {
            areToolbarsVisible = !areToolbarsVisible
            binding.appBarLayout.visibility = if (areToolbarsVisible) View.VISIBLE else View.GONE
            binding.ttsMiniPlayerCard.visibility = if (areToolbarsVisible) View.VISIBLE else View.GONE
            pushReaderBottomInset()
        }
    }

    // Playback Event Callbacks
    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            binding.fabTtsPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }
    }

    override fun onSentenceChanged(sentenceIndex: Int, text: String, isCached: Boolean) {
        runOnUiThread {
            binding.tvCurrentSentence.text = text
            updateScrubber(sentenceIndex)
            binding.tvAudioSourceBadge.visibility = View.VISIBLE
            if (isCached) {
                binding.tvAudioSourceBadge.text = "⚡ Cached · 0% CPU"
                binding.tvAudioSourceBadge.setBackgroundResource(R.drawable.bg_pill_green_soft)
                binding.tvAudioSourceBadge.setTextColor(0xFF059669.toInt())
            } else {
                binding.tvAudioSourceBadge.text = "Synthesizing"
                binding.tvAudioSourceBadge.setBackgroundResource(R.drawable.bg_chip_speed)
                binding.tvAudioSourceBadge.setTextColor(0xFF2563EB.toInt())
            }

            currentBook?.let { book ->
                bookRepository.updateProgress(book.id, currentChapterIndex, sentenceIndex)
            }

            // Highlight in WebView
            binding.readerWebView.evaluateJavascript("ReaderApp.highlightSentence($sentenceIndex)", null)
        }
    }

    override fun onPlaybackError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onChapterFinished(chapterIndex: Int) {
        runOnUiThread {
            if (currentChapterIndex + 1 >= chaptersList.size) {
                Toast.makeText(this, "Finished the book · 全书朗读完毕", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Sentence-level progress is throttled in memory; make sure it reaches disk
        bookRepository.flush()
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        preSynthesizer.cancel()
        bookRepository.flush()
        // The caching service may still be using the shared engine
        TtsEngine.activityAttached = false
        TtsEngine.releaseIfIdle()
        super.onDestroy()
    }
}
