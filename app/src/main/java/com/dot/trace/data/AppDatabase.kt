package com.dot.trace.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
)

@Dao
interface PointDao {
    @Insert
    suspend fun insert(point: PointEntity): Long

    @Query("SELECT * FROM points WHERE time >= :from AND time < :to ORDER BY time")
    fun observeBetween(from: Long, to: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE time >= :from AND time < :to ORDER BY time")
    suspend fun between(from: Long, to: Long): List<PointEntity>

    @Query("SELECT * FROM points ORDER BY time DESC LIMIT 1")
    fun observeLatest(): Flow<PointEntity?>

    @Query("SELECT COUNT(*) FROM points")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM points WHERE time >= :from AND time < :to")
    suspend fun deleteBetween(from: Long, to: Long): Int
}

@Database(entities = [PointEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun points(): PointDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "dottrace.db")
                .build()
                .also { instance = it }
        }
    }
}
