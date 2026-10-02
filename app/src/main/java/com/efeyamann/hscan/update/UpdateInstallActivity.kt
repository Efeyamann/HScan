package com.efeyamann.hscan.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.efeyamann.hscan.HScanApp
import kotlinx.coroutines.launch

class UpdateInstallActivity : ComponentActivity() {
    private var message by mutableStateOf("Güncelleme hazırlanıyor…")
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) install() else message = "Kurmak için HScan'e uygulama yükleme izni vermelisin."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("HScan güncellemesi", style = MaterialTheme.typography.titleLarge)
                        Text(message)
                        Button(onClick = { proceed() }) { Text("Kuruluma devam et") }
                        TextButton(onClick = { finish() }) { Text("Daha sonra") }
                    }
                }
            }
        }
        if (savedInstanceState == null) proceed()
    }
    private fun proceed() {
        if (!packageManager.canRequestPackageInstalls()) {
            message = "Android'in açtığı ekranda HScan için uygulama yüklemeye izin ver."
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } else install()
    }
    private fun install() {
        lifecycleScope.launch {
            try {
                val intent = (application as HScanApp).updates.installIntent()
                startActivity(intent)
                finish()
            } catch (e: Exception) { message = e.message ?: "Kurulum açılamadı. Güncellemeyi yeniden indir." }
        }
    }
}
