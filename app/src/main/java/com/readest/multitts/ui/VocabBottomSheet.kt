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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.readest.multitts.databinding.BottomSheetVocabBinding
import com.readest.multitts.databinding.ItemVocabBinding
import com.readest.multitts.vocab.VocabStore
import java.text.DateFormat
import java.util.Date

/**
 * The words you looked up, newest first.
 *
 * Kept deliberately plain: this is a record to skim and export, not a drill.
 * Spaced repetition belongs in a tool built for it, which is why the export
 * writes Anki's format rather than growing a scheduler here.
 */
class VocabBottomSheet(
    private val store: VocabStore,
    private val onSpeak: (String) -> Unit,
    private val onExport: (VocabStore.Format) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVocabBinding? = null
    private val binding get() = _binding!!

    private var shown: List<VocabStore.Entry> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetVocabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.85f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvVocab.layoutManager = LinearLayoutManager(requireContext())

        binding.etVocabSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = reload(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        binding.btnVocabExport.setOnClickListener { askExportFormat() }
        reload("")
        ClickFeedback.applyToTree(view)
    }

    private fun askExportFormat() {
        if (store.count() == 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export ${store.count()} words")
            .setItems(
                arrayOf(
                    "Spreadsheet (.csv)",
                    "Anki (.txt, tab-separated)"
                )
            ) { _, which ->
                onExport(if (which == 0) VocabStore.Format.CSV else VocabStore.Format.ANKI)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun reload(query: String = binding.etVocabSearch.text.toString()) {
        if (_binding == null) return
        shown = store.search(query)
        val total = store.count()
        binding.tvVocabSummary.text = when {
            total == 0 -> "Nothing collected yet"
            query.isBlank() -> "$total word${if (total == 1) "" else "s"} collected"
            else -> "${shown.size} of $total"
        }
        binding.tvVocabEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.tvVocabEmpty.text =
            if (total > 0 && shown.isEmpty()) "Nothing matches “$query”."
            else getString(com.readest.multitts.R.string.vocab_empty)
        binding.rvVocab.adapter = Adapter()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val binding: ItemVocabBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemVocabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = shown[position]
            val b = holder.binding
            b.tvVocabWord.text = entry.word
            b.tvVocabGloss.text = entry.gloss
            b.tvVocabCount.text = if (entry.lookups > 1) "×${entry.lookups}" else ""

            b.tvVocabSentence.visibility = if (entry.sentence.isBlank()) View.GONE else View.VISIBLE
            b.tvVocabSentence.text = "“${entry.sentence}”"

            val seen = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.lastSeen))
            b.tvVocabMeta.text = listOfNotNull(entry.bookTitle, entry.source, seen)
                .joinToString(" · ")

            b.btnVocabSpeak.setOnClickListener { onSpeak(entry.word) }
            b.btnVocabDelete.setOnClickListener {
                store.remove(entry.word)
                reload()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
