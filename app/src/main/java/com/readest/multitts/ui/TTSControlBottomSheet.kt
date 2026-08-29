package com.readest.multitts.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.readest.multitts.R
import com.readest.multitts.databinding.BottomSheetTtsBinding
import com.readest.multitts.model.Book
import com.readest.multitts.model.Chapter
import com.readest.multitts.model.SentenceItem
import com.readest.multitts.model.TTSEngineInfo
import com.readest.multitts.model.TTSVoiceInfo
import com.readest.multitts.reader.LanguageDetector
import com.readest.multitts.tts.MultiTTSManager
import com.readest.multitts.tts.PreSynthesisProgressListener
import com.readest.multitts.tts.TTSEngineController
import com.readest.multitts.tts.TTSLocalAudioCache
import com.readest.multitts.tts.CacheCheckpoint
import com.readest.multitts.tts.CacheCheckpointStore
import com.readest.multitts.tts.CacheService
import com.readest.multitts.tts.TTSPreSynthesizer
import java.util.Locale

class TTSControlBottomSheet(
    private val ttsController: TTSEngineController,
    private val audioCache: TTSLocalAudioCache,
    private val preSynthesizer: TTSPreSynthesizer,
    private val currentBook: Book?,
    private val allChapters: List<Chapter>,
    private val currentChapterIndex: Int,
    private val currentSentences: List<SentenceItem>,
    private val onEngineChanged: (String?) -> Unit,
    private val onVoiceChanged: (String) -> Unit,
    private val onRateChanged: (Float) -> Unit,
    private val onPitchChanged: (Float) -> Unit,
    private val onSleepTimerChanged: (Int) -> Unit,
    private val keepAwakeEnabled: Boolean = true,
    private val onKeepAwakeChanged: (Boolean) -> Unit = {},
    private val onCachingActiveChanged: (Boolean) -> Unit = {},
    private val onManageCache: () -> Unit = {},
    private val checkpoints: CacheCheckpointStore? = null,
    private val savedLanguage: String = "auto",
    private val savedWholeBookScope: Boolean = false,
    private val savedSleepTimer: Int = 0,
    private val onLanguageChanged: (String) -> Unit = {},
    private val onScopeChanged: (Boolean) -> Unit = {}
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTtsBinding? = null
    private val binding get() = _binding!!

    private var engines: List<TTSEngineInfo> = emptyList()
    private var allVoices: List<TTSVoiceInfo> = emptyList()
    private var filteredVoices: List<TTSVoiceInfo> = emptyList()
    private var selectedLanguageTag: String = savedLanguage
    private var pendingCheckpoint: CacheCheckpoint? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetTtsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupLanguageSpinner()
        setupEngines()
        setupVoices()
        setupSliders()
        setupCacheSection()
        setupSleepTimer()
        ClickFeedback.applyToTree(view)

        binding.btnMultiTtsGuide.setOnClickListener {
            val dialog = MultiTTSDownloadDialog(requireContext()) {
                setupEngines()
            }
            dialog.show()
        }
    }

    private fun setupLanguageSpinner() {
        val detected = if (currentSentences.isNotEmpty()) {
            LanguageDetector.detectLanguage(currentSentences.take(5).joinToString(" ") { it.text })
        } else {
            Locale.SIMPLIFIED_CHINESE
        }

        val langOptions = listOf(
            "⚡ Auto-Detect (${detected.displayLanguage})",
            "Chinese (中文 / zh)",
            "English (English / en)",
            "Japanese (日本語 / ja)",
            "German (Deutsch / de)",
            "French (Français / fr)",
            "Spanish (Español / es)",
            "Russian (Русский / ru)",
            "All Available Languages"
        )

        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_dark, langOptions).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        }
        binding.spinnerLanguage.adapter = adapter
        binding.spinnerLanguage.setSelection(
            when (savedLanguage) {
                "zh" -> 1; "en" -> 2; "ja" -> 3; "de" -> 4
                "fr" -> 5; "es" -> 6; "ru" -> 7; "all" -> 8
                else -> 0
            }
        )

        var initialLanguageCallback = true
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initialLanguageCallback) {
                    // Restoring the saved language, not a choice — the voice list is
                    // already being built by onViewCreated
                    initialLanguageCallback = false
                    return
                }
                selectedLanguageTag = when (position) {
                    0 -> detected.language
                    1 -> "zh"
                    2 -> "en"
                    3 -> "ja"
                    4 -> "de"
                    5 -> "fr"
                    6 -> "es"
                    7 -> "ru"
                    else -> "all"
                }
                onLanguageChanged(if (position == 0) "auto" else selectedLanguageTag)
                setupVoices()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupEngines() {
        val available = MultiTTSManager.getAvailableTTSEngines(requireContext(), null)
        engines = available
        val engineLabels = engines.map {
            if (it.isMultiTts) "★ MultiTTS Engine" else it.label
        }

        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_dark, engineLabels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        }
        binding.spinnerTtsEngine.adapter = adapter

        val currentPkg = ttsController.currentEnginePackage
        val selectedIdx = engines.indexOfFirst { it.packageName == currentPkg }.coerceAtLeast(0)
        binding.spinnerTtsEngine.setSelection(selectedIdx)

        binding.spinnerTtsEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedEngine = engines.getOrNull(position)
                if (selectedEngine != null && selectedEngine.packageName != ttsController.currentEnginePackage) {
                    onEngineChanged(selectedEngine.packageName)
                    setupVoices()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupVoices() {
        allVoices = ttsController.getVoices()
        filteredVoices = if (selectedLanguageTag == "all") {
            allVoices
        } else {
            val matching = allVoices.filter {
                it.locale.language == selectedLanguageTag || it.id.lowercase().contains(selectedLanguageTag)
            }
            if (matching.isNotEmpty()) matching else allVoices
        }

        binding.btnVoicePicker.text = currentVoiceLabel()
        binding.btnVoicePicker.setOnClickListener {
            // Every voice is reachable here, unlike the dropdown that ran off-screen
            val picker = VoicePickerBottomSheet(
                voices = allVoices,
                currentVoiceId = ttsController.currentVoiceId
            ) { voice ->
                onVoiceChanged(voice.id)
                binding.btnVoicePicker.text = voice.name
            }
            picker.show(parentFragmentManager, "VoicePickerBottomSheet")
        }
    }

    private fun currentVoiceLabel(): String {
        val id = ttsController.currentVoiceId
        val match = allVoices.firstOrNull { it.id == id }
            ?: allVoices.firstOrNull { id != null && it.name.contains(id) }
        return match?.name ?: (id ?: "Default voice")
    }

    private fun setupSliders() {
        binding.sliderSpeechRate.value = ttsController.currentRate
        binding.tvSheetRateVal.text = String.format(Locale.US, "%.1fx", ttsController.currentRate)
        binding.sliderSpeechRate.addOnChangeListener { _, value, _ ->
            binding.tvSheetRateVal.text = String.format(Locale.US, "%.1fx", value)
            onRateChanged(value)
        }

        binding.sliderSpeechPitch.value = ttsController.currentPitch
        binding.tvSheetPitchVal.text = String.format(Locale.US, "%.1f", ttsController.currentPitch)
        binding.sliderSpeechPitch.addOnChangeListener { _, value, _ ->
            binding.tvSheetPitchVal.text = String.format(Locale.US, "%.1f", value)
            onPitchChanged(value)
        }
    }

    private fun setupCacheSection() {
        binding.tvCacheStats.text = audioCache.getFormattedCacheSize() + " cached"

        binding.rbCacheBook.isChecked = savedWholeBookScope
        binding.rbCacheChapter.isChecked = !savedWholeBookScope
        binding.rgCacheScope.setOnCheckedChangeListener { _, checkedId ->
            onScopeChanged(checkedId == binding.rbCacheBook.id)
        }

        refreshResumeHint()

        binding.btnRestartCache.setOnClickListener {
            currentBook?.let { checkpoints?.clear(it.id) }
            pendingCheckpoint = null
            refreshResumeHint()
        }

        binding.switchKeepAwake.isChecked = keepAwakeEnabled
        binding.switchKeepAwake.setOnCheckedChangeListener { _, checked ->
            onKeepAwakeChanged(checked)
        }

        binding.btnPauseCache.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            CacheService.send(
                ctx,
                if (CacheService.state.paused) CacheService.ACTION_RESUME else CacheService.ACTION_PAUSE
            )
        }

        binding.btnStopCache.setOnClickListener {
            context?.let { CacheService.send(it, CacheService.ACTION_STOP) }
        }

        binding.btnPreCacheChapter.setOnClickListener {
            val book = currentBook
            val ctx = context
            if (book == null || ctx == null) {
                Toast.makeText(context, "No book loaded to cache", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isWholeBook = binding.rbCacheBook.isChecked
            // Resume only when the saved position matches the scope and voice in use
            val canResume = pendingCheckpoint?.let {
                it.wholeBook == isWholeBook && it.voiceId == (ttsController.currentVoiceId ?: "default")
            } ?: false

            CacheService.start(ctx, book.id, isWholeBook, currentChapterIndex, canResume)
            binding.pbPrecacheProgress.visibility = View.VISIBLE
            binding.tvPrecacheStatus.visibility = View.VISIBLE
            binding.tvPrecacheStatus.text = "Starting…"
            showCachingControls(true)
        }

        binding.btnClearCache.setOnClickListener {
            onManageCache()
        }

        // Reflect a job that is already running (started before this sheet opened)
        CacheService.addListener(cacheStateListener)
    }

    /** Mirrors the service's progress into the sheet. */
    private val cacheStateListener: (CacheService.Companion.State) -> Unit = { s ->
        if (_binding != null) {
            showCachingControls(s.running)
            onCachingActiveChanged(s.running && !s.paused)

            if (s.running) {
                binding.pbPrecacheProgress.visibility = View.VISIBLE
                binding.tvPrecacheStatus.visibility = View.VISIBLE
                binding.pbPrecacheProgress.progress = s.percent
                binding.tvPrecacheStatus.text = when {
                    s.paused -> "Paused · 已暂停 (${s.processed}/${s.total})"
                    // Offline voices manage ~0.1s; anything far above that is the
                    // voice waiting on the network, not the app being slow
                    s.secondsPerItem > 0.6 && s.total > 0 ->
                        "Caching ${s.processed} / ${s.total} (${s.percent}%) · " +
                            "${"%.1f".format(s.secondsPerItem)}s per sentence\n" +
                            "⚠ This voice needs the network. An offline voice caches ~10× faster."
                    s.total > 0 -> "Caching ${s.processed} / ${s.total} (${s.percent}%)\n${s.currentText}"
                    else -> s.message ?: "Preparing…"
                }
            } else {
                binding.pbPrecacheProgress.visibility = View.GONE
                val note = s.error ?: s.message
                if (note != null) {
                    binding.tvPrecacheStatus.visibility = View.VISIBLE
                    binding.tvPrecacheStatus.text = note
                }
                binding.tvCacheStats.text = audioCache.getFormattedCacheSize() + " cached"
                refreshResumeHint()
            }
        }
    }

    /** Shows where a previous run stopped, and turns Start into Resume. */
    private fun refreshResumeHint() {
        if (_binding == null) return
        val book = currentBook
        val checkpoint = if (book != null) checkpoints?.get(book.id) else null
        pendingCheckpoint = checkpoint

        if (checkpoint == null || checkpoint.processed >= checkpoint.total) {
            binding.tvResumeHint.visibility = View.GONE
            binding.btnRestartCache.visibility = View.GONE
            binding.btnPreCacheChapter.text = "Start pre-cache · 开始缓存"
            return
        }

        val scopeLabel = if (checkpoint.wholeBook) "whole book 全书" else "chapter 本章"
        binding.tvResumeHint.visibility = View.VISIBLE
        binding.tvResumeHint.text =
            "⏸ Unfinished $scopeLabel cache · Ch ${checkpoint.chapterIndex + 1}, " +
                "${checkpoint.processed}/${checkpoint.total} (${checkpoint.percent}%)"
        binding.btnRestartCache.visibility = View.VISIBLE
        // Keep it short: three buttons share this row and the banner already shows progress
        binding.btnPreCacheChapter.text = "Resume 继续"
    }

    private fun showCachingControls(active: Boolean) {
        if (_binding == null) return
        // Hide rather than disable: four buttons in one row squeezes them all
        binding.btnPreCacheChapter.visibility = if (active) View.GONE else View.VISIBLE
        binding.btnPauseCache.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnStopCache.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnPauseCache.text = if (CacheService.state.paused) "Resume 继续" else "Pause 暂停"
        binding.btnRestartCache.visibility =
            if (!active && pendingCheckpoint != null) View.VISIBLE else View.GONE
        // The "unfinished run" banner describes the saved position, not the live one
        if (active) binding.tvResumeHint.visibility = View.GONE
    }

    private fun setupSleepTimer() {
        val timerOptions = listOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes")
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_dark, timerOptions).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        }
        binding.spinnerSleepTimer.adapter = adapter
        binding.spinnerSleepTimer.setSelection(
            when (savedSleepTimer) { 15 -> 1; 30 -> 2; 45 -> 3; 60 -> 4; else -> 0 }
        )

        var isInitialTimerSelection = true
        binding.spinnerSleepTimer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Skip the automatic first callback so opening the sheet doesn't cancel a running timer
                if (isInitialTimerSelection) {
                    isInitialTimerSelection = false
                    return
                }
                val minutes = when (position) {
                    1 -> 15
                    2 -> 30
                    3 -> 45
                    4 -> 60
                    else -> 0
                }
                onSleepTimerChanged(minutes)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        CacheService.removeListener(cacheStateListener)
        _binding = null
    }
}
