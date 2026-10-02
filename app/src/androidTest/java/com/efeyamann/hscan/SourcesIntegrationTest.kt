package com.efeyamann.hscan

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.efeyamann.hscan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SourcesIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationKeepsLibraryAndReadingProgress() = runBlocking(Dispatchers.IO) {
        val name = "migration-source-test.db"
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.efeyamann.hscan.data.ReaderDatabase/1.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) db.execSQL(entities.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entities.getJSONObject(i).getString("tableName")))
            db.execSQL("CREATE INDEX index_chapter_mangaId ON chapter(mangaId)")
            db.execSQL("INSERT INTO manga VALUES ('old', 'Old', '', '', 'mangadex', 1, 'Okuyorum', 'chapter', 42, 1)")
            db.execSQL("INSERT INTO chapter VALUES ('chapter', 'old', 'Chapter', '339', 'en', 339, '[]', 'none', 0, 20, 7, 123, 0, '')")
            db.version = 1
        }
        val db = Room.databaseBuilder(context, ReaderDatabase::class.java, name).addMigrations(SOURCE_MIGRATION).build()
        try {
            assertTrue(db.dao().manga("old")!!.inLibrary)
            assertEquals("chapter", db.dao().manga("old")!!.lastChapterId)
            assertEquals(7, db.dao().chapter("chapter")!!.progressIndex)
            assertEquals(123, db.dao().chapter("chapter")!!.progressOffset)
            assertEquals("", db.dao().manga("old")!!.sourceUrl)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun bothSitesSearchReadDownloadAndBackup() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = ReaderRepository(context, db)
        try {
            for ((source, query, title) in listOf(Triple("mangabats", "ultimate prodigy", "The Ultimate Prodigy"), Triple("mangabuddy", "primal hunter", "The Primal Hunter"))) {
                val manga = repo.webSources.getValue(source).search(query).first { it.title.equals(title, ignoreCase = true) }.copy(inLibrary = true)
                repo.cacheManga(manga)
                repo.refreshChapters(manga, "tr")
                val chapter = repo.dao.chaptersOnce(manga.id).first()
                assertTrue(chapter.sourceUrl.startsWith(manga.sourceUrl + "/"))
                val pages = repo.readerPages(chapter)
                assertTrue(pages.isNotEmpty())
                val saved = repo.pageFile(chapter, 0, pages.first())
                assertTrue(saved.width > 0 && saved.height > 0)
                assertTrue(repo.performDownload(chapter) { false })
                assertTrue(repo.readerPages(repo.dao.chapter(chapter.id)!!).all { File(it.uri).isFile })
                repo.dao.progress(chapter.id, 1, 42, false)
            }
            val backup = File(context.cacheDir, "source-backup.json")
            repo.exportBackup(Uri.fromFile(backup))
            val old = repo.dao.allChapters().associateBy { it.id }
            db.clearAllTables()
            repo.restoreBackup(Uri.fromFile(backup))
            assertEquals(2, repo.dao.allManga().size)
            repo.dao.allChapters().forEach { assertEquals(old[it.id]!!.sourceUrl, it.sourceUrl); assertEquals(old[it.id]!!.progressIndex, it.progressIndex) }
        } finally { db.close() }
    }
}
