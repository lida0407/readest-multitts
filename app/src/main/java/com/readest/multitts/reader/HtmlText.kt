package com.readest.multitts.reader

import java.util.regex.Pattern

/** Shared HTML -> reading text helpers used by the EPUB parser. */
object HtmlText {

    fun extractParagraphs(html: String): List<String> {
        val body = html
            .replace("(?i)<style[\\s\\S]*?</style>".toRegex(), "")
            .replace("(?i)<script[\\s\\S]*?</script>".toRegex(), "")
            .replace("(?i)<head[\\s\\S]*?</head>".toRegex(), "")

        val blocked = body
            .replace("(?i)<br\\s*/?>".toRegex(), "\n")
            .replace("(?i)</(p|div|h[1-6]|li|blockquote|section)>".toRegex(), "\n\n")

        val decoded = unescape(stripTags(blocked))

        return decoded.split("\n")
            .map { it.replace(' ', ' ').trim() }
            .filter { line ->
                line.length > 1 &&
                    !line.contains("{padding:") &&
                    !line.contains("{margin:") &&
                    !line.contains("text-align:") &&
                    !line.matches("^\\s*[a-zA-Z0-9#._\\- >:,]+\\s*\\{[\\s\\S]*?\\}\\s*$".toRegex())
            }
    }

    fun extractHeading(html: String): String? {
        val m = Pattern.compile("<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (m.find()) {
            val t = unescape(stripTags(m.group(1))).trim()
            if (t.isNotEmpty()) return t.take(80)
        }
        val t = Pattern.compile("<title>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (t.find()) {
            val v = unescape(stripTags(t.group(1))).trim()
            if (v.isNotEmpty()) return v.take(80)
        }
        return null
    }

    fun stripTags(text: String): String = text.replace("<[^>]*>".toRegex(), " ")

    fun unescape(text: String): String {
        var out = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&hellip;", "…")

        // Numeric entities (&#8212; / &#x2014;)
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return out.replace("[ \\t]{2,}".toRegex(), " ")
    }
}
