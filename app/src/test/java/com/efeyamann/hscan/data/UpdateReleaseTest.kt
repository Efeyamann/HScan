package com.efeyamann.hscan.data

import com.efeyamann.hscan.update.UpdateRelease
import com.efeyamann.hscan.update.verifyUpdatePayload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateReleaseTest {
    private fun release(version: Int = 9): JSONObject = JSONObject().put("tag_name", "build-$version")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray().put(JSONObject()
            .put("name", "HScan.apk").put("size", 3).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/Efeyamann/HScan/releases/download/build-$version/HScan.apk")))
    @Test fun newerStableBuildHasVerifiedAssetMetadata() {
        val latest = UpdateRelease.fromGitHub(release().toString(), 8)!!
        assertEquals(9, latest.version)
        assertEquals(latest, UpdateRelease.fromStored(latest.toJson()))
    }
    @Test fun neverOffersOldEqualDraftOrPrereleaseBuilds() {
        assertNull(UpdateRelease.fromGitHub(release(8).toString(), 8))
        assertNull(UpdateRelease.fromGitHub(release(7).toString(), 8))
        assertNull(UpdateRelease.fromGitHub(release().put("draft", true).toString(), 8))
        assertNull(UpdateRelease.fromGitHub(release().put("prerelease", true).toString(), 8))
        assertNull(UpdateRelease.fromGitHub(release().put("tag_name", "v0.1").toString(), 8))
    }
    @Test fun rejectsForeignSourcesMissingDigestsAndOversizedAssets() {
        listOf("https://evil.example/HScan.apk", "http://github.com/Efeyamann/HScan/releases/download/build-9/HScan.apk",
            "https://github.com/other/HScan/releases/download/build-9/HScan.apk").forEach { url ->
            val data = release(); data.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.fromGitHub(data.toString(), 8) }
        }
        val missing = release(); missing.getJSONArray("assets").getJSONObject(0).put("digest", "")
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.fromGitHub(missing.toString(), 8) }
        val huge = release(); huge.getJSONArray("assets").getJSONObject(0).put("size", UpdateRelease.MAX_APK_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.fromGitHub(huge.toString(), 8) }
    }
    @Test fun rejectsMissingOrAmbiguousApkAssets() {
        val missing = release().put("assets", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.fromGitHub(missing.toString(), 8) }
        val duplicate = release(); val assets = duplicate.getJSONArray("assets"); assets.put(assets.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.fromGitHub(duplicate.toString(), 8) }
    }
    @Test fun verifiesBytesAndRejectsTamperedOrTruncatedDownload() {
        val file = File.createTempFile("hscan-update", ".apk")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val expected = UpdateRelease(9, "https://github.com/Efeyamann/HScan/releases/download/build-9/HScan.apk", 3, hash)
            verifyUpdatePayload(file, expected)
            file.writeBytes(byteArrayOf(1, 2))
            assertThrows(IllegalArgumentException::class.java) { verifyUpdatePayload(file, expected) }
            file.writeBytes(byteArrayOf(1, 2, 4))
            assertThrows(IllegalArgumentException::class.java) { verifyUpdatePayload(file, expected) }
        } finally { file.delete() }
    }
}
