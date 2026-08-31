package com.readest.multitts.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.readest.multitts.R
import com.readest.multitts.databinding.BottomSheetReaderSettingsBinding

class ReaderSettingsBottomSheet(
    private var currentFontSize: Int = 19,
    private var currentTheme: String = "theme-light",
    private var currentMode: String = "paginated",
    private val onThemeSelected: (String) -> Unit,
    private val onFontSizeChanged: (Int) -> Unit,
    private val onModeChanged: (String) -> Unit,
    private val onOpenDictionaries: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetReaderSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetReaderSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val themeButtons = mapOf(
            "theme-dark" to binding.btnThemeDark,
            "theme-oled" to binding.btnThemeOled,
            "theme-light" to binding.btnThemeLight,
            "theme-sepia" to binding.btnThemeSepia,
            "theme-mint" to binding.btnThemeMint
        )

        fun updateSelection() {
            val accent = ContextCompat.getColor(requireContext(), R.color.accent)
            themeButtons.forEach { (name, card: MaterialCardView) ->
                card.strokeColor = if (name == currentTheme) accent else 0x00000000
            }
        }
        updateSelection()

        themeButtons.forEach { (name, card) ->
            card.setOnClickListener {
                currentTheme = name
                updateSelection()
                onThemeSelected(name)
            }
        }

        binding.sliderFontSize.value = currentFontSize.toFloat().coerceIn(14f, 32f)
        binding.tvFontSizeVal.text = "$currentFontSize px"
        binding.sliderFontSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            binding.tvFontSizeVal.text = "$size px"
            onFontSizeChanged(size)
        }

        binding.btnOpenDictionaries.setOnClickListener {
            onOpenDictionaries()
            dismiss()
        }

        binding.rgReadingMode.check(
            if (currentMode == "scroll") binding.rbScroll.id else binding.rbPaginated.id
        )
        ClickFeedback.applyToTree(view)

        binding.rgReadingMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == binding.rbScroll.id) "scroll" else "paginated"
            if (mode != currentMode) {
                currentMode = mode
                onModeChanged(mode)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
