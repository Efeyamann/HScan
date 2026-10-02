package com.efeyamann.hscan.data

import java.io.File
import java.util.Locale

object ArchiveRules {
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    const val MAX_PAGES = 5000
    const val MAX_PAGE_BYTES = 50L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions && !name.startsWith("__MACOSX/")
    fun safeDestination(root: File, name: String): File {
        require(!name.contains('\\') && !name.startsWith('/') && !name.contains(':')) { "Geçersiz arşiv yolu." }
        val target = File(root, name).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "Arşiv klasör dışına yazmaya çalışıyor." }
        return target
    }
    val naturalOrder = Comparator<String> { a, b ->
        val regex = Regex("\\d+|\\D+")
        val aa = regex.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val bb = regex.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        var result = 0
        for (i in 0 until minOf(aa.size, bb.size)) {
            val x = aa[i]; val y = bb[i]
            result = if (x.first().isDigit() && y.first().isDigit()) {
                val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
                xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
            } else x.compareTo(y)
            if (result != 0) break
        }
        if (result != 0) result else aa.size.compareTo(bb.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
