package com.efeyamann.hscan

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.efeyamann.hscan.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext

    @Test fun validatesPackageSignatureVersionAndScopedFileSharing() {
        val manager = UpdateManager(context)
        val apk = File(context.filesDir, "updates/test.apk")
        try {
            File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val hash = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
            val release = UpdateRelease(BuildConfig.VERSION_CODE, "unused", apk.length(), hash)
            manager.validateApk(apk, release, BuildConfig.VERSION_CODE - 1)
            assertThrows(IllegalArgumentException::class.java) { manager.validateApk(apk, release) }
            assertThrows(IllegalArgumentException::class.java) { manager.validateApk(apk, release.copy(version = release.version + 1), BuildConfig.VERSION_CODE - 1) }
            assertThrows(IllegalArgumentException::class.java) { manager.validateApk(apk, release.copy(sha256 = "0".repeat(64)), BuildConfig.VERSION_CODE - 1) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            assertEquals("content", uri.scheme)
            assertEquals(apk.length(), context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { it.length })
            val outside = File(context.filesDir, "private.txt").apply { writeText("private") }
            try {
                assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, "${context.packageName}.updates", outside) }
            } finally { outside.delete() }
            val installer = Intent(context, UpdateInstallActivity::class.java)
            assertFalse(context.packageManager.getActivityInfo(installer.component!!, 0).exported)
        } finally { apk.delete() }
    }

    @Test fun downloadsVerifiesPersistsAndSharesReadyUpdateWithoutExternalNetwork() = runBlocking(Dispatchers.IO) {
        val payload = File(context.applicationInfo.sourceDir).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val release = UpdateRelease(BuildConfig.VERSION_CODE, "https://github.com/Efeyamann/HScan/releases/download/build-${BuildConfig.VERSION_CODE}/HScan.apk", payload.size.toLong(), hash)
        val metadata = JSONObject().put("tag_name", "build-${release.version}").put("assets", JSONArray().put(JSONObject()
            .put("name", "HScan.apk").put("size", release.size).put("digest", "sha256:$hash").put("browser_download_url", release.url))).toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "api.github.com") metadata.toByteArray() else payload
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val prefs = context.getSharedPreferences("updates-test", 0)
        prefs.edit().clear().putBoolean("autoDownload", false).commit()
        val manager = UpdateManager(context, BuildConfig.VERSION_CODE - 1, client, "updates-test")
        try {
            manager.checkLatest()
            assertEquals(release, manager.state.value.release)
            assertFalse(manager.state.value.ready)
            manager.download(release.version) { false }
            assertTrue(manager.state.value.ready)
            assertEquals(100, manager.state.value.percent)
            val intent = manager.installIntent()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("content", intent.data!!.scheme)
            assertEquals(payload.size.toLong(), context.contentResolver.openAssetFileDescriptor(intent.data!!, "r")!!.use { it.length })
            val reopened = UpdateManager(context, BuildConfig.VERSION_CODE - 1, client, "updates-test")
            assertTrue(reopened.state.value.ready)
            val upgraded = UpdateManager(context, BuildConfig.VERSION_CODE, client, "updates-test")
            assertNull(upgraded.state.value.release)
            assertFalse(File(context.filesDir, "updates/hscan-${release.version}.apk").exists())
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun updateSettingsDefaultToAutomaticDownloadOnMobileData() {
        val manager = (compose.activity.application as HScanApp).updates
        manager.setDownloadOptions(true, false)
        compose.onNodeWithContentDescription("Ayarlar").performClick()
        compose.onNodeWithText("Uygulama güncellemeleri").assertIsDisplayed()
        compose.onNodeWithText("Yeni sürümü otomatik indir").assertExists()
        compose.onNodeWithText("Yalnız Wi-Fi üzerinden indir").assertExists()
        assertTrue(manager.autoDownload)
        assertFalse(manager.wifiOnly)
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
    }
}
