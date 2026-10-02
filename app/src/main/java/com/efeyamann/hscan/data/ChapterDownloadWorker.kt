package com.efeyamann.hscan.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.efeyamann.hscan.HScanApp
import kotlinx.coroutines.CancellationException

class ChapterDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as HScanApp).repository
        val id = inputData.getString("chapterId") ?: return Result.failure()
        val chapter = repository.dao.chapter(id) ?: return Result.failure()
        if (chapter.downloadState == "ready") return Result.success()
        return try {
            repository.performDownload(chapter) { isStopped }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val fresh = repository.dao.chapter(id) ?: chapter
            repository.dao.download(id, if (runAttemptCount < 2) "queued" else "error", fresh.downloadCount, fresh.pageCount, error.message ?: "İndirme tamamlanamadı.")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
