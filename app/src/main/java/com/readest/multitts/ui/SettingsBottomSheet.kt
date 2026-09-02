package com.readest.multitts.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.readest.multitts.databinding.BottomSheetSettingsBinding
import com.readest.multitts.databinding.ItemSettingsRowBinding

/**
 * One place to reach every setting.
 *
 * The individual panels stay where they are — this is a hub that opens them, not
 * a replacement, so a setting is never in two places with two states. Each row
 * carries its current value in the subtitle so the sheet answers "what is it set
 * to?" without opening anything.
 */
class SettingsBottomSheet(
    private val summary: Summary,
    private val onOpenVoice: () -> Unit,
    private val onOpenEngine: () -> Unit,
    private val onOpenCache: () -> Unit,
    private val onOpenDictionaries: () -> Unit,
    private val onOpenTranslate: () -> Unit,
    private val onOpenDisplay: () -> Unit,
    private val onOpenAppTheme: () -> Unit,
    private val onOpenShelfOrder: () -> Unit,
    private val onCheckUpdate: () -> Unit,
    private val onOpenReleases: () -> Unit
) : BottomSheetDialogFragment() {

    /** Everything the rows show, gathered by the caller which owns the state. */
    data class Summary(
        val engineLabel: String,
        val engineInstalled: Boolean,
        val voice: String,
        val rate: String,
        val cache: String,
        val dictionaries: String,
        val translateTarget: String,
        val display: String,
        val shelfOrder: String,
        val version: String,
        val appTheme: String,
        val voiceRowLabel: String,
        val offlineRowLabel: String,
        val displayRowLabel: String,
        val shelfRowLabel: String,
        val title: String,
        /** Level, streak and badge count, or null in a theme without them. */
        val stats: Triple<Int, Int, Int>?
    )

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.chipSettingsEngine.text = if (summary.engineInstalled) "MultiTTS ✓" else "System TTS"
        binding.tvSettingsTitle.text = summary.title
        binding.tvSettingsSubtitle.text = "Readest++ ${summary.version}"

        summary.stats?.let { (level, streak, badges) ->
            binding.statStrip.visibility = View.VISIBLE
            binding.tvStatLevel.text = level.toString()
            binding.tvStatStreak.text = streak.toString()
            binding.tvStatBadges.text = badges.toString()
        }

        row(binding.rowVoice, "🔊", summary.voiceRowLabel, "${summary.voice} · ${summary.rate}", onOpenVoice)
        row(binding.rowEngine, "★", "Speech engine", summary.engineLabel, onOpenEngine)
        row(binding.rowCache, "⚡", summary.offlineRowLabel, summary.cache, onOpenCache)

        row(binding.rowDictionaries, "📚", "Dictionaries", summary.dictionaries, onOpenDictionaries)
        row(binding.rowTranslate, "🌐", "Translation language", summary.translateTarget, onOpenTranslate)

        row(binding.rowAppTheme, "🎮", "App theme · 主题", summary.appTheme, onOpenAppTheme)
        row(binding.rowDisplay, "🎨", summary.displayRowLabel, summary.display, onOpenDisplay)
        row(binding.rowShelfOrder, "🗂", summary.shelfRowLabel, summary.shelfOrder, onOpenShelfOrder)

        row(binding.rowUpdate, "⬆️", "Check for updates", summary.version, onCheckUpdate)
        row(binding.rowReleases, "🔗", "Releases on GitHub", "Release notes and older builds", onOpenReleases)

        ClickFeedback.applyToTree(view)
    }

    /** Dismisses first so the panel it opens is the only sheet on screen. */
    private fun row(
        binding: ItemSettingsRowBinding,
        icon: String,
        title: String,
        subtitle: String,
        action: () -> Unit
    ) {
        binding.tvRowIcon.text = icon
        binding.tvRowTitle.text = title
        binding.tvRowSubtitle.text = subtitle
        binding.root.setOnClickListener {
            dismiss()
            action()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
