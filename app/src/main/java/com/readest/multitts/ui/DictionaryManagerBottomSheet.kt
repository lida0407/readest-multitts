package com.readest.multitts.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.readest.multitts.databinding.BottomSheetDictionariesBinding
import com.readest.multitts.databinding.ItemDictionaryBinding
import com.readest.multitts.dict.DictionaryStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Add, enable and remove dictionary files.
 *
 * Importing is deliberately visible: indexing a large dictionary takes a while,
 * and a silent spinner would look like the app had hung.
 */
class DictionaryManagerBottomSheet(
    private val store: DictionaryStore,
    private val onPickFile: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDictionariesBinding? = null
    private val binding get() = _binding!!
    private var importing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetDictionariesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.7f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvDictionaries.layoutManager = LinearLayoutManager(requireContext())
        binding.btnAddDictionary.setOnClickListener { onPickFile() }
        reload()
        ClickFeedback.applyToTree(view)
    }

    fun reload() {
        if (_binding == null) return
        val items = store.list()
        binding.tvDictEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.tvDictSummary.text = when {
            items.isEmpty() -> "Add a MOBI or PRC dictionary to look words up offline."
            else -> "${items.size} installed · ${items.sumOf { it.entries }} entries"
        }
        binding.rvDictionaries.adapter = Adapter(items)
    }

    /** Called by the host once the file picker comes back. */
    fun startImport(uri: Uri, displayName: String) {
        if (importing) {
            Toast.makeText(context, "An import is already running", Toast.LENGTH_SHORT).show()
            return
        }
        importing = true
        showProgress(true, "Copying…")

        Thread {
            try {
                val installed = store.install(uri, displayName) { message ->
                    post { binding.tvDictStatus.text = message }
                }
                post {
                    importing = false
                    showProgress(false, null)
                    reload()
                    Toast.makeText(
                        context,
                        "Added ${installed.name} · ${installed.entries} entries",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                post {
                    importing = false
                    showProgress(false, null)
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Couldn't add that dictionary")
                        .setMessage(e.message ?: "Unknown problem")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showProgress(active: Boolean, status: String?) {
        if (_binding == null) return
        binding.pbDictImport.visibility = if (active) View.VISIBLE else View.GONE
        binding.tvDictStatus.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnAddDictionary.isEnabled = !active
        if (status != null) binding.tvDictStatus.text = status
    }

    private fun post(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class Adapter(private val items: List<DictionaryStore.Installed>) :
        RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(val binding: ItemDictionaryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemDictionaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.tvDictName.text = item.name
            holder.binding.tvDictMeta.text =
                "${item.entries} entries · ${formatBytes(item.bytes)}"
            holder.binding.swDictEnabled.setOnCheckedChangeListener(null)
            holder.binding.swDictEnabled.isChecked = item.enabled
            holder.binding.swDictEnabled.setOnCheckedChangeListener { _, checked ->
                store.setEnabled(item.id, checked)
            }
            holder.binding.btnDeleteDict.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete ${item.name}?")
                    .setMessage("Removes the dictionary file and its index, freeing ${formatBytes(item.bytes)}.")
                    .setPositiveButton("Delete") { _, _ ->
                        store.delete(item.id)
                        reload()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.0f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.0f KB", bytes / 1024.0)
    }
}
