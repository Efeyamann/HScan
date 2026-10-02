package com.efeyamann.hscan

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efeyamann.hscan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo get() = (compose.activity.application as HScanApp).repository

    @Before fun clearLibrary() {
        runBlocking(Dispatchers.IO) { repo.database.clearAllTables() }
        compose.waitForIdle()
    }

    private fun archive(long: Boolean = false): File {
        val archive = File(repo.context.cacheDir, if (long) "Uzun Okuma Testi.cbz" else "Okuma Testi.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf(10, 2, 1).forEach { number ->
                zip.putNextEntry(ZipEntry("$number.png"))
                val bitmap = Bitmap.createBitmap(600, if (long && number == 1) 12000 else 900, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (number == 1) Color.WHITE else if (number == 2) Color.LTGRAY else Color.GRAY)
                val paint = Paint().apply { color = Color.BLACK; textSize = 48f }
                for (y in 100 until bitmap.height step 500) canvas.drawText("HScan test $number / $y", 40f, y.toFloat(), paint)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                bitmap.recycle(); zip.closeEntry()
            }
        }
        return archive
    }

    @Test fun archiveNaturalOrderAndReimportKeepProgress() = runBlocking(Dispatchers.IO) {
        val file = archive()
        val manga = repo.importArchive(Uri.fromFile(file))
        val chapter = repo.dao.chaptersOnce(manga.id).single()
        val pages = repo.readerPages(chapter)
        assertEquals(3, pages.size)
        val bitmap = android.graphics.BitmapFactory.decodeFile(pages.first().uri)
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0)); bitmap.recycle()
        repo.dao.progress(chapter.id, 1, 42, false)
        repo.importArchive(Uri.fromFile(file))
        assertEquals(1, repo.dao.chapter(chapter.id)!!.progressIndex)
        assertEquals(42, repo.dao.chapter(chapter.id)!!.progressOffset)
    }

    @Test fun backupRestoresProgressAndRequiresLocalArchive() = runBlocking(Dispatchers.IO) {
        val file = archive()
        val manga = repo.importArchive(Uri.fromFile(file))
        val chapter = repo.dao.chaptersOnce(manga.id).single()
        repo.dao.progress(chapter.id, 2, 12, true)
        val backup = File(repo.context.cacheDir, "backup.json")
        repo.exportBackup(Uri.fromFile(backup))
        repo.database.clearAllTables()
        repo.restoreBackup(Uri.fromFile(backup))
        val restored = repo.dao.chapter(chapter.id)!!
        assertEquals(2, restored.progressIndex)
        assertTrue(restored.isRead)
        assertEquals("missing", restored.downloadState)
        assertEquals("[]", restored.manifest)
        repo.importArchive(Uri.fromFile(file))
        assertEquals("ready", repo.dao.chapter(chapter.id)!!.downloadState)
        assertEquals(2, repo.dao.chapter(chapter.id)!!.progressIndex)
    }

    @Test fun malformedArchiveIsRejectedWithoutLibraryEntry() = runBlocking(Dispatchers.IO) {
        val archive = File(repo.context.cacheDir, "broken.zip")
        ZipOutputStream(archive.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../escape.jpg")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry() }
        var rejected = false
        try { repo.importArchive(Uri.fromFile(archive)) }
        catch (_: IllegalArgumentException) { rejected = true }
        catch (_: java.util.zip.ZipException) { rejected = true }
        assertTrue(rejected)
        assertTrue(repo.dao.allManga().isEmpty())
        assertFalse(File(repo.context.filesDir, "escape.jpg").exists())
    }

    @Test fun readerScrollRestoreAndTallImageInLandscape() {
        val manga = runBlocking(Dispatchers.IO) { repo.importArchive(Uri.fromFile(archive(long = true))) }
        compose.waitUntil(15000) { compose.onAllNodesWithText(manga.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(manga.title).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Okumaya başla").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Okumaya başla").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("reader-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Kontrolleri gizle").performClick()
        val longChapter = runBlocking(Dispatchers.IO) { repo.dao.chaptersOnce(manga.id).single() }
        val longPage = runBlocking(Dispatchers.IO) { repo.readerPages(longChapter).first() }
        val slice = runBlocking(Dispatchers.IO) { repo.pageSlice(longPage, readerParts(0, longPage).first()) }
        val sliceBounds = repo.dimensions(slice)
        assertEquals(600, sliceBounds.width)
        assertEquals(2048, sliceBounds.height)
        screenshot("reader-long-page.png")
        compose.onNodeWithTag("reader-list").performScrollToIndex(6)
        val id = runBlocking(Dispatchers.IO) { repo.dao.chaptersOnce(manga.id).single().id }
        compose.waitUntil(15000) { runBlocking(Dispatchers.IO) { repo.dao.chapter(id)?.progressIndex == 1 } }
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(15000) { repo.context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("reader-list").assertExists()
        screenshot("reader-landscape.png")
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(15000) { repo.context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        screenshot("reader-portrait.png")
    }

    @Test fun mangaDexSearchFeedAndFirstPageWork() = runBlocking(Dispatchers.IO) {
        val found = repo.api.search("White Tiger and Black Tiger", "en")
        assertTrue("MangaDex search returned no sample", found.isNotEmpty())
        val manga = found.first()
        repo.cacheManga(manga)
        repo.refreshChapters(manga, "en")
        val chapter = repo.dao.chaptersOnce(manga.id).first()
        val pages = repo.readerPages(chapter)
        assertTrue(pages.isNotEmpty())
        val file = repo.pageFile(chapter, 0, pages.first())
        assertTrue(file.width > 0 && file.height > 0)
        repo.performDownload(chapter) { false }
        val downloaded = repo.dao.chapter(chapter.id)!!
        assertEquals("ready", downloaded.downloadState)
        val offlinePages = repo.readerPages(downloaded)
        assertEquals(pages.size, offlinePages.size)
        assertTrue(offlinePages.all { !it.uri.startsWith("https://") && File(it.uri).isFile })
        assertTrue(repo.pageFile(downloaded, 0, offlinePages.first()).width > 0)
    }

    private fun screenshot(name: String) {
        val folder = File(repo.context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, name).outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
