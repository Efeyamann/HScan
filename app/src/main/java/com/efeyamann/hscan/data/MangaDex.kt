package com.efeyamann.hscan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MangaDex {
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private val gate = Mutex()
    private var lastRequest = 0L

    private suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): JSONObject = withContext(Dispatchers.IO) {
        gate.withLock {
            val wait = 350 - (System.currentTimeMillis() - lastRequest)
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
        }
        val url = "https://api.mangadex.org$path".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        client.newCall(Request.Builder().url(url).header("User-Agent", "HScan/0.1 (personal Android reader)").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(when (response.code) {
                429 -> "MangaDex istek sınırına ulaşıldı. Biraz bekleyip tekrar dene."
                403 -> "MangaDex bu bağlantıya erişim vermedi."
                else -> "MangaDex'e ulaşılamadı (${response.code})."
            })
            JSONObject(response.body?.string() ?: throw IOException("MangaDex boş yanıt verdi."))
        }
    }

    suspend fun search(query: String, language: String): List<Manga> {
        val params = mutableListOf("limit" to "30", "includes[]" to "cover_art", "contentRating[]" to "safe", "contentRating[]" to "suggestive", "hasAvailableChapters" to "true")
        if (query.isNotBlank()) params += "title" to query.trim() else params += "order[followedCount]" to "desc"
        if (language != "all") params += "availableTranslatedLanguage[]" to language
        val array = get("/manga", params).getJSONArray("data")
        return (0 until array.length()).map { parseManga(array.getJSONObject(it)) }
    }

    suspend fun chapters(mangaId: String, language: String): List<Chapter> {
        val result = mutableListOf<Chapter>()
        var offset = 0
        do {
            val params = mutableListOf("limit" to "500", "offset" to offset.toString(), "order[chapter]" to "asc", "order[volume]" to "asc", "includeFutureUpdates" to "0", "contentRating[]" to "safe", "contentRating[]" to "suggestive")
            if (language != "all") params += "translatedLanguage[]" to language
            val response = get("/manga/$mangaId/feed", params)
            val array = response.getJSONArray("data")
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val a = entry.getJSONObject("attributes")
                if (a.optInt("pages") <= 0 || !a.isNull("externalUrl")) continue
                val number = a.optString("chapter").takeUnless { it == "null" }.orEmpty()
                val title = a.optString("title").takeUnless { it == "null" }.orEmpty()
                val label = if (number.isNotEmpty()) "Bölüm $number" else "Özel bölüm"
                result += Chapter(entry.getString("id"), mangaId, if (title.isBlank()) label else "$label · $title", number, a.optString("translatedLanguage", "en"), number.toDoubleOrNull() ?: i.toDouble(), pageCount = a.optInt("pages"))
            }
            offset += array.length()
            val total = response.optInt("total")
        } while (array.length() > 0 && offset < total && offset < 10000)
        // MangaDex exposes each scanlation separately. Show one readable edition per number/language.
        return result.distinctBy { if (it.number.isBlank()) it.id else "${it.number}:${it.language}" }
    }

    suspend fun pages(chapterId: String): List<Page> {
        val response = get("/at-home/server/$chapterId")
        val base = response.getString("baseUrl").trimEnd('/')
        require(base.startsWith("https://")) { "Güvenli olmayan görsel adresi." }
        val chapter = response.getJSONObject("chapter")
        val hash = chapter.getString("hash")
        val files = chapter.getJSONArray("data")
        require(files.length() in 1..ArchiveRules.MAX_PAGES) { "Bu bölümde okunabilir sayfa bulunamadı." }
        return (0 until files.length()).map { Page("$base/data/$hash/${files.getString(it)}") }
    }

    companion object {
        fun parseManga(entry: JSONObject): Manga {
            val a = entry.getJSONObject("attributes")
            fun localized(value: JSONObject): String = value.optString("tr").ifBlank { value.optString("en") }.ifBlank {
                value.keys().asSequence().firstOrNull()?.let { value.optString(it) }.orEmpty()
            }
            val id = entry.getString("id")
            var cover = ""
            val relations = entry.optJSONArray("relationships")
            if (relations != null) for (i in 0 until relations.length()) {
                val r = relations.getJSONObject(i)
                if (r.optString("type") == "cover_art") {
                    val file = r.optJSONObject("attributes")?.optString("fileName").orEmpty()
                    if (file.isNotEmpty()) cover = "https://uploads.mangadex.org/covers/$id/$file.256.jpg"
                }
            }
            return Manga(id, localized(a.getJSONObject("title")), localized(a.optJSONObject("description") ?: JSONObject()), cover)
        }
    }
}
