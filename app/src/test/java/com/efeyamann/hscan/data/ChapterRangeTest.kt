package com.efeyamann.hscan.data

import org.junit.Assert.*
import org.junit.Test

class ChapterRangeTest {
    private val chapters = listOf(
        Chapter("a", "m", "Bölüm 1", "1"),
        Chapter("b", "m", "Bölüm 2", "2", downloadState = "ready"),
        Chapter("c", "m", "Bölüm 2.5", "2.5", downloadState = "error"),
        Chapter("special", "m", "Özel bölüm"),
        Chapter("d", "m", "Bölüm 4", "4", downloadState = "queued"),
        Chapter("e", "m", "Bölüm 5", "5", downloadState = "downloading"),
    )

    @Test fun includesEndpointsDecimalsAndUnnumberedSpecials() {
        val range = chapterRange(chapters, "b", "d")
        assertEquals(listOf("b", "c", "special", "d"), range.map { it.id })
        assertEquals(listOf("c", "special"), range.filter { it.canDownload() }.map { it.id })
    }

    @Test fun sameEndpointSelectsOneAndWholeRangeSelectsAll() {
        assertEquals(listOf(chapters[2]), chapterRange(chapters, "c", "c"))
        assertEquals(chapters, chapterRange(chapters, "a", "e"))
    }

    @Test fun reversedMissingAndEmptyRangesAreRejected() {
        assertTrue(chapterRange(chapters, "d", "b").isEmpty())
        assertTrue(chapterRange(chapters, "gone", "d").isEmpty())
        assertTrue(chapterRange(chapters, "a", "gone").isEmpty())
        assertTrue(chapterRange(emptyList(), "a", "e").isEmpty())
    }

    @Test fun preservesVisibleLanguageFilterAndSkipsLocalChapters() {
        val visible = chapters + Chapter("tr", "m", "Bölüm 6", "6", "tr")
        assertFalse(chapterRange(visible.filter { it.language == "en" }, "a", "e").any { it.language == "tr" })
        assertFalse(Chapter("local", "m", "Arşiv", language = "local").canDownload())
    }
}
