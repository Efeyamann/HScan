package com.efeyamann.hscan.update

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateRelease(val version: Int, val url: String, val size: Long, val sha256: String) {
    fun toJson(): String = JSONObject().put("version", version).put("url", url).put("size", size).put("sha256", sha256).toString()

    companion object {
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        fun fromStored(json: String): UpdateRelease {
            val value = JSONObject(json)
            return validated(value.getInt("version"), value.getString("url"), value.getLong("size"), value.getString("sha256"))
        }
        fun fromGitHub(json: String, installedVersion: Int): UpdateRelease? {
            val value = JSONObject(json)
            if (value.optBoolean("draft") || value.optBoolean("prerelease")) return null
            val version = Regex("build-([1-9][0-9]*)").matchEntire(value.getString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (version <= installedVersion) return null
            val assets = value.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == "HScan.apk" }
                ?: throw IllegalArgumentException("Güncelleme APK'sı bulunamadı.")
            val digest = apk.getString("digest")
            require(digest.startsWith("sha256:")) { "Güncelleme doğrulama bilgisi eksik." }
            return validated(version, apk.getString("browser_download_url"), apk.getLong("size"), digest.removePrefix("sha256:"))
        }
        private fun validated(version: Int, url: String, size: Long, sha256: String): UpdateRelease {
            require(version > 0 && url == "https://github.com/Efeyamann/HScan/releases/download/build-$version/HScan.apk") { "Geçersiz güncelleme kaynağı." }
            require(size in 1..MAX_APK_BYTES && sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Geçersiz güncelleme doğrulama bilgisi." }
            return UpdateRelease(version, url, size, sha256.lowercase())
        }
    }
}

fun verifyUpdatePayload(file: File, release: UpdateRelease) {
    require(file.length() == release.size) { "Güncelleme dosyası eksik veya bozuk. Yeniden indir." }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == release.sha256) { "Güncelleme dosyası doğrulanamadı. Yeniden indir." }
}
