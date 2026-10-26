package com.dot.trace.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    /** 위치 기록 주기 (분) */
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** 사용자가 기록을 켜 둔 상태인지. 재부팅·업데이트 후 자동 재시작 판단에 쓴다. */
    val trackingEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 1
        val INTERVAL_CHOICES = listOf(1, 2, 3, 5, 10, 15, 30)
    }
}

class Prefs(context: Context) {
    private val ds = context.applicationContext.dataStore

    private object K {
        val interval = intPreferencesKey("interval_minutes")
        val tracking = booleanPreferencesKey("tracking_enabled")
    }

    val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            intervalMinutes = (p[K.interval] ?: Settings.DEFAULT_INTERVAL_MINUTES).coerceIn(1, 120),
            trackingEnabled = p[K.tracking] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setIntervalMinutes(minutes: Int) = ds.edit { it[K.interval] = minutes.coerceIn(1, 120) }

    suspend fun setTrackingEnabled(enabled: Boolean) = ds.edit { it[K.tracking] = enabled }
}
