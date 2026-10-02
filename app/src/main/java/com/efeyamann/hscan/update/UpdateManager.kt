package com.efeyamann.hscan.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.*
import com.efeyamann.hscan.BuildConfig
import com.efeyamann.hscan.MainActivity
import com.efeyamann.hscan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateState(
    val release: UpdateRelease? = null,
    val ready: Boolean = false,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val percent: Int = 0,
    val error: String? = null,
    val lastChecked: Long = 0,
)

class UpdateManager(
    private val context: Context,
    private val installedVersion: Int = BuildConfig.VERSION_CODE,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build(),
    preferenceName: String = "updates",
) {
    val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    val autoDownload get() = preferences.getBoolean("autoDownload", true)
    val wifiOnly get() = preferences.getBoolean("wifiOnly", false)
    private val folder = File(context.filesDir, "updates").apply { mkdirs() }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(loadState())
    val state = mutableState.asStateFlow()

    private fun apk(release: UpdateRelease) = File(folder, "hscan-${release.version}.apk")
    private fun loadState(): UpdateState {
        val stored = preferences.getString("release", null)?.let { runCatching { UpdateRelease.fromStored(it) }.getOrNull() }
        val release = stored?.takeIf { it.version > installedVersion }
        if (release == null) {
            preferences.edit().remove("release").apply()
            folder.listFiles()?.forEach { it.delete() }
        }
        return UpdateState(release, release != null && apk(release).isFile && apk(release).length() == release.size, lastChecked = preferences.getLong("lastChecked", 0))
    }
    fun schedule() {
        createChannel()
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("hscan-update-check", ExistingPeriodicWorkPolicy.KEEP, periodic)
        checkNow()
    }
    fun checkNow() {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("hscan-update-check-now", ExistingWorkPolicy.KEEP, request)
    }
    fun setDownloadOptions(automatic: Boolean, onlyWifi: Boolean) {
        preferences.edit().putBoolean("autoDownload", automatic).putBoolean("wifiOnly", onlyWifi).apply()
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork("hscan-update-download")
        if (automatic && state.value.release != null && !state.value.ready) queueDownload(ExistingWorkPolicy.REPLACE)
    }
    private fun downloadConstraints() = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    fun queueDownload(policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val release = state.value.release ?: return
        if (state.value.ready) return
        mutableState.value = state.value.copy(error = null)
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>().setConstraints(downloadConstraints())
            .setInputData(workDataOf("version" to release.version)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("hscan-update-download", policy, request)
    }
    suspend fun checkLatest() = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableState.value = state.value.copy(checking = true, error = null)
            try {
                val request = Request.Builder().url("https://api.github.com/repos/Efeyamann/HScan/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "HScan/${BuildConfig.VERSION_CODE}").build()
                val release = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Güncelleme kontrol edilemedi (${response.code}).")
                    UpdateRelease.fromGitHub(response.body?.string() ?: throw IOException("Boş güncelleme yanıtı."), installedVersion)
                }
                val checked = System.currentTimeMillis()
                preferences.edit().putLong("lastChecked", checked).apply()
                if (release != null) {
                    preferences.edit().putString("release", release.toJson()).apply()
                    val ready = apk(release).isFile && apk(release).length() == release.size
                    mutableState.value = UpdateState(release, ready, lastChecked = checked)
                    if (ready) notifyUpdate(true) else if (autoDownload) queueDownload() else notifyUpdate(false)
                } else mutableState.value = state.value.copy(checking = false, lastChecked = checked)
            } catch (e: Exception) {
                mutableState.value = state.value.copy(checking = false, error = e.message ?: "Güncelleme kontrol edilemedi.")
                throw e
            }
        }
    }
    suspend fun download(version: Int, stopped: () -> Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val release = state.value.release?.takeIf { it.version == version } ?: return@withLock
            if (state.value.ready) { notifyUpdate(true); return@withLock }
            val part = File(folder, "hscan-$version.part")
            mutableState.value = state.value.copy(downloading = true, percent = 0, error = null)
            try {
                client.newCall(Request.Builder().url(release.url).header("User-Agent", "HScan/${BuildConfig.VERSION_CODE}").build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Güncelleme indirilemedi (${response.code}).")
                    val body = response.body ?: throw IOException("Güncelleme dosyası boş.")
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            if (stopped()) throw kotlinx.coroutines.CancellationException("İndirme durduruldu.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= release.size && total <= UpdateRelease.MAX_APK_BYTES) { "Güncelleme dosyası beklenenden büyük." }
                            output.write(buffer, 0, count)
                            mutableState.value = state.value.copy(percent = (total * 100 / release.size).toInt())
                        }
                    } }
                }
                validateApk(part, release)
                check(part.renameTo(apk(release))) { "Güncelleme kaydedilemedi." }
                folder.listFiles()?.filter { it != apk(release) }?.forEach { it.delete() }
                mutableState.value = state.value.copy(ready = true, downloading = false, percent = 100)
                notifyUpdate(true)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                mutableState.value = state.value.copy(downloading = false)
                throw cancel
            } catch (e: Exception) {
                mutableState.value = state.value.copy(downloading = false, error = e.message ?: "Güncelleme indirilemedi.")
                throw e
            } finally { part.delete() }
        }
    }
    @Suppress("DEPRECATION")
    fun validateApk(file: File, release: UpdateRelease, installedVersion: Int = this.installedVersion) {
        verifyUpdatePayload(file, release)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Güncelleme APK'sı okunamadı.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && version == release.version.toLong() && version > installedVersion) { "Güncelleme uygulama veya sürüm bilgisi uyuşmuyor." }
        fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        val currentSigners = signers(installed)
        require(currentSigners.isNotEmpty() && currentSigners == signers(archive)) { "Güncellemenin imzası HScan ile uyuşmuyor." }
    }
    suspend fun installIntent(): Intent = withContext(Dispatchers.IO) {
        mutex.withLock {
            val release = state.value.release ?: throw IOException("İndirilen güncelleme bulunamadı.")
            val file = apk(release)
            try { validateApk(file, release) }
            catch (e: Exception) {
                file.delete()
                mutableState.value = state.value.copy(ready = false, error = e.message)
                throw e
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = android.content.ClipData.newRawUri("HScan güncellemesi", uri) }
        }
    }
    fun notificationsEnabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    private fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("updates", "Uygulama güncellemeleri", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Yeni HScan sürümleri ve kuruluma hazır güncellemeler" })
    }
    fun notifyUpdate(ready: Boolean = state.value.ready) {
        val release = state.value.release ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!notificationsEnabled()) return
        val marker = "${release.version}:$ready"
        if (preferences.getString("notified", null) == marker) return
        createChannel()
        val intent = if (ready) Intent(context, UpdateInstallActivity::class.java) else Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, 701, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "updates").setSmallIcon(R.drawable.ic_update)
            .setContentTitle(if (ready) "HScan güncellemesi hazır" else "Yeni HScan sürümü var")
            .setContentText(if (ready) "Build ${release.version} indirildi. Kurmak için dokun." else "Build ${release.version} hazır. Uygulamadan indirebilirsin.")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        NotificationManagerCompat.from(context).notify(701, notification)
        preferences.edit().putString("notified", marker).apply()
    }
}
