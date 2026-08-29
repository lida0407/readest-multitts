package com.readest.multitts.reader

import com.readest.multitts.model.Book
import com.readest.multitts.model.BookFormat
import com.readest.multitts.model.Chapter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.regex.Pattern
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object DocumentManager {

    fun determineFormat(file: File): BookFormat {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".epub") -> BookFormat.EPUB
            name.endsWith(".txt") -> BookFormat.TXT
            name.endsWith(".mobi") || name.endsWith(".azw3") -> BookFormat.MOBI
            name.endsWith(".pdf") -> BookFormat.PDF
            else -> BookFormat.UNKNOWN
        }
    }

    /**
     * Same as [loadBook] but reuses a previously parsed chapter list when the file
     * hasn't changed — a long book otherwise re-parses every chapter on each open.
     */
    fun loadBookCached(context: android.content.Context, file: File): Pair<Book, List<Chapter>> {
        val bookId = java.util.UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString()
        ChapterCacheStore.load(context, bookId, file)?.let { cached ->
            val book = Book(
                id = bookId,
                title = cached.title,
                author = cached.author,
                format = determineFormat(file),
                filePath = file.absolutePath,
                totalChapters = cached.chapters.size
            )
            return Pair(book, cached.chapters)
        }

        val parsed = loadBook(file)
        ChapterCacheStore.save(
            context, bookId, file,
            ChapterCacheStore.ParsedBook(parsed.first.title, parsed.first.author, parsed.second)
        )
        return parsed
    }

    fun loadBook(file: File): Pair<Book, List<Chapter>> {
        val format = determineFormat(file)
        val fallbackTitle = titleFromFileName(file)

        val bookId = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString()

        var title = fallbackTitle
        var author = "Unknown Author"

        val chapters = when (format) {
            BookFormat.TXT -> parseTxt(file, title)
            BookFormat.EPUB -> {
                // Prefer the book's own metadata title over the (often junk) file name
                val doc = EpubParser.parse(file)
                if (doc != null) {
                    doc.title?.let { if (isUsableTitle(it)) title = it }
                    doc.author?.let { if (it.isNotBlank()) author = it }
                    doc.chapters
                } else {
                    parseEpub(file, title)
                }
            }
            BookFormat.MOBI -> {
                val doc = MobiParser.parse(file)
                if (doc != null) {
                    doc.title?.let { if (isUsableTitle(it)) title = it }
                    chaptersFromText(doc.text, title)
                } else {
                    parseMobi(file, title)
                }
            }
            BookFormat.PDF -> {
                val doc = PdfParser.parse(file)
                if (doc != null) {
                    doc.title?.let { if (isUsableTitle(it)) title = it }
                    // One chapter per ~10 pages keeps chapters navigable in long PDFs
                    doc.pages.chunked(10).mapIndexed { idx, chunk ->
                        Chapter(
                            index = idx,
                            title = "Pages ${idx * 10 + 1}–${idx * 10 + chunk.size}",
                            paragraphs = chunk.flatMap { page ->
                                page.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                            }
                        )
                    }
                } else {
                    parsePdf(file, title)
                }
            }
            else -> parseTxt(file, title)
        }

        val book = Book(
            id = bookId,
            title = title,
            author = author,
            format = format,
            filePath = file.absolutePath,
            totalChapters = chapters.size
        )

        return Pair(book, chapters)
    }

    /** Split a plain-text body into chapters, falling back to fixed-size sections. */
    private fun chaptersFromText(text: String, title: String): List<Chapter> {
        val chapterRegex = Pattern.compile(
            "^\\s*(第[0-9一二三四五六七八九十百千]+[章回节卷集部].*|Chapter\\s+[0-9IVXLC]+.*|CHAPTER\\s+[0-9IVXLC]+.*|Section\\s+\\d+.*|序章|尾声|前言|后记)\\s*$",
            Pattern.CASE_INSENSITIVE
        )
        val paragraphs = text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chapters = mutableListOf<Chapter>()
        var currentTitle = title
        val current = mutableListOf<String>()

        for (line in paragraphs) {
            if (chapterRegex.matcher(line).matches() && current.isNotEmpty()) {
                chapters.add(Chapter(chapters.size, currentTitle, current.toList()))
                current.clear()
                currentTitle = line
            } else {
                current.add(line)
                // Guard against books with no chapter markers at all
                if (current.size >= 400) {
                    chapters.add(Chapter(chapters.size, currentTitle, current.toList()))
                    current.clear()
                    currentTitle = "Section ${chapters.size + 1}"
                }
            }
        }
        if (current.isNotEmpty()) chapters.add(Chapter(chapters.size, currentTitle, current.toList()))
        if (chapters.isEmpty()) chapters.add(Chapter(0, title, listOf("This document has no readable text.")))
        return chapters
    }

    /** Clean up download-service noise: leading ids, z-lib/libgen tags, underscores. */
    private fun titleFromFileName(file: File): String {
        val raw = file.nameWithoutExtension
        var title = raw
            .replace("^[0-9]{6,}[_\\-\\s]*".toRegex(), "")
            .replace("^[0-9]+_+".toRegex(), "")
            .replace("[_\\-\\s]*\\(?z-?lib(rary)?\\.org\\)?[_\\-\\s]*".toRegex(RegexOption.IGNORE_CASE), " ")
            .replace("[_\\-\\s]*libgen[^_\\-\\s]*".toRegex(RegexOption.IGNORE_CASE), " ")
            .replace("_+".toRegex(), " ")
            .replace("\\s{2,}".toRegex(), " ")
            .trim()
        if (title.isEmpty()) title = raw
        return title
    }

    /**
     * Best available title for an already-imported book, without a full re-parse.
     * Returns null when nothing better than the stored title is available.
     */
    fun refreshedTitleFor(file: File, storedTitle: String): Pair<String, String?>? {
        if (determineFormat(file) != BookFormat.EPUB || !file.exists()) return null
        val (metaTitle, metaAuthor) = EpubParser.readMetadata(file) ?: return null
        val better = metaTitle?.takeIf { isUsableTitle(it) } ?: titleFromFileName(file)
        if (better == storedTitle) return null
        return Pair(better, metaAuthor?.takeIf { it.isNotBlank() })
    }

    /** Reject metadata titles that are just ids/filenames rather than a real book name. */
    private fun isUsableTitle(candidate: String): Boolean {
        val t = candidate.trim()
        if (t.length < 2 || t.length > 200) return false
        if (t.contains("z-lib", ignoreCase = true)) return false
        if (t.matches("^[0-9\\s_\\-.]+$".toRegex())) return false
        if (t.equals("unknown", ignoreCase = true)) return false
        return t.any { it.isLetter() }
    }

    private fun parseTxt(file: File, title: String): List<Chapter> {
        val charset = EncodingDetector.detectCharset(file)
        val chapterRegex = Pattern.compile("^\\s*(第[0-9一二三四五六七八九十百千0-9]+[章回节卷集部].*|Chapter\\s+\\d+.*|Section\\s+\\d+.*|序章|尾声|前言|后记)\\s*$", Pattern.CASE_INSENSITIVE)

        val chapters = mutableListOf<Chapter>()
        var currentTitle = "Beginning"
        val currentParagraphs = mutableListOf<String>()

        BufferedReader(InputStreamReader(FileInputStream(file), charset)).use { reader ->
            var line: String?
            var paragraphCountInChunk = 0

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue

                val matcher = chapterRegex.matcher(trimmed)
                if (matcher.matches() || (currentParagraphs.size >= 60 && trimmed.length < 30)) {
                    if (currentParagraphs.isNotEmpty()) {
                        chapters.add(
                            Chapter(
                                index = chapters.size,
                                title = currentTitle,
                                paragraphs = currentParagraphs.toList()
                            )
                        )
                        currentParagraphs.clear()
                        paragraphCountInChunk = 0
                    }
                    currentTitle = trimmed
                } else {
                    currentParagraphs.add(trimmed)
                    paragraphCountInChunk++
                }
            }

            if (currentParagraphs.isNotEmpty()) {
                chapters.add(
                    Chapter(
                        index = chapters.size,
                        title = currentTitle,
                        paragraphs = currentParagraphs.toList()
                    )
                )
            }
        }

        if (chapters.isEmpty()) {
            chapters.add(Chapter(0, title, listOf("Empty text document.")))
        }

        return chapters
    }

    private fun parseEpub(file: File, defaultTitle: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        try {
            val zip = ZipFile(file)
            val entries = zip.entries()
            val htmlFiles = mutableListOf<String>()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.lowercase()
                if ((name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) && !name.contains("toc")) {
                    htmlFiles.add(entry.name)
                }
            }

            htmlFiles.sort()

            for ((idx, entryName) in htmlFiles.withIndex()) {
                val entry = zip.getEntry(entryName) ?: continue
                val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                
                val paragraphs = extractParagraphsFromHtml(content)
                if (paragraphs.isNotEmpty()) {
                    val chapterTitle = extractTitleFromHtml(content) ?: "Chapter ${idx + 1}"
                    chapters.add(
                        Chapter(
                            index = chapters.size,
                            title = chapterTitle,
                            paragraphs = paragraphs
                        )
                    )
                }
            }
            zip.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (chapters.isEmpty()) {
            chapters.add(Chapter(0, defaultTitle, listOf("Unable to extract EPUB content or file is encrypted.")))
        }

        return chapters
    }

    private fun parseMobi(file: File, defaultTitle: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        try {
            val bytes = file.readBytes()
            val textContent = extractMobiText(bytes)
            val paras = textContent.split("\n\n", "\r\n\r\n").map { it.trim() }.filter { it.isNotEmpty() }
            
            // Chunk paragraphs into chapters
            val chunkSize = 50
            paras.chunked(chunkSize).forEachIndexed { index, chunk ->
                chapters.add(
                    Chapter(
                        index = index,
                        title = "Section ${index + 1}",
                        paragraphs = chunk
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (chapters.isEmpty()) {
            chapters.add(Chapter(0, defaultTitle, listOf("MOBI parsing loaded.")))
        }

        return chapters
    }

    private fun parsePdf(file: File, defaultTitle: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        try {
            val text = file.readText(Charsets.ISO_8859_1)
            // Extract text chunks from streams
            val regex = Pattern.compile("BT[\\s\\S]*?ET")
            val matcher = regex.matcher(text)
            val extractedLines = mutableListOf<String>()
            
            while (matcher.find()) {
                val block = matcher.group()
                val lineRegex = Pattern.compile("\\((.*?)\\)\\s*T[jJ]")
                val lineMatcher = lineRegex.matcher(block)
                while (lineMatcher.find()) {
                    val rawStr = lineMatcher.group(1) ?: ""
                    if (rawStr.isNotBlank()) {
                        extractedLines.add(rawStr)
                    }
                }
            }

            if (extractedLines.isNotEmpty()) {
                extractedLines.chunked(40).forEachIndexed { idx, chunk ->
                    chapters.add(
                        Chapter(
                            index = idx,
                            title = "Page / Section ${idx + 1}",
                            paragraphs = chunk
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (chapters.isEmpty()) {
            chapters.add(Chapter(0, defaultTitle, listOf("PDF document ready for reading.")))
        }

        return chapters
    }

    private fun extractParagraphsFromHtml(html: String): List<String> {
        val noScriptsStyles = html.replace("(?i)<style[\\s\\S]*?</style>".toRegex(), "")
            .replace("(?i)<script[\\s\\S]*?</script>".toRegex(), "")
            .replace("(?i)<head[\\s\\S]*?</head>".toRegex(), "")
        
        val cleanHtml = noScriptsStyles.replace("<br\\s*/?>".toRegex(), "\n")
            .replace("</p>".toRegex(), "\n\n")
            .replace("</div>".toRegex(), "\n")
            .replace("</h1>".toRegex(), "\n\n")
            .replace("</h2>".toRegex(), "\n\n")
            .replace("</h3>".toRegex(), "\n\n")
        
        val noTags = cleanHtml.replace("<[^>]*>".toRegex(), " ")
        val decoded = unescapeHtml(noTags)
        
        val cssPattern = "^\\s*[a-zA-Z0-9#._\\- >:,]+\\s*\\{[\\s\\S]*?\\}\\s*$".toRegex()

        return decoded.split("\n")
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && line.length > 1 &&
                !line.contains("{padding:") &&
                !line.contains("{margin:") &&
                !line.contains("text-align:") &&
                !cssPattern.matches(line)
            }
    }

    private fun extractTitleFromHtml(html: String): String? {
        val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (titleMatcher.find()) {
            val t = titleMatcher.group(1)?.trim()
            if (!t.isNullOrEmpty()) return unescapeHtml(t)
        }
        val h1Matcher = Pattern.compile("<h[1-2][^>]*>(.*?)</h[1-2]>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (h1Matcher.find()) {
            val t = h1Matcher.group(1)?.replace("<[^>]*>".toRegex(), "")?.trim()
            if (!t.isNullOrEmpty()) return unescapeHtml(t)
        }
        return null
    }

    private fun extractMobiText(bytes: ByteArray): String {
        val sb = StringBuilder()
        var inText = false
        for (b in bytes) {
            val ch = b.toInt().toChar()
            if (ch in ' '..'~' || ch == '\n' || ch == '\r') {
                sb.append(ch)
            } else if (b.toInt() in 0x80..0xFF) {
                // High byte
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescapeHtml(text: String): String {
        return text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&hellip;", "…")
    }
}
