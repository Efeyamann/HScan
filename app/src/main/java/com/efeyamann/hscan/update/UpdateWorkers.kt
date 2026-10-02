package com.efeyamann.hscan.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.efeyamann.hscan.HScanApp
import kotlinx.coroutines.CancellationException

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as HScanApp).updates.checkLatest()
        Result.success()
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
}

class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as HScanApp).updates.download(inputData.getInt("version", 0)) { isStopped }
        Result.success()
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: IllegalArgumentException) { Result.failure() }
    catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
}
