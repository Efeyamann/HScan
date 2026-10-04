package com.efeyamann.hscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.efeyamann.hscan.data.*

@Composable
internal fun ChapterRangeDialog(chapters: List<Chapter>, onDismiss: () -> Unit, onDownload: (List<Chapter>) -> Unit) {
    var firstId by rememberSaveable { mutableStateOf(chapters.first().id) }
    var lastId by rememberSaveable { mutableStateOf(chapters.last().id) }
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = chapterRange(chapters, firstId, lastId)
    val count = selected.count { it.canDownload() }
    val first = chapters.firstOrNull { it.id == firstId }
    val last = chapters.firstOrNull { it.id == lastId }

    if (choosing != null) {
        val start = choosing == "first"
        ChapterEndpointPicker(chapters, if (start) firstId else lastId,
            title = if (start) "Başlangıç bölümü" else "Bitiş bölümü",
            onDismiss = { choosing = null },
            onSelect = { if (start) firstId = it.id else lastId = it.id; choosing = null },
        )
    } else AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aralık indir") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Başlangıç ve bitiş dahil, listedeki tüm ara bölümler indirilir. Seçili dil filtresi geçerlidir.")
                OutlinedButton(onClick = { choosing = "first" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Başlangıç: ${first?.rangeLabel() ?: "Bölüm seç"}")
                }
                OutlinedButton(onClick = { choosing = "last" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Bitiş: ${last?.rangeLabel() ?: "Bölüm seç"}")
                }
                if (selected.isEmpty()) Text("Bitiş bölümü başlangıçtan önce olamaz. Bölümleri yeniden seç.", color = MaterialTheme.colorScheme.error)
                else {
                    Text("${selected.size} bölüm seçildi · $count bölüm indirilecek")
                    Text("İndirilmiş veya kuyruktaki bölümler atlanır. İlerlemeyi İndirilenler ekranından takip edebilirsin.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(selected.toList()) }, enabled = count > 0, modifier = Modifier.testTag("download-range-confirm")) {
                Text("$count bölümü indir")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

private fun Chapter.rangeLabel(): String = "$title · ${language.uppercase()}"

@Composable
private fun ChapterEndpointPicker(chapters: List<Chapter>, selectedId: String, title: String, onDismiss: () -> Unit, onSelect: (Chapter) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = chapters.filter { query.isBlank() || it.title.contains(query, true) || it.number.contains(query, true) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
    LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Bölüm adı veya numarası ara") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 300.dp).testTag("range-endpoints")) {
                    items(filtered, key = { it.id }) { chapter ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(chapter) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chapter.id == selectedId, onClick = { onSelect(chapter) })
                            Text(chapter.rangeLabel(), Modifier.weight(1f))
                        }
                    }
                }
                if (filtered.isEmpty()) Text("Bölüm bulunamadı.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Geri") } },
    )
}
