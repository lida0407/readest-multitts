package com.readest.multitts.reader

import com.readest.multitts.model.Chapter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.regex.Pattern

/**
 * Real EPUB parsing: container.xml -> .opf -> manifest + spine, so chapters keep the
 * book's own reading order (filename sorting reordered and mis-titled chapters before).
 * Titles come from the navigation document (EPUB 3 nav / EPUB 2 toc.ncx) when present.
 */
object EpubParser {

    data class EpubDoc(
        val title: String?,
        val author: String?,
        val chapters: List<Chapter>
    )

    /** Cheap metadata-only read (container.xml + OPF header) for refreshing shelf titles. */
    fun readMetadata(file: File): Pair<String?, String?>? {
        var zip: ZipFile? = null
        return try {
            zip = ZipFile(file)
            val opfPath = findOpfPath(zip) ?: return null
            val opfXml = readEntry(zip, opfPath) ?: return null
            val title = extractTag(opfXml, "dc:title") ?: extractTag(opfXml, "title")
            val author = extractTag(opfXml, "dc:creator") ?: extractTag(opfXml, "creator")
            Pair(title?.trim(), author?.trim())
        } catch (e: Exception) {
            null
        } finally {
            try { zip?.close() } catch (_: Exception) {}
        }
    }

    fun parse(file: File): EpubDoc? {
        var zip: ZipFile? = null
        try {
            zip = ZipFile(file)

            val opfPath = findOpfPath(zip) ?: return null
            val opfXml = readEntry(zip, opfPath) ?: return null
            val opfDir = opfPath.substringBeforeLast('/', "")

            val metaTitle = extractTag(opfXml, "dc:title") ?: extractTag(opfXml, "title")
            val metaAuthor = extractTag(opfXml, "dc:creator") ?: extractTag(opfXml, "creator")

            // manifest: id -> href (+ media type / properties)
            val manifest = HashMap<String, String>()
            var navHref: String? = null
            var ncxHref: String? = null
            val itemMatcher = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
            while (itemMatcher.find()) {
                val tag = itemMatcher.group()
                val id = attr(tag, "id") ?: continue
                val href = attr(tag, "href") ?: continue
                val mediaType = attr(tag, "media-type") ?: ""
                val properties = attr(tag, "properties") ?: ""
                manifest[id] = href
                if (properties.contains("nav")) navHref = resolve(opfDir, href)
                if (mediaType.contains("dtbncx")) ncxHref = resolve(opfDir, href)
            }

            // spine: ordered idrefs
            val spineBlock = between(opfXml, "<spine", "</spine>") ?: ""
            val spineHrefs = mutableListOf<String>()
            val refMatcher = Pattern.compile("<itemref\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(spineBlock)
            while (refMatcher.find()) {
                val tag = refMatcher.group()
                if (attr(tag, "linear")?.equals("no", true) == true) continue
                val idref = attr(tag, "idref") ?: continue
                val href = manifest[idref] ?: continue
                spineHrefs.add(resolve(opfDir, href))
            }
            if (spineHrefs.isEmpty()) return null

            // toc: normalized href (without fragment) -> label
            val tocTitles = HashMap<String, String>()
            navHref?.let { readEntry(zip, it)?.let { xml -> parseNavDoc(xml, it.substringBeforeLast('/', ""), tocTitles) } }
            if (tocTitles.isEmpty()) {
                ncxHref?.let { readEntry(zip, it)?.let { xml -> parseNcx(xml, it.substringBeforeLast('/', ""), tocTitles) } }
            }

            val chapters = mutableListOf<Chapter>()
            for (href in spineHrefs) {
                val html = readEntry(zip, href) ?: continue
                val paragraphs = HtmlText.extractParagraphs(html)
                if (paragraphs.isEmpty()) continue

                val title = tocTitles[href]
                    ?: HtmlText.extractHeading(html)
                    ?: "Chapter ${chapters.size + 1}"

                chapters.add(
                    Chapter(
                        index = chapters.size,
                        title = title.trim(),
                        paragraphs = paragraphs
                    )
                )
            }

            if (chapters.isEmpty()) return null
            return EpubDoc(metaTitle?.trim()?.ifEmpty { null }, metaAuthor?.trim()?.ifEmpty { null }, chapters)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { zip?.close() } catch (_: Exception) {}
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val container = readEntry(zip, "META-INF/container.xml")
        if (container != null) {
            val m = Pattern.compile("<rootfile\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(container)
            while (m.find()) {
                val path = attr(m.group(), "full-path")
                if (!path.isNullOrEmpty()) return path.trimStart('/')
            }
        }
        // Fall back to any .opf in the archive
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e: ZipEntry = entries.nextElement()
            if (e.name.lowercase().endsWith(".opf")) return e.name
        }
        return null
    }

    private fun parseNavDoc(xml: String, baseDir: String, out: MutableMap<String, String>) {
        // EPUB 3: <nav epub:type="toc"> ... <a href="ch1.xhtml">Title</a>
        val navBlock = between(xml, "<nav", "</nav>") ?: xml
        val m = Pattern.compile("<a\\b([^>]*)>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(navBlock)
        while (m.find()) {
            val href = attr("<a ${m.group(1)}>", "href") ?: continue
            val label = HtmlText.stripTags(m.group(2)).trim()
            if (label.isEmpty()) continue
            out.putIfAbsent(resolve(baseDir, href.substringBefore('#')), label)
        }
    }

    private fun parseNcx(xml: String, baseDir: String, out: MutableMap<String, String>) {
        val m = Pattern.compile("<navPoint\\b[\\s\\S]*?</navPoint>", Pattern.CASE_INSENSITIVE).matcher(xml)
        while (m.find()) {
            val block = m.group()
            val label = between(block, "<text>", "</text>")?.let { HtmlText.stripTags(it).trim() } ?: continue
            val contentTag = Pattern.compile("<content\\b[^>]*>", Pattern.CASE_INSENSITIVE)
                .matcher(block).let { if (it.find()) it.group() else null } ?: continue
            val src = attr(contentTag, "src") ?: continue
            if (label.isEmpty()) continue
            out.putIfAbsent(resolve(baseDir, src.substringBefore('#')), label)
        }
    }

    private fun readEntry(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path)
            ?: zip.getEntry(path.trimStart('/'))
            ?: zip.entries().toList().firstOrNull { it.name.equals(path, ignoreCase = true) }
            ?: return null
        return try {
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    /** Resolve an href against its base directory, collapsing "../" segments. */
    private fun resolve(baseDir: String, href: String): String {
        val decoded = href.replace("%20", " ").trim()
        if (decoded.startsWith("/")) return decoded.trimStart('/')
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts.addAll(baseDir.split("/").filter { it.isNotEmpty() })
        for (segment in decoded.split("/")) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun attr(tag: String, name: String): String? {
        val m = Pattern.compile("$name\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag)
        return if (m.find()) m.group(1) else null
    }

    private fun between(text: String, start: String, end: String): String? {
        val s = text.indexOf(start, ignoreCase = true)
        if (s < 0) return null
        val contentStart = text.indexOf('>', s).let { if (it < 0) s + start.length else it + 1 }
        val e = text.indexOf(end, contentStart, ignoreCase = true)
        if (e < 0) return null
        return text.substring(contentStart, e)
    }

    private fun extractTag(xml: String, tag: String): String? {
        val m = Pattern.compile("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", Pattern.CASE_INSENSITIVE).matcher(xml)
        return if (m.find()) HtmlText.unescape(HtmlText.stripTags(m.group(1))).trim() else null
    }
}
