@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.efeyamann.hscan.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.size.Scale
import androidx.compose.ui.platform.LocalContext
import com.efeyamann.hscan.AppModel
import com.efeyamann.hscan.BuildConfig
import com.efeyamann.hscan.data.*
import kotlinx.coroutines.CancellationException
import java.io.File

private val ReaderColors = darkColorScheme(
    primary = Color(0xFFB6C4FF), onPrimary = Color(0xFF10265D),
    secondary = Color(0xFFA8D5C5), background = Color(0xFF111318),
    surface = Color(0xFF111318), surfaceContainer = Color(0xFF1D2028),
    onSurface = Color(0xFFE3E2E9), onSurfaceVariant = Color(0xFFC5C6D0),
)

@Composable
fun HScanUI(model: AppModel = viewModel()) {
    MaterialTheme(colorScheme = ReaderColors) {
        val screenStates = rememberSaveableStateHolder()
        val repo = model.repository
        UpdateNotificationPrompt(model.updates)
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var mangaId by rememberSaveable { mutableStateOf<String?>(null) }
        var chapterId by rememberSaveable { mutableStateOf<String?>(null) }
        var settings by rememberSaveable { mutableStateOf(false) }
        var gap by remember { mutableStateOf(repo.preferences.getBoolean("pageGap", false)) }
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { model.messages.collect { snack.showSnackbar(it) } }
        LaunchedEffect(model.importedMangaId) {
            model.importedMangaId?.let { mangaId = it; model.importedMangaId = null }
        }
        val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
        val backupWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) model.action { repo.exportBackup(uri); model.notice("Kütüphane ve ilerleme yedeklendi.") }
        }
        val backupReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.action { repo.restoreBackup(uri); model.notice("Yedek geri yüklendi.") }
        }
        val library by remember { repo.dao.library() }.collectAsStateWithLifecycle(emptyList())
        val latestRead by remember { repo.dao.latestRead() }.collectAsStateWithLifecycle(null)
        val downloads by remember { repo.dao.downloads() }.collectAsStateWithLifecycle(emptyList())
        val openManga: (Manga) -> Unit = { m -> model.open(m) { mangaId = m.id } }
        val selectedChapter = chapterId
        if (selectedChapter != null) {
            ReaderScreen(selectedChapter, repo, gap, onBack = { chapterId = null }, onNext = { chapterId = it })
        } else {
            BackHandler(enabled = mangaId != null || tab != 0) { if (mangaId != null) mangaId = null else tab = 0 }
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                topBar = {
                    TopAppBar(
                        title = { Text(if (mangaId != null) "Seri" else listOf("HScan", "Seri ara", "İndirilenler")[tab], fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { if (mangaId != null) IconButton(onClick = { mangaId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Geri") } },
                        actions = {
                            IconButton(onClick = { archivePicker.launch(arrayOf("*/*")) }, enabled = !model.importing) { Icon(Icons.Outlined.Add, "CBZ veya ZIP içe aktar") }
                            IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Ayarlar") }
                        },
                    )
                },
                bottomBar = {
                    if (mangaId == null) NavigationBar {
                        listOf(Triple("Kütüphane", Icons.AutoMirrored.Outlined.MenuBook, 0), Triple("Kaynaklar", Icons.Outlined.Explore, 1), Triple("İndirilenler", Icons.Outlined.Download, 2)).forEach { (name, icon, value) ->
                            NavigationBarItem(selected = tab == value, onClick = { tab = value }, icon = { Icon(icon, null) }, label = { Text(name) })
                        }
                    }
                },
            ) { insets ->
                Column(Modifier.fillMaxSize().padding(insets)) {
                    if (model.importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Arşiv içe aktarılıyor…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    UpdateBanner(model.updates)
                    val selected = mangaId
                    screenStates.SaveableStateProvider(selected?.let { "detail:$it" } ?: "tab:$tab") {
                        if (selected != null) DetailScreen(selected, model, onRead = { chapterId = it })
                        else when (tab) {
                            0 -> LibraryScreen(library, latestRead, repo, onOpen = openManga, onContinue = { chapterId = it }, onDiscover = { tab = 1 }, onImport = { archivePicker.launch(arrayOf("*/*")) })
                            1 -> SourceScreen(model, onOpen = openManga)
                            2 -> DownloadsScreen(downloads, library, model, onRead = { chapterId = it })
                        }
                    }
                }
            }
        }
        if (settings) AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("Ayarlar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    UpdateSettings(model.updates)
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sayfalar arasında boşluk", Modifier.weight(1f))
                        Switch(checked = gap, onCheckedChange = { gap = it; repo.preferences.edit().putBoolean("pageGap", it).apply() })
                    }
                    Text("Yedek, kütüphaneni ve okuma ilerlemeni içerir. Görseller dahil değildir. Yerel arşivleri ayrıca sakla.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { settings = false; backupWriter.launch("hscan-yedek.json") }, modifier = Modifier.fillMaxWidth()) { Text("Yedek oluştur") }
                    OutlinedButton(onClick = { settings = false; backupReader.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Yedeği geri yükle") }
                    TextButton(onClick = { settings = false; model.action { repo.clearPageCache(); model.notice("Geçici sayfalar temizlendi. İndirilenler korundu.") } }, modifier = Modifier.fillMaxWidth()) { Text("Geçici sayfaları temizle") }
                    Text("HScan ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}\nMangaDex · MangaBats · MangaBuddy · Türkçe arayüz", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { settings = false }) { Text("Tamam") } },
        )
    }
}

@Composable
private fun ContinueCard(manga: Manga, repo: ReaderRepository, onContinue: (String) -> Unit) {
    val chapter by remember(manga.lastChapterId) { repo.dao.observeChapter(manga.lastChapterId) }.collectAsStateWithLifecycle(null)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kaldığın yerden", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(manga.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            chapter?.let { Text("${it.title} · Sayfa ${it.progressIndex + 1}", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { onContinue(manga.lastChapterId) }, modifier = Modifier.fillMaxWidth()) { Text("Devam et") }
        }
    }
}

@Composable
private fun LibraryScreen(library: List<Manga>, latest: Manga?, repo: ReaderRepository, onOpen: (Manga) -> Unit, onContinue: (String) -> Unit, onDiscover: () -> Unit, onImport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("Tümü") }
    Column(Modifier.fillMaxSize()) {
        if (latest != null && query.isBlank() && status == "Tümü") ContinueCard(latest, repo, onContinue)
        if (library.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyState(Icons.AutoMirrored.Outlined.MenuBook, "İlk serini ekle", "Kaynaklardan bir seri bul veya telefondan CBZ/ZIP dosyası aç.") {
                    Button(onClick = onDiscover) { Text("Kaynaklara göz at") }
                    OutlinedButton(onClick = onImport) { Text("Dosya içe aktar") }
                }
            }
        } else {
            SearchField(query, { query = it }, "Kütüphanede ara")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tümü", "Okuyorum", "Sonra", "Tamamlandı").forEach { item -> FilterChip(selected = status == item, onClick = { status = item }, label = { Text(item) }) }
            }
            val filtered = library.filter { (status == "Tümü" || it.readingStatus == status) && it.title.contains(query, ignoreCase = true) }
            MangaGrid(filtered, onOpen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourceScreen(model: AppModel, onOpen: (Manga) -> Unit) {
    val repo = model.repository
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(repo.preferences.getString("language", "en") ?: "en") }
    val mangas = model.sourceMangas
    val loading = model.sourceLoading
    val error = model.sourceError
    val grid = rememberLazyGridState()
    LaunchedEffect(query, language) { model.searchSource(query, language) }
    Column {
        SearchField(query, { query = it }, "Tüm kaynaklarda seri ara")
        Text("MangaDex bölüm dili", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        LanguageRow(language, { language = it; repo.preferences.edit().putString("language", it).apply() })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            Text(error, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.searchSource(query, language, force = true) }) { Text("Tekrar dene") }
        }
        if (!loading && mangas.isEmpty() && error == null) EmptyState(Icons.Outlined.Search, "Seri bulunamadı", "Aramayı veya bölüm dilini değiştir.") {}
        else MangaGrid(mangas, onOpen, Modifier.weight(1f).testTag("source-grid"), grid)
    }
}

@Composable
private fun DetailScreen(id: String, model: AppModel, onRead: (String) -> Unit) {
    val repo = model.repository
    val manga by remember(id) { repo.dao.observeManga(id) }.collectAsStateWithLifecycle(null)
    val chapters by remember(id) { repo.dao.chapters(id) }.collectAsStateWithLifecycle(emptyList())
    var language by rememberSaveable(id) { mutableStateOf(repo.preferences.getString("language", "en") ?: "en") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var expand by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(manga?.id, language, retry) {
        val m = manga ?: return@LaunchedEffect
        if (m.source == "local") return@LaunchedEffect
        loading = true; error = null
        try { repo.refreshChapters(m, language) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }
    val m = manga ?: return
    val visible = chapters.filter { m.source != "mangadex" || language == "all" || it.language == language }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(m, Modifier.width(96.dp).height(144.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(com.efeyamann.hscan.data.sourceName(m.source), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    FilledTonalButton(onClick = { model.action { repo.dao.setLibrary(id, !m.inLibrary) } }) {
                        Icon(if (m.inLibrary) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd, null)
                        Spacer(Modifier.width(8.dp)); Text(if (m.inLibrary) "Kütüphanede" else "Kütüphaneye ekle")
                    }
                }
            }
            if (m.description.isNotBlank()) {
                Text(m.description.replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1"), Modifier.padding(horizontal = 16.dp), maxLines = if (expand) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { expand = !expand }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (expand) "Daha az" else "Açıklamayı aç") }
            }
            if (m.inLibrary) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Okuyorum", "Sonra", "Tamamlandı").forEach { status -> FilterChip(selected = m.readingStatus == status, onClick = { model.action { repo.dao.setStatus(id, status) } }, label = { Text(status) }) }
            }
            if (m.source == "mangadex") LanguageRow(language, { language = it })
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) Column(Modifier.padding(16.dp)) { Text(error!!, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("Tekrar dene") } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bölümler · ${visible.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (m.source != "local") IconButton(onClick = { retry++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Bölümleri yenile") }
            }
            val start = visible.firstOrNull { it.id == m.lastChapterId } ?: visible.firstOrNull { !it.isRead } ?: visible.firstOrNull()
            if (start != null) Button(onClick = { onRead(start.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(if (m.lastChapterId.isNotEmpty()) "Kaldığın yerden devam et" else "Okumaya başla")
            }
            if (visible.isEmpty() && !loading && error == null) Text("Bu dilde okunabilir bölüm bulunamadı. Başka bir dil seçebilirsin.", Modifier.padding(16.dp))
        }
        items(visible, key = { it.id }) { chapter ->
            ChapterRow(chapter, onRead = { onRead(chapter.id) }, onDownload = if (m.source != "local" && chapter.downloadState !in listOf("ready", "queued", "downloading")) {
                { model.action { repo.dao.setLibrary(id, true); repo.enqueue(chapter) } }
            } else null)
        }
    }
}

@Composable
private fun DownloadsScreen(chapters: List<Chapter>, library: List<Manga>, model: AppModel, onRead: (String) -> Unit) {
    var deleting by remember { mutableStateOf<Chapter?>(null) }
    if (chapters.isEmpty()) EmptyState(Icons.Outlined.Download, "İndirilen bölüm yok", "Bir serinin bölüm listesindeki indirme düğmesini kullan.") {}
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chapters, key = { it.id }) { c ->
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(library.firstOrNull { it.id == c.mangaId }?.title ?: "MangaDex", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    Text(downloadLabel(c), style = MaterialTheme.typography.bodySmall)
                    if (c.downloadState in listOf("queued", "downloading")) LinearProgressIndicator(progress = { if (c.pageCount > 0) c.downloadCount.toFloat() / c.pageCount else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (c.error.isNotBlank()) Text(c.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (c.downloadState == "ready") TextButton(onClick = { onRead(c.id) }) { Text("Oku") }
                        if (c.downloadState == "error" && c.language != "local") TextButton(onClick = { model.repository.enqueue(c) }) { Text("Tekrar indir") }
                        if (c.language != "local") TextButton(onClick = { deleting = c }) { Text(if (c.downloadState in listOf("queued", "downloading")) "İptal et" else "Sil") }
                        else if (c.downloadState == "missing") Text("Aynı arşivi tekrar içe aktar.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    deleting?.let { chapter -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("İndirilen dosyaları sil?") }, text = { Text("Okuma ilerlemen korunacak. Bölümü yeniden indirebilirsin.") }, confirmButton = { TextButton(onClick = { deleting = null; model.action { model.repository.deleteDownload(chapter) } }) { Text("Sil") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Vazgeç") } }) }
}

@Composable
private fun ChapterRow(chapter: Chapter, onRead: () -> Unit, onDownload: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onRead).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (chapter.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(listOf(chapter.language.uppercase(), if (chapter.isRead) "Okundu" else "Okunmadı", downloadLabel(chapter)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDownload != null) IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, "Bölümü indir") }
        else if (chapter.downloadState == "ready") Icon(Icons.Outlined.OfflinePin, "Çevrimdışı kullanılabilir", Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.secondary)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

private fun downloadLabel(c: Chapter): String = when (c.downloadState) {
    "ready" -> "İndirildi · ${c.pageCount} sayfa"
    "downloading" -> "İndiriliyor · ${c.downloadCount}/${c.pageCount}"
    "queued" -> "İndirme bekliyor · ${c.downloadCount}/${c.pageCount}"
    "error" -> "İndirme tamamlanamadı"
    "missing" -> "Yerel dosya eksik"
    else -> ""
}

@Composable
private fun MangaGrid(mangas: List<Manga>, onOpen: (Manga) -> Unit, modifier: Modifier = Modifier, state: LazyGridState = rememberLazyGridState()) {
    LazyVerticalGrid(state = state, columns = GridCells.Adaptive(140.dp), modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(mangas, key = { it.id }) { manga ->
            Column(Modifier.clickable { onOpen(manga) }) {
                Cover(manga, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                Text(com.efeyamann.hscan.data.sourceName(manga.source), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(manga.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (manga.inLibrary) Text(manga.readingStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Cover(manga: Manga, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(manga.title.take(1), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            if (manga.cover.isNotBlank()) AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(if (manga.cover.startsWith("https://")) manga.cover else File(manga.cover)).httpHeaders(NetworkHeaders.Builder().apply {
                when (manga.source) {
                    "mangabats" -> set("Referer", "https://www.mangabats.com/")
                    "mangabuddy" -> set("Referer", "https://mangabuddy1.co.uk/")
                }
            }.build()).size(512, 768).scale(Scale.FIT).build(), contentDescription = "${manga.title} kapağı", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Aramayı temizle") } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp))
}

@Composable
private fun LanguageRow(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Bölüm dili", style = MaterialTheme.typography.labelMedium)
        listOf("en" to "İngilizce", "tr" to "Türkçe", "all" to "Tümü").forEach { (code, label) -> FilterChip(selected == code, { onSelect(code) }, label = { Text(label) }) }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, description: String, actions: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium)
        actions()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    EmptyState(Icons.Outlined.CloudOff, "Bağlantı kurulamadı", message) { Button(onClick = onRetry) { Text("Tekrar dene") } }
}
