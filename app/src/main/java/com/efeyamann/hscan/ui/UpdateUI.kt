package com.efeyamann.hscan.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.efeyamann.hscan.BuildConfig
import com.efeyamann.hscan.update.UpdateInstallActivity
import com.efeyamann.hscan.update.UpdateManager
import com.efeyamann.hscan.update.UpdateState
import java.text.DateFormat
import java.util.Date

private fun updateText(state: UpdateState): String = when {
    state.ready -> "İndirildi. Kurulum için Android onayı gerekir."
    state.downloading -> "İndiriliyor · %${state.percent}"
    state.error != null -> state.error
    state.checking -> "Yeni sürüm kontrol ediliyor…"
    state.release != null -> "İndirme bekleniyor."
    state.lastChecked > 0 -> "Güncelsin · Son kontrol: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastChecked))}"
    else -> "Yeni sürümler otomatik kontrol edilir."
}

@Composable
fun UpdateBanner(updates: UpdateManager) {
    val state by updates.state.collectAsStateWithLifecycle()
    val release = state.release ?: return
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.ready) "Güncelleme hazır · build ${release.version}" else "Yeni sürüm · build ${release.version}", style = MaterialTheme.typography.titleSmall)
            Text(updateText(state), style = MaterialTheme.typography.bodySmall)
            if (state.downloading) LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            else Button(onClick = {
                if (state.ready) context.startActivity(Intent(context, UpdateInstallActivity::class.java)) else updates.queueDownload()
            }, modifier = Modifier.fillMaxWidth()) { Text(if (state.ready) "Güncellemeyi kur" else "İndir") }
        }
    }
}

@Composable
fun UpdateSettings(updates: UpdateManager) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var automatic by remember { mutableStateOf(updates.autoDownload) }
    var onlyWifi by remember { mutableStateOf(updates.wifiOnly) }
    var notifications by remember { mutableStateOf(updates.notificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = updates.notificationsEnabled()
        if (notifications) updates.notifyUpdate()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Uygulama güncellemeleri", style = MaterialTheme.typography.titleMedium)
        Text("Yüklü sürüm · build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Yeni sürümü otomatik indir", Modifier.weight(1f))
            Switch(checked = automatic, onCheckedChange = { automatic = it; updates.setDownloadOptions(it, onlyWifi) })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Yalnız Wi-Fi üzerinden indir", Modifier.weight(1f))
            Switch(checked = onlyWifi, onCheckedChange = { onlyWifi = it; updates.setDownloadOptions(automatic, it) })
        }
        Text(if (onlyWifi) "İndirme için Wi-Fi beklenir." else "Wi-Fi ve mobil veri kullanılır. Güncelleme yaklaşık 13 MB.", style = MaterialTheme.typography.bodySmall)
        Text(updateText(state), style = MaterialTheme.typography.bodySmall)
        state.release?.let { UpdateBanner(updates) }
        OutlinedButton(onClick = { updates.checkNow() }, enabled = !state.checking && !state.downloading, modifier = Modifier.fillMaxWidth()) { Text("Güncellemeleri kontrol et") }
        if (!notifications) TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !updates.preferences.getBoolean("notificationRequested", false)) {
                updates.preferences.edit().putBoolean("notificationRequested", true).apply()
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.fillMaxWidth()) { Text("Güncelleme bildirimlerini aç") }
    }
}

@Composable
fun UpdateNotificationPrompt(updates: UpdateManager) {
    var show by remember { mutableStateOf(!BuildConfig.DEBUG && !updates.preferences.getBoolean("notificationPromptShown", false) && !updates.notificationsEnabled()) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) updates.notifyUpdate()
    }
    fun dismiss() { show = false; updates.preferences.edit().putBoolean("notificationPromptShown", true).apply() }
    if (show) AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("Güncelleme bildirimleri") },
        text = { Text("Yeni HScan sürümü otomatik indirildiğinde sana haber vereyim. Bildirime dokunarak kuruluma geçebilirsin.") },
        confirmButton = { TextButton(onClick = {
            dismiss()
            if (Build.VERSION.SDK_INT >= 33 && !updates.preferences.getBoolean("notificationRequested", false)) {
                updates.preferences.edit().putBoolean("notificationRequested", true).apply()
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("Bildirimleri aç") } },
        dismissButton = { TextButton(onClick = { dismiss() }) { Text("Daha sonra") } },
    )
}
