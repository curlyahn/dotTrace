package com.dot.trace.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 한 번 기록한 위치. 모든 데이터는 이 기기의 DB에만 저장된다. */
@Entity(tableName = "points", indices = [Index("time")])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 위치를 잡은 시각 (epoch ms) */
    val time: Long,
    val lat: Double,
    val lng: Double,
    /** 수평 정확도 (m) */
    val accuracy: Float?,
    val altitude: Double?,
    /** 속도 (m/s) */
    val speed: Float?,
    /** 이 위치를 잡을 때 OS 활동 인식이 알려 준 활동 (DetectedActivity 상수, 모르면 null) */
    val activity: Int? = null,
    /** 그때 보이던 와이파이 공유기(BSSID) 최대 5개, 쉼표로 구분. 함께 이동하는 와이파이를 찾는 데 쓴다 */
    val wifi: String? = null,
    /** 대중교통 공공 와이파이로 보이는 이름이 있었는지 */
    @ColumnInfo(defaultValue = "0") val transitWifi: Boolean = false,
    /** 위치 신호가 끊겼다가 다시 잡힌 위치라면, 끊겨 있던 시간 (ms) */
    val lostMs: Long? = null,
    /** 어디서 온 기록인지: 0 = DotTrace가 기록, 1 = 구글 타임라인에서 가져옴 */
    @ColumnInfo(defaultValue = "0") val source: Int = SOURCE_DOTTRACE,
) {
    companion object {
        const val SOURCE_DOTTRACE = 0
        const val SOURCE_GOOGLE = 1
    }
}

@Dao
interface PointDao {
    @Insert
    suspend fun insert(point: PointEntity): Long

    @Insert
    suspend fun insertAll(points: List<PointEntity>)

    @Query("SELECT time FROM points WHERE time >= :from AND time <= :to ORDER BY time")
    suspend fun timesBetween(from: Long, to: Long): List<Long>

    @Query("SELECT COUNT(*) FROM points WHERE source = :source")
    fun observeCountBySource(source: Int): Flow<Int>

    @Query("DELETE FROM points WHERE source = :source")
    suspend fun deleteBySource(source: Int): Int

    @Query("SELECT * FROM points WHERE time >= :from AND time < :to ORDER BY time")
    fun observeBetween(from: Long, to: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE time >= :from AND time < :to ORDER BY time")
    suspend fun between(from: Long, to: Long): List<PointEntity>

    @Query("SELECT * FROM points ORDER BY time DESC LIMIT 1")
    fun observeLatest(): Flow<PointEntity?>

    @Query("SELECT COUNT(*) FROM points")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM points WHERE time >= :from AND time < :to")
    suspend fun countBetween(from: Long, to: Long): Int

    /** 이 시각 이전의 마지막 기록 시각 (기록 있는 날로 건너뛰기) */
    @Query("SELECT MAX(time) FROM points WHERE time < :t")
    suspend fun lastBefore(t: Long): Long?

    /** 이 시각 이후의 첫 기록 시각 */
    @Query("SELECT MIN(time) FROM points WHERE time >= :t")
    suspend fun firstFrom(t: Long): Long?

    @Query("DELETE FROM points WHERE time >= :from AND time < :to")
    suspend fun deleteBetween(from: Long, to: Long): Int
}

/** 사용자가 등록한 장소: 집, 회사, 자주 가는 곳(최대 10개) */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** home, work, fav */
    val kind: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** 이 반경(m) 안에 있으면 이 장소에 있는 것으로 본다 */
    @ColumnInfo(defaultValue = "150") val radius: Float = 150f,
) {
    companion object {
        const val HOME = "home"
        const val WORK = "work"
        const val FAV = "fav"
        const val MAX_FAVORITES = 10
    }
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY CASE kind WHEN 'home' THEN 0 WHEN 'work' THEN 1 ELSE 2 END, id")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY CASE kind WHEN 'home' THEN 0 WHEN 'work' THEN 1 ELSE 2 END, id")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: Long): PlaceEntity?

    @Query("SELECT * FROM places WHERE kind = :kind LIMIT 1")
    suspend fun byKind(kind: String): PlaceEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(place: PlaceEntity): Long

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [PointEntity::class, PlaceEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun points(): PointDao
    abstract fun places(): PlaceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "dottrace.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }

        /** 이동 수단 추정용 정보 추가 (기존 기록은 그대로 둔다) */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE points ADD COLUMN activity INTEGER")
                db.execSQL("ALTER TABLE points ADD COLUMN wifi TEXT")
                db.execSQL("ALTER TABLE points ADD COLUMN transitWifi INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE points ADD COLUMN lostMs INTEGER")
            }
        }

        /** 내 장소(집·회사·자주 가는 곳) 추가 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS places (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, " +
                        "lat REAL NOT NULL, lng REAL NOT NULL, radius REAL NOT NULL DEFAULT 150)"
                )
            }
        }

        /** 기록 출처(구글 타임라인 가져오기 구분) 추가 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE points ADD COLUMN source INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
