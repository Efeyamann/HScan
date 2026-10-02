package com.efeyamann.hscan

import android.app.Application
import androidx.room.Room
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.efeyamann.hscan.data.ReaderDatabase
import com.efeyamann.hscan.data.ReaderRepository
import okio.Path.Companion.toOkioPath

class HScanApp : Application(), SingletonImageLoader.Factory {
    val updates by lazy { com.efeyamann.hscan.update.UpdateManager(this) }
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) updates.schedule()
    }
    val repository by lazy {
        ReaderRepository(this, Room.databaseBuilder(this, ReaderDatabase::class.java, "hscan.db").build())
    }
    override fun newImageLoader(context: android.content.Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(java.io.File(cacheDir, "coil").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
        .build()
}
