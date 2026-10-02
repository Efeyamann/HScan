@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

package com.efeyamann.hscan.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.efeyamann.hscan.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.io.File

@Composable
fun ReaderScreen(id: String, repo: ReaderRepository, gap: Boolean, onBack: () -> Unit, onNext: (String) -> Unit) {
    // A separate composition resets the list state when switching to the next chapter.
    key(id) { ChapterReader(id, repo, gap, onBack, onNext) }
}

@Composable
private fun ChapterReader(id: String, repo: ReaderRepository, gap: Boolean, onBack: () -> Unit, onNext: (String) -> Unit) {
    val chapter by remember(id) { repo.dao.observeChapter(id) }.collectAsStateWithLifecycle(null)
    var initialChapter by remember(id) { mutableStateOf<Chapter?>(null) }
    var pages by remember(id) { mutableStateOf<List<Page>>(emptyList()) }
    var nextId by remember(id) { mutableStateOf<String?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var retry by remember(id) { mutableIntStateOf(0) }
    var controls by rememberSaveable(id) { mutableStateOf(true) }
    var restored by remember(id, retry) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    BackHandler(onBack = onBack)
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    LaunchedEffect(controls) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(id, retry) {
        loading = true; error = null
        try {
            val found = repo.dao.chapter(id) ?: throw IllegalStateException("Bölüm bulunamadı.")
            initialChapter = found
            pages = repo.readerPages(found)
            val siblings = repo.dao.chaptersOnce(found.mangaId).filter { it.language == found.language }
            val index = siblings.indexOfFirst { it.id == id }
            nextId = siblings.getOrNull(index + 1)?.id
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message ?: "Bölüm açılamadı." }
        finally { loading = false }
    }
    LaunchedEffect(pages, loading) {
        if (pages.isNotEmpty() && !loading && !restored) {
            val c = initialChapter ?: return@LaunchedEffect
            list.scrollToItem(c.progressIndex.coerceIn(0, pages.lastIndex), c.progressOffset)
            restored = true
        }
    }
    LaunchedEffect(restored, id) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, !list.canScrollForward) }
            .distinctUntilChanged().debounce(300).collect { (index, offset, atEnd) ->
                initialChapter?.let { repo.saveProgress(it, index.coerceAtMost(pages.lastIndex.coerceAtLeast(0)), offset, atEnd) }
            }
    }
    val currentChapter by rememberUpdatedState(initialChapter)
    val currentPages by rememberUpdatedState(pages)
    val isRestored by rememberUpdatedState(restored)
    DisposableEffect(id) {
        onDispose {
            if (isRestored && currentPages.isNotEmpty()) currentChapter?.let {
                repo.saveProgress(it, list.firstVisibleItemIndex.coerceAtMost(currentPages.lastIndex), list.firstVisibleItemScrollOffset, !list.canScrollForward)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(); Text("Bölüm açılıyor…") }
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(error!!)
                Button(onClick = { retry++ }) { Text("Tekrar dene") }
                TextButton(onClick = onBack) { Text("Geri dön") }
            }
            else -> {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("reader-list"), verticalArrangement = Arrangement.spacedBy(if (gap) 8.dp else 0.dp)) {
                    itemsIndexed(pages, key = { index, _ -> index }) { index, page ->
                        val c = initialChapter
                        if (c != null) ReaderPage(repo, c, index, page, onTap = { controls = !controls })
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Bölüm sonu", style = MaterialTheme.typography.titleLarge)
                            if (nextId != null) Button(onClick = { nextId?.let(onNext) }) { Text("Sonraki bölüm") }
                            OutlinedButton(onClick = onBack) { Text("Bölüm listesine dön") }
                        }
                    }
                }
            }
        }
        if (controls) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Bölüm listesine dön") }
                        Text(chapter?.title ?: "Okuyucu", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { controls = false }) { Icon(Icons.Outlined.Close, "Kontrolleri gizle") }
                    }
                }
            }
            if (pages.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                    val index by remember(pages.size) { derivedStateOf { list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) } }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1} / ${pages.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { scope.launch { list.scrollToItem(0) } }) { Text("Başa dön") }
                        if (nextId != null) IconButton(onClick = { nextId?.let(onNext) }) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "Sonraki bölüm") }
                    }
                    if (pages.size > 1) Slider(value = (index + 1).toFloat(), onValueChange = { value -> scope.launch { list.scrollToItem((value.toInt() - 1).coerceIn(0, pages.lastIndex)) } }, valueRange = 1f..pages.size.toFloat())
                    Text("Yakınlaştırmak için çift dokun veya iki parmağını kullan.", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReaderPage(repo: ReaderRepository, chapter: Chapter, index: Int, original: Page, onTap: () -> Unit) {
    var loaded by remember(original) { mutableStateOf<Page?>(null) }
    var error by remember(original) { mutableStateOf<String?>(null) }
    var retry by remember(original) { mutableIntStateOf(0) }
    LaunchedEffect(original, retry) {
        error = null
        try {
            val source = if (retry > 0 && original.uri.startsWith("https://")) repo.api.pages(chapter.id).getOrElse(index) { original } else original
            loaded = repo.pageFile(chapter, index, source)
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message ?: "Sayfa açılamadı." }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("reader-page-$index")) {
        val page = loaded ?: original
        val ratio = if (page.width > 0 && page.height > 0) page.height.toFloat() / page.width else 1.4f
        val height = (maxWidth.value * ratio).dp
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            val file = loaded
            when {
                file != null -> ZoomableAsyncImage(model = File(file.uri), contentDescription = "Sayfa ${index + 1}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter, onClick = { onTap() })
                error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(error!!); TextButton(onClick = { retry++ }) { Icon(Icons.Outlined.Refresh, null); Text("Tekrar dene") } }
                else -> Column(Modifier.clickable(onClick = onTap).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("Sayfa ${index + 1}") }
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
