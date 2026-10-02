package com.efeyamann.hscan

import com.efeyamann.hscan.data.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class WebMangaSourceTest {
    private val bats = WebMangaSource("mangabats", OkHttpClient())
    private val buddy = WebMangaSource("mangabuddy", OkHttpClient())

    @Test fun searchKeepsSourceAndIgnoresCarouselAndChapterLinks() {
        val cards = bats.parseSearch("""<div class="item"><a href="/manga/advert">Advert</a></div>
            <div class="panel_story_list"><div class="story_item"><a href="/manga/sample"><img src="https://images.example/cover.webp"></a>
            <h3><a href="/manga/sample">Sample &amp; Hero</a></h3><a href="/manga/sample/chapter-1">Chapter 1</a></div>
            <div class="story_item"><a href="https://other.example/manga/sample">Other</a></div></div>""")
        assertEquals(1, cards.size)
        assertEquals("Sample & Hero", cards.single().title)
        assertEquals("mangabats", cards.single().source)
        assertEquals("https://www.mangabats.com/manga/sample", cards.single().sourceUrl)
        assertTrue(cards.single().id.matches(Regex("[A-Za-z0-9-]{1,100}")))
    }
    @Test fun buddyHashedUrlsAndLazyCoversSurviveSearch() {
        val card = buddy.parseSearch("""<div id="comics-container"><div class="comic-item">
            <a href="/series/hero.Ab_C"><img data-src="https://images.example/hero.webp" src="/placeholder.png"></a>
            <a href="/series/hero.Ab_C">Hero</a><a href="/series/hero.Ab_C/chapter-2">Chapter 2</a></div></div>""").single()
        assertEquals("https://images.example/hero.webp", card.cover)
        assertEquals("https://mangabuddy1.co.uk/series/hero.Ab_C", card.sourceUrl)
        assertEquals(buddy.id(card.sourceUrl), card.id)
        assertNotEquals(bats.id(card.sourceUrl), card.id)
    }
    @Test fun pagesKeepDocumentOrderRefererAndExcludeAds() {
        val pages = bats.parsePages("""<img src="https://ads.example/ad.webp"><div class="container-chapter-reader">
            <img src="https://images.example/10.webp"><img data-src="https://images.example/2.webp" src="/placeholder.png">
            <img src="https://images.example/2.webp"><img src="javascript:bad()"></div>""", "https://www.mangabats.com/manga/hero/chapter-1")
        assertEquals(listOf("https://images.example/10.webp", "https://images.example/2.webp"), pages.map { it.uri })
        assertEquals("https://www.mangabats.com/", pages.first().referer)
        assertEquals(pages, pagesFromJson(pages.toJson()))
        assertEquals("", pagesFromJson("""[{"uri":"https://images.example/old.webp"}]""").single().referer)
        assertEquals(1, buddy.parsePages("""<img src="https://ads.example/ad"><img data-number="1" data-src="https://images.example/page.webp">""", buddy.base).size)
    }
    @Test fun onlyProviderCataloguePathsAreAccepted() {
        assertTrue(buddy.validUrl("https://mangabuddy1.co.uk/series/hero.Ab_C/chapter-1.5", true))
        assertFalse(buddy.validUrl("https://mangabuddy1.co.uk.evil.example/series/hero"))
        assertFalse(bats.validUrl("http://www.mangabats.com/manga/hero"))
        assertFalse(bats.validUrl("https://www.mangabats.com/manga/hero/chapter-1"))
    }
}
