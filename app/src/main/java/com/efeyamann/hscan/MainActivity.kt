package com.efeyamann.hscan

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.efeyamann.hscan.data.Manga
import com.efeyamann.hscan.data.ReaderRepository
import com.efeyamann.hscan.ui.HScanUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { HScanUI() }
    }
}

class AppModel(application: Application) : AndroidViewModel(application) {
    val repository: ReaderRepository = (application as HScanApp).repository
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    var importing by mutableStateOf(false)
    var importedMangaId by mutableStateOf<String?>(null)
    var sourceMangas by mutableStateOf<List<Manga>>(emptyList())
        private set
    var sourceLoading by mutableStateOf(false)
        private set
    var sourceError by mutableStateOf<String?>(null)
        private set
    private var sourceKey: Pair<String, String>? = null
    private var sourceJob: Job? = null
    private var sourceRequest = 0

    fun searchSource(query: String, language: String, force: Boolean = false) {
        val key = query to language
        if (!force && sourceKey == key) return
        sourceJob?.cancel()
        val request = ++sourceRequest
        sourceKey = key
        sourceMangas = emptyList()
        sourceLoading = true
        sourceError = null
        sourceJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(400)
                sourceMangas = repository.api.search(query, language)
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { sourceError = e.message ?: "Kaynak yüklenemedi." }
            finally { if (sourceRequest == request) sourceLoading = false }
        }
    }
    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { notices.send(e.message ?: "İşlem tamamlanamadı. Tekrar dene.") }
        }
    }
    fun notice(message: String) { viewModelScope.launch { notices.send(message) } }
    fun import(uri: Uri) {
        if (importing) return
        action {
            importing = true
            try { importedMangaId = repository.importArchive(uri).id }
            finally { importing = false }
        }
    }
    fun open(manga: Manga, after: () -> Unit) { action { repository.cacheManga(manga); after() } }
}
