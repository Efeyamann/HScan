package com.efeyamann.hscan

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.efeyamann.hscan.data.*
import com.efeyamann.hscan.ui.ChapterRangeDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChapterRangeIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chooseRangeSearchValidateAndConfirmOnce() {
        val chapters = listOf(
            Chapter("one", "m", "Bölüm 1", "1", downloadState = "ready"),
            Chapter("two", "m", "Bölüm 2", "2"),
            Chapter("special", "m", "Özel bölüm"),
            Chapter("three", "m", "Bölüm 3", "3", downloadState = "queued"),
            Chapter("four", "m", "Bölüm 4", "4"),
        )
        var selected: List<Chapter>? = null
        var confirmations = 0
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { ChapterRangeDialog(chapters, {}, { selected = it; confirmations++ }) } }
        compose.onNodeWithText("5 bölüm seçildi · 3 bölüm indirilecek").assertExists()
        compose.onNodeWithText("Başlangıç:", substring = true).performClick()
        compose.onNodeWithText("Bölüm adı veya numarası ara").performTextInput("2")
        compose.onNodeWithText("Bölüm 2 · EN").performClick()
        compose.onNodeWithText("Bitiş:", substring = true).performClick()
        compose.onNodeWithText("Bölüm 1 · EN").performClick()
        compose.onNodeWithTag("download-range-confirm").assertIsNotEnabled()
        compose.onNodeWithText("Bitiş:", substring = true).performClick()
        compose.onNodeWithText("Bölüm 3 · EN").performClick()
        compose.onNodeWithText("3 bölüm seçildi · 2 bölüm indirilecek").assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            .resolve("chapter-range.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("download-range-confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf("two", "special", "three"), selected!!.map { it.id })
            assertEquals(1, confirmations)
        }
    }

    @Test fun bulkQueueUsesFreshStateSkipsDuplicatesAndKeepsProgress() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as HScanApp).repository
        val workManager = WorkManager.getInstance(context)
        repo.database.clearAllTables()
        val prefix = UUID.randomUUID().toString()
        // Unsupported test source fails locally before any network request; work stays queued for retry.
        val manga = Manga(prefix, "Queue test", source = "test")
        val ready = Chapter("$prefix-ready", prefix, "Ready", downloadState = "ready", progressIndex = 7)
        val first = Chapter("$prefix-first", prefix, "First", progressIndex = 3, isRead = true)
        val last = Chapter("$prefix-last", prefix, "Last", downloadState = "error")
        val chapters = listOf(ready, first, last)
        try {
            repo.dao.putManga(manga)
            chapters.forEach { repo.dao.putChapter(it) }
            assertEquals(2, repo.enqueueChapters(chapters + first))
            assertTrue(repo.dao.manga(prefix)!!.inLibrary)
            assertEquals("ready", repo.dao.chapter(ready.id)!!.downloadState)
            assertEquals(7, repo.dao.chapter(ready.id)!!.progressIndex)
            assertEquals(3, repo.dao.chapter(first.id)!!.progressIndex)
            assertTrue(repo.dao.chapter(first.id)!!.isRead)
            assertEquals(0, repo.enqueueChapters(chapters)) // stale objects must not enqueue again
            assertEquals(1, workManager.getWorkInfosForUniqueWork("chapter-${first.id}").get().size)
            assertEquals(1, workManager.getWorkInfosForUniqueWork("chapter-${last.id}").get().size)
            assertTrue(workManager.getWorkInfosForUniqueWork("chapter-${ready.id}").get().isEmpty())
        } finally {
            chapters.forEach { workManager.cancelUniqueWork("chapter-${it.id}").result.get() }
        }
    }
}
