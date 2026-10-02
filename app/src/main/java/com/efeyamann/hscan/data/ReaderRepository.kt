package com.efeyamann.hscan.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class ReaderRepository(val context: Context, val database: ReaderDatabase) {
    val dao = database.dao()
    val api = MangaDex()
    private val locks = ConcurrentHashMap<String, Mutex>()
    val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun cacheManga(manga: Manga) {
        val old = dao.manga(manga.id)
        dao.putManga(if (old == null) manga else old.copy(title = manga.title, description = manga.description, cover = manga.cover))
    }
    suspend fun refreshChapters(manga: Manga, language: String) {
        if (manga.source == "local") return
        val fetched = api.chapters(manga.id, language)
        database.withTransaction {
            fetched.forEach { fresh ->
                val old = dao.chapter(fresh.id)
                dao.putChapter(old?.copy(title = fresh.title, number = fresh.number, language = fresh.language, order = fresh.order, pageCount = fresh.pageCount) ?: fresh)
            }
        }
    }

    suspend fun readerPages(chapter: Chapter): List<Page> = withContext(Dispatchers.IO) {
        if (chapter.downloadState == "ready" || chapter.language == "local") {
            val pages = pagesFromJson(chapter.manifest)
            if (pages.isNotEmpty() && pages.all { File(it.uri).isFile }) return@withContext pages
            if (chapter.language == "local") throw IOException("Yerel dosyalar bulunamadı. Aynı CBZ/ZIP dosyasını tekrar içe aktar.")
            dao.download(chapter.id, "error", 0, chapter.pageCount, "İndirilen dosyalar bulunamadı.")
        }
        api.pages(chapter.id).mapIndexed { index, remote ->
            val cached = File(directory(chapter.id, false), "%05d.img".format(index))
            if (cached.isFile) runCatching { dimensions(cached).copy(uri = remote.uri) }.getOrDefault(remote) else remote
        }
    }

    fun directory(id: String, permanent: Boolean): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Geçersiz bölüm kimliği." }
        return File(if (permanent) context.filesDir else context.cacheDir, "chapters/$id")
    }

    suspend fun pageFile(chapter: Chapter, index: Int, page: Page, permanent: Boolean = false): Page = withContext(Dispatchers.IO) {
        if (!page.uri.startsWith("https://")) {
            val file = File(page.uri)
            require(file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator) || file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)) { "Geçersiz yerel sayfa yolu." }
            return@withContext dimensions(file)
        }
        val dir = directory(chapter.id, permanent).apply { mkdirs() }
        val file = File(dir, "%05d.img".format(index))
        locks.getOrPut(file.absolutePath) { Mutex() }.withLock {
            if (file.exists()) return@withLock dimensions(file)
            val cached = File(directory(chapter.id, !permanent), file.name)
            if (cached.isFile) {
                cached.copyTo(file, overwrite = true)
                return@withLock dimensions(file)
            }
            val part = File(dir, file.name + ".part")
            try {
                api.client.newCall(Request.Builder().url(page.uri).header("User-Agent", "HScan/0.1").build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Sayfa yüklenemedi (${response.code}). Tekrar dene.")
                    val body = response.body ?: throw IOException("Boş sayfa yanıtı.")
                    if (body.contentLength() > ArchiveRules.MAX_PAGE_BYTES) throw IOException("Görsel dosyası çok büyük.")
                    body.byteStream().use { input -> part.outputStream().use { output -> copyLimited(input, output, ArchiveRules.MAX_PAGE_BYTES) } }
                }
                dimensions(part)
                check(part.renameTo(file)) { "Sayfa kaydedilemedi." }
                dimensions(file)
            } finally { part.delete() }
        }
    }

    fun dimensions(file: File): Page {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Bu görsel okunamıyor.")
        return Page(file.absolutePath, options.outWidth, options.outHeight)
    }

    fun saveProgress(chapter: Chapter, index: Int, offset: Int, read: Boolean) {
        scope.launch {
            database.withTransaction {
                val existing = dao.chapter(chapter.id) ?: return@withTransaction
                dao.progress(chapter.id, index, offset, existing.isRead || read)
                dao.lastRead(chapter.mangaId, chapter.id, System.currentTimeMillis())
            }
        }
    }

    fun enqueue(chapter: Chapter) {
        val work = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setInputData(workDataOf("chapterId" to chapter.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("hscan-download")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("chapter-${chapter.id}", ExistingWorkPolicy.KEEP, work)
        scope.launch { if (dao.chapter(chapter.id)?.downloadState !in listOf("ready", "downloading")) dao.download(chapter.id, "queued", chapter.downloadCount, chapter.pageCount) }
    }

    suspend fun deleteDownload(chapter: Chapter) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("chapter-${chapter.id}").result.await()
        // The worker locks the chapter too, so cleanup never races an active file rename.
        locks.getOrPut("chapter-${chapter.id}") { Mutex() }.withLock {
            directory(chapter.id, true).deleteRecursively()
            val state = if (chapter.language == "local") "missing" else "none"
            dao.download(chapter.id, state, 0, chapter.pageCount)
            val now = dao.chapter(chapter.id) ?: return@withLock
            dao.putChapter(now.copy(manifest = "[]"))
        }
    }

    suspend fun performDownload(chapter: Chapter, stopped: () -> Boolean): Boolean {
        return locks.getOrPut("chapter-${chapter.id}") { Mutex() }.withLock {
            val pages = api.pages(chapter.id)
            dao.download(chapter.id, "downloading", 0, pages.size)
            val saved = mutableListOf<Page>()
            for ((index, page) in pages.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (stopped()) throw CancellationException("İndirme durduruldu.")
                saved += pageFile(chapter, index, page, permanent = true)
                dao.download(chapter.id, "downloading", index + 1, pages.size)
                delay(150)
            }
            database.withTransaction {
                val fresh = dao.chapter(chapter.id) ?: chapter
                dao.putChapter(fresh.copy(manifest = saved.toJson(), downloadState = "ready", downloadCount = saved.size, pageCount = saved.size, error = ""))
            }
            true
        }
    }

    suspend fun importArchive(uri: Uri): Manga = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Yerel manga.cbz"
        val tmp = File(context.cacheDir, "import-${UUID.randomUUID()}.zip")
        val staging = File(context.filesDir, "imports/${UUID.randomUUID()}").apply { mkdirs() }
        var finalDir: File? = null
        var committed = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { copyLimited(input, it, ArchiveRules.MAX_TOTAL_BYTES) } } ?: throw IOException("Dosya açılamadı.")
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(32)
            val mangaId = "local-$hash"
            val chapterId = "local-chapter-$hash"
            val pages = mutableListOf<Page>()
            ZipFile(tmp).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.take(10001).toList()
                require(entries.size <= 10000) { "Arşivde çok fazla dosya var." }
                entries.forEach { ArchiveRules.safeDestination(staging, it.name) }
                val images = entries.filter { ArchiveRules.isImage(it.name) }.sortedWith { a, b -> ArchiveRules.naturalOrder.compare(a.name, b.name) }
                require(images.size in 1..ArchiveRules.MAX_PAGES) { "Arşivde okunabilir görsel yok veya sayfa sayısı çok fazla." }
                var total = 0L
                for ((index, entry) in images.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val target = File(staging, "%05d.img".format(index))
                    val bytes = zip.getInputStream(entry).use { input -> target.outputStream().use { copyLimited(input, it, ArchiveRules.MAX_PAGE_BYTES) } }
                    total += bytes
                    require(total <= ArchiveRules.MAX_TOTAL_BYTES) { "Arşivin açılmış boyutu çok büyük." }
                    pages += dimensions(target)
                }
            }
            val destination = directory(chapterId, true)
            destination.parentFile?.mkdirs()
            val oldChapter = dao.chapter(chapterId)
            if (destination.exists()) destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Arşiv kaydedilemedi." }
            finalDir = destination
            val moved = pages.map { it.copy(uri = File(destination, File(it.uri).name).absolutePath) }
            val oldManga = dao.manga(mangaId)
            val manga = (oldManga ?: Manga(mangaId, name.substringBeforeLast('.'), source = "local")).copy(cover = moved.first().uri, inLibrary = true)
            database.withTransaction {
                dao.putManga(manga)
                dao.putChapter((oldChapter ?: Chapter(chapterId, mangaId, "${moved.size} sayfa", language = "local")).copy(manifest = moved.toJson(), downloadState = "ready", downloadCount = moved.size, pageCount = moved.size, error = ""))
            }
            committed = true
            manga
        } finally {
            tmp.delete(); staging.deleteRecursively()
            if (!committed) finalDir?.deleteRecursively()
        }
    }

    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val root = JSONObject().put("format", "hscan").put("version", 1)
        val manga = JSONArray()
        val chapters = JSONArray()
        database.withTransaction {
            val library = dao.allManga().filter { it.inLibrary }
            val ids = library.map { it.id }.toSet()
            library.forEach { m -> manga.put(JSONObject().put("id", m.id).put("title", m.title).put("description", m.description).put("cover", if (m.source == "local") "" else m.cover).put("source", m.source).put("status", m.readingStatus).put("lastChapter", m.lastChapterId).put("lastRead", m.lastReadAt)) }
            dao.allChapters().filter { it.mangaId in ids }.forEach { c -> chapters.put(JSONObject().put("id", c.id).put("mangaId", c.mangaId).put("title", c.title).put("number", c.number).put("language", c.language).put("order", c.order).put("index", c.progressIndex).put("offset", c.progressOffset).put("read", c.isRead)) }
        }
        root.put("manga", manga).put("chapters", chapters)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) } ?: throw IOException("Yedek dosyası yazılamadı.")
    }

    suspend fun clearPageCache() = withContext(Dispatchers.IO) {
        File(context.cacheDir, "chapters").deleteRecursively()
    }

    suspend fun restoreBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(); copyLimited(input, output, 10L * 1024 * 1024); output.toByteArray()
        } ?: throw IOException("Yedek dosyası açılamadı.")
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optString("format") == "hscan" && root.optInt("version") == 1) { "Geçerli bir HScan yedeği değil." }
        val mangas = root.getJSONArray("manga")
        val chapters = root.getJSONArray("chapters")
        require(mangas.length() <= 10000 && chapters.length() <= 100000) { "Yedek çok büyük." }
        fun validId(id: String): String { require(id.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Yedekte geçersiz kimlik var." }; return id }
        database.withTransaction {
            val ids = mutableSetOf<String>()
            for (i in 0 until mangas.length()) {
                val m = mangas.getJSONObject(i)
                val id = validId(m.getString("id")); ids += id
                val source = m.getString("source")
                require(source in listOf("local", "mangadex"))
                val old = dao.manga(id)
                val cover = m.optString("cover").takeIf { it.startsWith("https://uploads.mangadex.org/covers/") }.orEmpty()
                dao.putManga((old ?: Manga(id, m.getString("title"), m.optString("description"), cover, source)).copy(inLibrary = true, readingStatus = m.optString("status", "Okuyorum"), lastChapterId = validId(m.optString("lastChapter").ifBlank { "none" }).takeUnless { it == "none" }.orEmpty(), lastReadAt = m.optLong("lastRead")))
            }
            for (i in 0 until chapters.length()) {
                val c = chapters.getJSONObject(i)
                val id = validId(c.getString("id")); val mangaId = validId(c.getString("mangaId"))
                require(mangaId in ids)
                val old = dao.chapter(id)
                val language = c.optString("language", "en")
                dao.putChapter((old ?: Chapter(id, mangaId, c.getString("title"), c.optString("number"), language, c.optDouble("order", 0.0), downloadState = if (language == "local") "missing" else "none")).copy(progressIndex = c.optInt("index").coerceAtLeast(0), progressOffset = c.optInt("offset").coerceAtLeast(0), isRead = c.optBoolean("read")))
            }
        }
    }

    companion object {
        fun copyLimited(input: InputStream, output: OutputStream, max: Long): Long {
            val buffer = ByteArray(65536); var total = 0L
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= max) { "Dosya boyutu sınırı aşıldı." }; output.write(buffer, 0, count) }
            return total
        }
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener({ runCatching { get() }.fold({ continuation.resumeWith(Result.success(it)) }, { continuation.resumeWith(Result.failure(it)) }) }, java.util.concurrent.Executor { it.run() })
    continuation.invokeOnCancellation { cancel(true) }
}
