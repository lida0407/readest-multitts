package com.readest.multitts.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two release tracks share one repository, so picking the wrong one is a
 * silent mistake: the download succeeds and only the install fails.
 */
class UpdateCheckerTest {

    private val releases = """
    [
      {
        "tag_name": "v1.17.0-bundled",
        "published_at": "2026-09-01T10:00:00Z",
        "body": "bundled voices",
        "assets": [
          {"name": "Readest-MultiTTS-bundled-v1.17.0.apk",
           "browser_download_url": "https://example.test/bundled-1.17.0.apk"}
        ]
      },
      {
        "tag_name": "v1.17.0",
        "published_at": "2026-09-01T09:00:00Z",
        "body": "standard",
        "assets": [
          {"name": "Readest-MultiTTS-v1.17.0.apk",
           "browser_download_url": "https://example.test/standard-1.17.0.apk"}
        ]
      },
      {
        "tag_name": "v1.9.0",
        "published_at": "2026-08-01T09:00:00Z",
        "body": "older",
        "assets": [
          {"name": "Readest-MultiTTS-v1.9.0.apk",
           "browser_download_url": "https://example.test/standard-1.9.0.apk"}
        ]
      }
    ]
    """.trimIndent()

    @Test
    fun `standard track ignores bundled releases`() {
        val release = UpdateChecker.selectRelease(releases, "standard")!!
        assertEquals("1.17.0", release.version)
        assertEquals("Readest-MultiTTS-v1.17.0.apk", release.apkName)
        assertTrue(release.apkUrl!!.contains("standard"))
    }

    @Test
    fun `bundled track ignores standard releases`() {
        val release = UpdateChecker.selectRelease(releases, "bundled")!!
        assertEquals("1.17.0", release.version)
        assertEquals("Readest-MultiTTS-bundled-v1.17.0.apk", release.apkName)
        assertTrue(release.apkUrl!!.contains("bundled"))
    }

    @Test
    fun `a track with no release yet returns nothing rather than the other track`() {
        val onlyStandard = """
        [{"tag_name": "v1.2.0", "assets": [
          {"name": "Readest-MultiTTS-v1.2.0.apk", "browser_download_url": "https://example.test/a.apk"}]}]
        """.trimIndent()
        assertNull(UpdateChecker.selectRelease(onlyStandard, "bundled"))
    }

    @Test
    fun `a release carrying both APKs hands each track its own`() {
        val combined = """
        [{"tag_name": "v2.0.0", "assets": [
          {"name": "Readest-MultiTTS-bundled-v2.0.0.apk", "browser_download_url": "https://example.test/b.apk"},
          {"name": "Readest-MultiTTS-v2.0.0.apk", "browser_download_url": "https://example.test/s.apk"}]}]
        """.trimIndent()
        assertEquals("Readest-MultiTTS-v2.0.0.apk", UpdateChecker.selectRelease(combined, "standard")!!.apkName)
    }

    @Test
    fun `the newest version wins even when published out of order`() {
        val outOfOrder = """
        [{"tag_name": "v1.9.1", "published_at": "2026-09-02T00:00:00Z", "assets": []},
         {"tag_name": "v1.10.0", "published_at": "2026-09-01T00:00:00Z", "assets": []}]
        """.trimIndent()
        assertEquals("1.10.0", UpdateChecker.selectRelease(outOfOrder, "standard")!!.version)
    }

    @Test
    fun `draft releases are skipped`() {
        val withDraft = """
        [{"tag_name": "v3.0.0", "draft": true, "assets": []},
         {"tag_name": "v2.0.0", "assets": []}]
        """.trimIndent()
        assertEquals("2.0.0", UpdateChecker.selectRelease(withDraft, "standard")!!.version)
    }

    @Test
    fun `dotted versions compare numerically, not as text`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"))
        assertTrue(!UpdateChecker.isNewer("1.17.0", "1.17.0"))
        assertTrue(!UpdateChecker.isNewer("1.16.0", "1.17.0"))
    }
}
