package com.readest.multitts.dict

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The dictionaries a reader has installed.
 *
 * Files are copied into app storage rather than read through their original URI:
 * a document permission can be revoked at any time, and a dictionary that stops
 * working a week after it was added is worse than one that never worked.
 */
class DictionaryStore(private val context: Context) {

    data class Installed(
        val id: String,
        val name: String,
        val fileName: String,
        val entries: Int,
        val bytes: Long,
        var enabled: Boolean
    )

    private val dir = File(context.filesDir, "dictionaries").apply { mkdirs() }
    private val manifest = File(dir, "dictionaries.json")

    private val open = HashMap<String, MobiDictionary>()

    fun list(): List<Installed> {
        if (!manifest.exists()) return emptyList()
        return try {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val id = o.optString("id")
                if (id.isEmpty() || !bookFile(id).exists()) null
                else Installed(
                    id = id,
                    name = o.optString("name", id),
                    fileName = o.optString("fileName", ""),
                    entries = o.optInt("entries"),
                    bytes = bookFile(id).length(),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dictionary manifest unreadable", e)
            emptyList()
        }
    }

    private fun save(items: List<Installed>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("fileName", it.fileName)
                    .put("entries", it.entries)
                    .put("enabled", it.enabled)
            )
        }
        manifest.writeText(array.toString())
    }

    fun bookFile(id: String) = File(dir, "$id.mobi")
    fun indexFile(id: String) = File(dir, "$id.idx")

    /**
     * Copies the file in and indexes it. Throws [DictionaryException] with a
     * reason worth showing when the file turns out not to be a usable dictionary.
     */
    fun install(uri: Uri, displayName: String, onProgress: (String) -> Unit): Installed {
        val id = "dict_" + System.currentTimeMillis().toString(36)
        val target = bookFile(id)

        onProgress("Copying…")
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw DictionaryException("Couldn't read that file.")
            target.outputStream().use { input.copyTo(it, 1 shl 16) }
        }

        val entries = try {
            MobiDictionary.buildIndex(target, indexFile(id), onProgress)
        } catch (e: Exception) {
            target.delete()
            indexFile(id).delete()
            throw if (e is DictionaryException) e
            else DictionaryException(e.message ?: "This file couldn't be indexed.")
        }

        val name = displayName.substringBeforeLast('.').ifBlank { "Dictionary" }
        val installed = Installed(id, name, displayName, entries, target.length(), true)
        save(list() + installed)
        return installed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
        if (!enabled) closeOne(id)
    }

    fun rename(id: String, name: String) {
        save(list().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun delete(id: String) {
        closeOne(id)
        bookFile(id).delete()
        indexFile(id).delete()
        save(list().filter { it.id != id })
    }

    /** Looks the word up in each enabled dictionary, in the order they were added. */
    fun lookup(word: String): List<Pair<Installed, MobiDictionary.Definition>> {
        val hits = ArrayList<Pair<Installed, MobiDictionary.Definition>>()
        for (entry in list()) {
            if (!entry.enabled) continue
            val dict = handle(entry.id) ?: continue
            val definition = try {
                dict.lookup(word)
            } catch (e: Exception) {
                Log.w(TAG, "Lookup failed in ${entry.name}", e)
                null
            }
            if (definition != null) hits.add(entry to definition)
        }
        return hits
    }

    // Lookups run on their own thread per word sheet, so two can arrive at once
    // and both decide the index needs rebuilding. Two concurrent rebuilds of a
    // large dictionary exhaust the heap and corrupt the file they share.
    @Synchronized
    private fun handle(id: String): MobiDictionary? {
        open[id]?.let { return it }
        val book = bookFile(id)
        val index = indexFile(id)

        MobiDictionary.open(book, index)?.let {
            open[id] = it
            return it
        }

        // The index is missing or was written by an older build whose headword
        // decoding was wrong. Rebuild rather than leave the dictionary silently
        // answering nothing for every word.
        if (!book.exists()) return null
        return try {
            Log.i(TAG, "Rebuilding index for $id")
            val entries = MobiDictionary.buildIndex(book, index) {}
            val rebuilt = MobiDictionary.open(book, index)
            if (rebuilt != null) {
                open[id] = rebuilt
                save(list().map { if (it.id == id) it.copy(entries = entries) else it })
            }
            rebuilt
        } catch (e: Throwable) {
            // Throwable, not Exception: indexing a large dictionary can run the
            // heap out, and an uncaught Error kills the lookup thread silently.
            Log.w(TAG, "Could not rebuild index for $id", e)
            index.delete()
            null
        }
    }

    private fun closeOne(id: String) {
        open.remove(id)?.close()
    }

    fun closeAll() {
        open.values.forEach { it.close() }
        open.clear()
    }

    companion object {
        private const val TAG = "DictionaryStore"
    }
}
