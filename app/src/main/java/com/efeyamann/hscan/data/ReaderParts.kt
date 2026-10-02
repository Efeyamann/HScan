package com.efeyamann.hscan.data

data class ReaderPart(val sourceIndex: Int, val partIndex: Int, val top: Int, val height: Int, val width: Int, val last: Boolean)

/** Keep each rendered image below GPU texture limits while preserving the source page. */
fun readerParts(index: Int, page: Page): List<ReaderPart> {
    if (page.width <= 0 || page.height <= 0) return listOf(ReaderPart(index, 0, 0, 0, 0, true))
    val chunk = minOf(2048, (2_000_000 / page.width).coerceAtLeast(256))
    return (0 until page.height step chunk).mapIndexed { part, top ->
        val height = minOf(chunk, page.height - top)
        ReaderPart(index, part, top, height, page.width, top + height == page.height)
    }
}
