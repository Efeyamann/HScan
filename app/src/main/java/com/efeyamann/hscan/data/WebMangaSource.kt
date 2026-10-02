package com.efeyamann.hscan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.security.MessageDigest

fun sourceName(source: String) = when (source) {
    "mangabats" -> "MangaBats"
    "mangabuddy" -> "MangaBuddy"
    "local" -> "Yerel arşiv"
    else -> "MangaDex"
}

class WebMangaSource(val source: String, private val client: OkHttpClient) {
    val base = if (source == "mangabats") "https://www.mangabats.com" else "https://mangabuddy1.co.uk"
    private val prefix = if (source == "mangabats") "/manga/" else "/series/"

    fun validUrl(url: String, chapter: Boolean = false): Boolean {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        return parsed.scheme == "https" && parsed.host == base.toHttpUrl().host &&
            parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.encodedPath.matches(Regex(if (chapter) "${prefix}[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+" else "${prefix}[A-Za-z0-9_.-]+"))
    }
    fun id(url: String) = source + "-" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private suspend fun get(url: String, ajax: Boolean = false): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) HScan/0.1")
            .header("Referer", "$base/")
        if (ajax) request.header("X-Requested-With", "XMLHttpRequest").header("Accept", "application/json")
        client.newCall(request.build()).execute().use {
            if (!it.isSuccessful) throw IOException("${sourceName(source)} yanıt vermedi (${it.code}).")
            it.body?.string() ?: throw IOException("${sourceName(source)} boş yanıt verdi.")
        }
    }
    suspend fun search(query: String): List<Manga> {
        val url = if (source == "mangabats") {
            if (query.isBlank()) "$base/" else "$base/home/search/json".toHttpUrl().newBuilder()
                .addQueryParameter("searchword", query.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')).build().toString()
        } else "$base/series".toHttpUrl().newBuilder().addQueryParameter("searchTerm", query.trim()).build().toString()
        return parseSearch(get(url), url)
    }
    fun parseSearch(html: String, url: String = base): List<Manga> {
        val doc = Jsoup.parse(html, url)
        val cards = doc.select(if (source == "mangabats") ".panel_story_list .story_item, #contentstory .itemupdate" else "#comics-container .comic-item")
        return cards.mapNotNull { card ->
            val link = card.select("a[href]").firstOrNull { validUrl(it.absUrl("href")) && it.selectFirst("img") == null && it.text().isNotBlank() } ?: return@mapNotNull null
            val target = link.absUrl("href").substringBefore('?').trimEnd('/')
            val image = card.selectFirst("img")
            val cover = image?.let { it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src") }.orEmpty()
            Manga(id(target), link.text(), cover = cover, source = source, sourceUrl = target)
        }.distinctBy { it.id }.take(30)
    }
    suspend fun chapters(manga: Manga): List<Chapter> {
        require(validUrl(manga.sourceUrl)) { "Kaynak bağlantısı geçersiz." }
        val doc = Jsoup.parse(get(manga.sourceUrl), manga.sourceUrl)
        val result = mutableListOf<Chapter>()
        if (source == "mangabats") {
            val slug = manga.sourceUrl.substringAfterLast('/')
            var offset = 0
            do {
                val root = JSONObject(get("$base/api/manga/$slug/chapters?limit=50&offset=$offset", true))
                check(root.optBoolean("success")) { "Bölüm listesi yüklenemedi." }
                val data = root.getJSONObject("data")
                val rows = data.getJSONArray("chapters")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val target = "${manga.sourceUrl}/${row.getString("chapter_slug")}"
                    if (validUrl(target, true)) result += chapter(manga, target, row.getString("chapter_name"), row.optString("chapter_num"))
                }
                offset += 50
                val more = data.getJSONObject("pagination").optBoolean("has_more")
                if (more && (rows.length() == 0 || offset >= 10000)) throw IOException("Bölüm listesi tamamlanamadı.")
            } while (more)
        } else {
            val slug = doc.selectFirst("#load-all-chapters-btn")?.attr("data-comic-slug")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            if (slug != null) {
                val root = JSONObject(get("$base/get-chapter-list".toHttpUrl().newBuilder().addQueryParameter("slug", slug).build().toString(), true))
                check(root.optBoolean("success")) { "Bölüm listesi yüklenemedi." }
                val rows = root.getJSONArray("data")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val number = row.getString("chapter_num")
                    val target = "${manga.sourceUrl}/chapter-$number"
                    if (validUrl(target, true)) result += chapter(manga, target, row.getString("chapter_name"), number)
                }
            } else {
                doc.select("#chapter-list a[href]").forEach { link ->
                    val target = link.absUrl("href")
                    if (validUrl(target, true)) result += chapter(manga, target, link.text(), target.substringAfterLast("chapter-"))
                }
            }
        }
        if (result.isEmpty()) throw IOException("${sourceName(source)} bölüm listesi bulunamadı.")
        return result.distinctBy { it.id }.sortedBy { it.order }
    }
    private fun chapter(manga: Manga, url: String, title: String, number: String) =
        Chapter(id(url), manga.id, title, number, order = number.toDoubleOrNull() ?: 0.0, sourceUrl = url)

    suspend fun pages(chapter: Chapter): List<Page> {
        require(validUrl(chapter.sourceUrl, true)) { "Bölüm bağlantısı geçersiz." }
        return parsePages(get(chapter.sourceUrl), chapter.sourceUrl).ifEmpty { throw IOException("${sourceName(source)} sayfaları bulunamadı.") }
    }
    fun parsePages(html: String, url: String): List<Page> = Jsoup.parse(html, url)
        .select(if (source == "mangabats") ".container-chapter-reader img" else "img[data-number][data-src]")
        .mapNotNull {
            val image = it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src")
            if (image.startsWith("https://")) Page(image, referer = "$base/") else null
        }.distinctBy { it.uri }
}
