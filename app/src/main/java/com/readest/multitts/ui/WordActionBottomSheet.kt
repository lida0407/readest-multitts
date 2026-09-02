package com.readest.multitts.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.readest.multitts.databinding.BottomSheetWordBinding
import com.readest.multitts.dict.DictionaryHtml
import com.readest.multitts.dict.DictionaryStore
import com.readest.multitts.dict.MobiDictionary
import com.readest.multitts.dict.Translator

/**
 * What a long-press on a word offers: hear it, look it up in an installed
 * dictionary, or translate it.
 *
 * Both lookups run off the main thread — a dictionary read seeks into a large
 * file and a translation crosses the network, and neither should stall a page turn.
 */
class WordActionBottomSheet(
    private val word: String,
    private val sentenceIndex: Int,
    private val sentenceText: String,
    private val store: DictionaryStore,
    private val defaultTarget: String,
    /** The eyebrow above the word — the theme decides what a look-up is worth. */
    private val foundLabel: String,
    private val onLookedUp: () -> Unit,
    private val onTargetChanged: (String) -> Unit,
    private val onSpeak: (String) -> Unit,
    private val onReadFromHere: (Int) -> Unit,
    private val onManageDictionaries: () -> Unit,
    private val onDismissed: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetWordBinding? = null
    private val binding get() = _binding!!

    private var mode = MODE_DICTIONARY
    private var target = defaultTarget
    private var hits: List<Pair<DictionaryStore.Installed, MobiDictionary.Definition>> = emptyList()
    private var selectedHit = 0
    private var lastSpoken: String = word
    private var worker: Thread? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetWordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.62f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvWord.text = word
        binding.tvWordEyebrow.text = foundLabel
        onLookedUp()

        binding.btnSpeakWord.setOnClickListener { onSpeak(lastSpoken) }
        binding.btnCopyWord.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("word", word))
            Toast.makeText(context, "Copied “$word”", Toast.LENGTH_SHORT).show()
        }
        binding.btnReadFromHere.setOnClickListener {
            onReadFromHere(sentenceIndex)
            dismiss()
        }

        binding.tgWordMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == binding.btnModeTranslate.id) MODE_TRANSLATE else MODE_DICTIONARY
            refreshChips()
            run()
        }

        // Start on whichever side can actually answer: with no dictionary
        // installed, opening on an empty Dictionary tab is just a dead end.
        mode = if (store.list().any { it.enabled }) MODE_DICTIONARY else MODE_TRANSLATE
        binding.tgWordMode.check(
            if (mode == MODE_TRANSLATE) binding.btnModeTranslate.id else binding.btnModeDictionary.id
        )
        refreshChips()
        run()
        ClickFeedback.applyToTree(view)
    }

    // ------------------------------------------------------------------ chips

    private fun refreshChips() {
        val group = binding.chipsWordSource
        group.removeAllViews()

        if (mode == MODE_TRANSLATE) {
            binding.btnWordSecondary.text = "Open Translate app"
            binding.btnWordSecondary.setOnClickListener {
                if (!Translator.openExternal(requireContext(), word, target)) {
                    Toast.makeText(context, "No translation app on this phone", Toast.LENGTH_SHORT).show()
                }
            }
            Translator.COMMON_TARGETS.forEach { (code, label) ->
                group.addView(chip(label, code == target) {
                    target = code
                    onTargetChanged(code)
                    refreshChips()
                    run()
                })
            }
        } else {
            binding.btnWordSecondary.text = "Dictionaries"
            binding.btnWordSecondary.setOnClickListener {
                onManageDictionaries()
                dismiss()
            }
            hits.forEachIndexed { index, (installed, _) ->
                group.addView(chip(installed.name, index == selectedHit) {
                    selectedHit = index
                    refreshChips()
                    showHit()
                })
            }
            binding.scrollWordChips.visibility = if (hits.size > 1) View.VISIBLE else View.GONE
            return
        }
        binding.scrollWordChips.visibility = View.VISIBLE
    }

    private fun chip(label: String, checked: Boolean, onClick: () -> Unit): Chip =
        Chip(requireContext()).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------- lookups

    private fun run() {
        worker = null
        setBusy(true)
        val currentMode = mode
        val currentTarget = target
        val thread = Thread {
            if (currentMode == MODE_DICTIONARY) {
                val found = store.lookup(word)
                post {
                    // Ignore anything that finished after the user switched tabs.
                    if (mode != currentMode) return@post
                    hits = found
                    selectedHit = 0
                    setBusy(false)
                    refreshChips()
                    showHit()
                }
            } else {
                val result = Translator.translate(word, currentTarget)
                post {
                    if (mode != currentMode || target != currentTarget) return@post
                    setBusy(false)
                    when (result) {
                        is Translator.Result.Ok -> {
                            val t = result.translation
                            lastSpoken = t.text
                            val source = t.sourceLanguage?.let { " · detected $it" } ?: ""
                            val roman = t.romanization?.let { "<br><small>$it</small>" } ?: ""
                            setHtml("<b>${escape(t.text)}</b>$roman<br><br><small>via Google$source</small>")
                        }
                        is Translator.Result.Failed ->
                            setHtml(
                                "Couldn't translate: ${escape(result.reason)}<br><br>" +
                                    "<small>Translation needs a network. The Open Translate app button below " +
                                    "works with an installed app.</small>"
                            )
                    }
                }
            }
        }
        worker = thread
        thread.start()
    }

    private fun showHit() {
        if (hits.isEmpty()) {
            val installed = store.list()
            lastSpoken = word
            setHtml(
                if (installed.isEmpty())
                    "No dictionary installed yet.<br><br><small>Tap <b>Dictionaries</b> below to add a " +
                        "MOBI or PRC dictionary file, or switch to <b>Translate</b>.</small>"
                else
                    "“${escape(word)}” isn't in ${if (installed.size == 1) "your dictionary" else "your dictionaries"}."
            )
            return
        }
        val (source, definition) = hits[selectedHit.coerceIn(0, hits.size - 1)]
        lastSpoken = DictionaryHtml.toPlainText(definition.html).ifBlank { word }
        setHtml(
            "<small>${escape(source.name)}</small><br><br>" +
                DictionaryHtml.toDisplayHtml(definition.html)
        )
    }

    private fun setHtml(html: String) {
        if (_binding == null) return
        binding.tvWordResult.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    }

    private fun setBusy(busy: Boolean) {
        if (_binding == null) return
        binding.pbWord.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) binding.tvWordResult.text = ""
    }

    private fun post(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        onDismissed()
    }

    companion object {
        private const val MODE_DICTIONARY = 0
        private const val MODE_TRANSLATE = 1
    }
}
