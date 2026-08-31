package com.readest.multitts.dict

/**
 * Turns a raw slice of dictionary markup into something a TextView can render.
 *
 * The slice is cut at a byte offset, so it routinely starts or ends mid-tag —
 * everything here is written to survive markup that was never well-formed.
 */
object DictionaryHtml {

    private val DROP_TAGS = Regex("(?is)</?(?:idx:[a-z]+|mbp:[a-z]+|html|body|head|meta|link|script|style)[^>]*>")
    private val IMAGES = Regex("(?is)<img[^>]*>")
    private val ANCHORS = Regex("(?is)</?a[^>]*>")
    private val BLOCKS = Regex("(?is)</(?:p|div|li|tr|h[1-6])>")
    private val OPEN_BLOCKS = Regex("(?is)<(?:p|div|li|tr|h[1-6])[^>]*>")
    private val BREAKS = Regex("(?is)<br\\s*/?>")
    private val KEEP = Regex("(?is)</?(?:b|strong|i|em|u|sup|sub|span|font)[^>]*>")
    private val TRAILING_PARTIAL_TAG = Regex("<[^>]*$")
    private val LEADING_PARTIAL_TAG = Regex("^[^<]*>")
    private val BLANK_RUN = Regex("(<br>\\s*){3,}")

    fun toDisplayHtml(raw: String): String {
        var s = raw

        // A cut can land inside a tag at either end; drop the fragment rather
        // than let the renderer swallow the text that follows it.
        s = LEADING_PARTIAL_TAG.replace(s) { m ->
            if (m.value.contains('<')) m.value else ""
        }
        s = TRAILING_PARTIAL_TAG.replace(s, "")

        s = DROP_TAGS.replace(s, "")
        s = IMAGES.replace(s, "")
        s = ANCHORS.replace(s, "")
        s = BREAKS.replace(s, "<br>")
        s = BLOCKS.replace(s, "<br>")
        s = OPEN_BLOCKS.replace(s, "")

        // Anything still tag-shaped that is not on the keep list goes, so stray
        // attributes from a truncated element can't leak into the text.
        s = Regex("(?is)<(?!/?(?:b|strong|i|em|u|sup|sub|br)\\b)[^>]*>").replace(s) { m ->
            if (KEEP.matches(m.value)) "" else ""
        }

        s = BLANK_RUN.replace(s, "<br><br>")
        return s.trim().removePrefix("<br>").trim()
    }

    /** Plain text for TTS — the definition read aloud, not its markup. */
    fun toPlainText(raw: String): String =
        toDisplayHtml(raw)
            .replace(Regex("(?is)<br>"), "\n")
            .replace(Regex("(?is)<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
