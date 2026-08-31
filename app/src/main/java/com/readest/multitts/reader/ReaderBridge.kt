package com.readest.multitts.reader

import android.webkit.JavascriptInterface
import org.json.JSONArray

interface ReaderBridgeListener {
    fun onReaderReady()
    fun onChapterLoaded(chapterIndex: Int, chapterTitle: String, sentences: List<String>)
    fun onSentenceClicked(sentenceIndex: Int, text: String)
    fun requestNextChapter()
    fun requestPrevChapter()
    fun toggleToolbars()
    fun onPageChanged(pageIndex: Int, totalPages: Int, firstVisibleSentence: Int)
    fun onWordLongPress(word: String, sentenceIndex: Int, sentenceText: String)
}

class ReaderBridge(private val listener: ReaderBridgeListener) {

    @JavascriptInterface
    fun onReaderReady() {
        listener.onReaderReady()
    }

    @JavascriptInterface
    fun onChapterLoaded(chapterIndex: Int, chapterTitle: String, sentencesJson: String) {
        val sentencesList = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(sentencesJson)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val text = item.optString("text", "")
                if (text.isNotEmpty()) {
                    sentencesList.add(text)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        listener.onChapterLoaded(chapterIndex, chapterTitle, sentencesList)
    }

    @JavascriptInterface
    fun onSentenceClicked(sentenceIndex: Int, text: String) {
        listener.onSentenceClicked(sentenceIndex, text)
    }

    @JavascriptInterface
    fun requestNextChapter() {
        listener.requestNextChapter()
    }

    @JavascriptInterface
    fun requestPrevChapter() {
        listener.requestPrevChapter()
    }

    @JavascriptInterface
    fun toggleToolbars() {
        listener.toggleToolbars()
    }

    @JavascriptInterface
    fun onPageChanged(pageIndex: Int, totalPages: Int, firstVisibleSentence: Int) {
        listener.onPageChanged(pageIndex, totalPages, firstVisibleSentence)
    }

    @JavascriptInterface
    fun onWordLongPress(word: String, sentenceIndex: Int, sentenceText: String) {
        listener.onWordLongPress(word, sentenceIndex, sentenceText)
    }
}
