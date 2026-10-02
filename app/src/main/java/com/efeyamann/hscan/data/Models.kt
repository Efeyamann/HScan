package com.efeyamann.hscan.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "manga")
data class Manga(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val cover: String = "",
    val source: String = "mangadex",
    val inLibrary: Boolean = false,
    val readingStatus: String = "Okuyorum",
    val lastChapterId: String = "",
    val lastReadAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val sourceUrl: String = "",
)

@Entity(tableName = "chapter", indices = [Index("mangaId")])
data class Chapter(
    @PrimaryKey val id: String,
    val mangaId: String,
    val title: String,
    val number: String = "",
    val language: String = "en",
    val order: Double = 0.0,
    val manifest: String = "[]",
    val downloadState: String = "none",
    val downloadCount: Int = 0,
    val pageCount: Int = 0,
    val progressIndex: Int = 0,
    val progressOffset: Int = 0,
    val isRead: Boolean = false,
    val error: String = "",
    @ColumnInfo(defaultValue = "''") val sourceUrl: String = "",
)

data class Page(val uri: String, val width: Int = 0, val height: Int = 0, val referer: String = "")
fun List<Page>.toJson(): String = JSONArray().apply {
    forEach { put(JSONObject().put("uri", it.uri).put("width", it.width).put("height", it.height).put("referer", it.referer)) }
}.toString()
fun pagesFromJson(json: String): List<Page> {
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val p = array.getJSONObject(index)
        Page(p.getString("uri"), p.optInt("width"), p.optInt("height"), p.optString("referer"))
    }
}

@Dao
interface ReaderDao {
    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY lastReadAt DESC, addedAt DESC")
    fun library(): Flow<List<Manga>>
    @Query("SELECT * FROM manga WHERE lastChapterId != '' AND EXISTS (SELECT 1 FROM chapter WHERE chapter.id = manga.lastChapterId) ORDER BY lastReadAt DESC LIMIT 1")
    fun latestRead(): Flow<Manga?>
    @Query("SELECT * FROM manga WHERE id = :id") fun observeManga(id: String): Flow<Manga?>
    @Query("SELECT * FROM manga WHERE id = :id") suspend fun manga(id: String): Manga?
    @Query("SELECT * FROM manga") suspend fun allManga(): List<Manga>
    @Query("SELECT * FROM chapter WHERE mangaId = :id ORDER BY `order`, id") fun chapters(id: String): Flow<List<Chapter>>
    @Query("SELECT * FROM chapter WHERE mangaId = :id ORDER BY `order`, id") suspend fun chaptersOnce(id: String): List<Chapter>
    @Query("SELECT * FROM chapter WHERE id = :id") suspend fun chapter(id: String): Chapter?
    @Query("SELECT * FROM chapter WHERE id = :id") fun observeChapter(id: String): Flow<Chapter?>
    @Query("SELECT * FROM chapter WHERE downloadState != 'none' ORDER BY mangaId, `order`") fun downloads(): Flow<List<Chapter>>
    @Query("SELECT * FROM chapter") suspend fun allChapters(): List<Chapter>
    @Upsert suspend fun putManga(manga: Manga)
    @Upsert suspend fun putChapter(chapter: Chapter)
    @Query("UPDATE manga SET inLibrary = :value WHERE id = :id") suspend fun setLibrary(id: String, value: Boolean)
    @Query("UPDATE manga SET readingStatus = :status WHERE id = :id") suspend fun setStatus(id: String, status: String)
    @Query("UPDATE chapter SET progressIndex = :index, progressOffset = :offset, isRead = :read WHERE id = :id")
    suspend fun progress(id: String, index: Int, offset: Int, read: Boolean)
    @Query("UPDATE manga SET lastChapterId = :chapterId, lastReadAt = :time WHERE id = :mangaId")
    suspend fun lastRead(mangaId: String, chapterId: String, time: Long)
    @Query("UPDATE chapter SET downloadState = :state, downloadCount = :count, pageCount = :total, error = :error WHERE id = :id")
    suspend fun download(id: String, state: String, count: Int, total: Int, error: String = "")
}

@Database(entities = [Manga::class, Chapter::class], version = 2, exportSchema = true)
abstract class ReaderDatabase : RoomDatabase() { abstract fun dao(): ReaderDao }

val SOURCE_MIGRATION = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE manga ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chapter ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
    }
}
