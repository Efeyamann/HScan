package com.efeyamann.hscan.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ArchiveRulesTest {
    @Test fun pagesUseNumericRatherThanLexicalOrder() {
        assertEquals(listOf("1.jpg", "02.jpg", "10.jpg", "100.jpg"), listOf("10.jpg", "100.jpg", "02.jpg", "1.jpg").sortedWith(ArchiveRules.naturalOrder))
        assertTrue(ArchiveRules.naturalOrder.compare("99999999999999999999.jpg", "100000000000000000000.jpg") < 0)
    }
    @Test fun rejectsTraversalAndAbsolutePaths() {
        val root = File(System.getProperty("java.io.tmpdir"), "hscan-import-test")
        listOf("../escape.jpg", "/absolute.jpg", "C:/escape.jpg", "folder\\escape.jpg").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { ArchiveRules.safeDestination(root, name) }
        }
        assertEquals(File(root, "chapter/1.jpg").canonicalFile, ArchiveRules.safeDestination(root, "chapter/1.jpg"))
    }
    @Test fun validatesImageNames() {
        assertTrue(ArchiveRules.isImage("chapter/1.JPG"))
        assertFalse(ArchiveRules.isImage("__MACOSX/1.jpg"))
        assertFalse(ArchiveRules.isImage("1.jpg.exe"))
    }
    @Test fun sizeLimitAppliesToActualBytes() {
        assertThrows(IllegalArgumentException::class.java) { ReaderRepository.copyLimited(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 9) }
        assertEquals(10L, ReaderRepository.copyLimited(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 10))
    }
    @Test fun pageManifestRoundTrip() {
        val pages = listOf(Page("https://example.com/page.jpg", 800, 16000), Page("/local/2.img", 600, 900))
        assertEquals(pages, pagesFromJson(pages.toJson()))
    }
    @Test fun readsMangaDexCoverRelationshipAndLocalizedTitle() {
        val data = JSONObject("""{"id":"abc","attributes":{"title":{"ja":"日本語","en":"English","tr":"Türkçe"},"description":{"en":"Description"}},"relationships":[{"type":"cover_art","attributes":{"fileName":"cover.png"}}]}""")
        val manga = MangaDex.parseManga(data)
        assertEquals("Türkçe", manga.title)
        assertEquals("https://uploads.mangadex.org/covers/abc/cover.png.256.jpg", manga.cover)
    }
}
