package com.efeyamann.hscan.data

/** Both endpoints are included; use the currently visible, ordered chapter list. */
fun chapterRange(chapters: List<Chapter>, firstId: String, lastId: String): List<Chapter> {
    val first = chapters.indexOfFirst { it.id == firstId }
    val last = chapters.indexOfFirst { it.id == lastId }
    if (first < 0 || last < first) return emptyList()
    return chapters.subList(first, last + 1)
}

fun Chapter.canDownload(): Boolean = language != "local" &&
    downloadState !in listOf("ready", "queued", "downloading")
