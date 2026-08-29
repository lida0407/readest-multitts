package com.readest.multitts.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.readest.multitts.databinding.BottomSheetVoicePickerBinding
import com.readest.multitts.databinding.ItemVoiceRowBinding
import com.readest.multitts.model.TTSVoiceInfo

/**
 * Full-height voice list.
 *
 * The dropdown this replaces was anchored to a control near the bottom of the sheet,
 * so with dozens of installed voices most of them sat below the screen edge and could
 * not be reached. A list also removes the dropdown's stray selection callbacks, which
 * had been silently changing the saved voice.
 */
class VoicePickerBottomSheet(
    private val voices: List<TTSVoiceInfo>,
    private val currentVoiceId: String?,
    private val onPicked: (TTSVoiceInfo) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVoicePickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetVoicePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.88f).toInt()
    }

    private var sourceFilter: String = ALL_SOURCES
    private var searchQuery: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvVoices.layoutManager = LinearLayoutManager(requireContext())
        buildSourceTabs()
        applyFilters()

        binding.etVoiceSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim()?.lowercase().orEmpty()
                applyFilters()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /** One tab per backend actually present on the device, plus All. */
    private fun buildSourceTabs() {
        val sources = listOf(ALL_SOURCES) +
            voices.map { sourceOf(it) }.distinct().sorted()

        binding.chipsVoiceSource.removeAllViews()
        for (source in sources) {
            val count = if (source == ALL_SOURCES) voices.size
            else voices.count { sourceOf(it) == source }
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = "$source ($count)"
                isCheckable = true
                isChecked = source == sourceFilter
                setOnClickListener {
                    sourceFilter = source
                    applyFilters()
                }
            }
            binding.chipsVoiceSource.addView(chip)
        }
    }

    private fun applyFilters() {
        var list = voices
        if (sourceFilter != ALL_SOURCES) list = list.filter { sourceOf(it) == sourceFilter }
        if (searchQuery.isNotEmpty()) {
            list = list.filter {
                it.name.lowercase().contains(searchQuery) || it.id.lowercase().contains(searchQuery)
            }
        }
        render(list)
    }

    private fun render(list: List<TTSVoiceInfo>) {
        binding.tvVoiceCount.text =
            if (list.isEmpty()) "No voices match"
            else "${list.size} voices · offline ones work in airplane mode"
        binding.rvVoices.adapter = VoiceAdapter(list, currentVoiceId) { voice ->
            onPicked(voice)
            dismiss()
        }
        // Open on the current voice rather than at the top of a long list
        val index = list.indexOfFirst { it.id == currentVoiceId }
        if (index > 0) {
            binding.rvVoices.post {
                (binding.rvVoices.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(index, 120)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ALL_SOURCES = "All"

        /** MultiTTS prefixes its voice ids with the backend that provides them. */
        fun sourceOf(voice: TTSVoiceInfo): String = when {
            voice.id.startsWith("microsoft_") -> "Microsoft"
            voice.id.startsWith("isstts_") -> "IssTTS"
            voice.id.startsWith("sherpa_") -> "Sherpa"
            voice.id.startsWith("gemini_") -> "Gemini"
            voice.id.startsWith("google_") -> "Google"
            voice.id.startsWith("edge_") -> "Edge"
            voice.name.contains("IssTTS") -> "IssTTS"
            voice.name.contains("Sherpa") -> "Sherpa"
            voice.name.contains("Microsoft") -> "Microsoft"
            else -> "Other"
        }
    }

    private class VoiceAdapter(
        private val items: List<TTSVoiceInfo>,
        private val currentVoiceId: String?,
        private val onClick: (TTSVoiceInfo) -> Unit
    ) : RecyclerView.Adapter<VoiceAdapter.Holder>() {

        class Holder(val binding: ItemVoiceRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemVoiceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val voice = items[position]
            val selected = voice.id == currentVoiceId

            holder.binding.tvVoiceName.text = voice.name
            holder.binding.tvVoiceMeta.text = "${sourceOf(voice)} · ${voice.locale.displayLanguage}"
            holder.binding.tvVoiceOffline.visibility =
                if (voice.isOffline) View.VISIBLE else View.GONE
            holder.binding.tvVoiceCheck.visibility =
                if (selected) View.VISIBLE else View.INVISIBLE
            holder.binding.tvVoiceName.setTypeface(
                null,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            holder.itemView.setOnClickListener { onClick(voice) }
        }

        override fun getItemCount(): Int = items.size
    }
}
