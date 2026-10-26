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
    /** 지도 스타일 (OpenFreeMap 스타일 이름) */
    val mapStyle: String = DEFAULT_MAP_STYLE,
) {
    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 1
        val INTERVAL_CHOICES = listOf(1, 2, 3, 5, 10, 15, 30)
        const val DEFAULT_MAP_STYLE = "liberty"
        /** OpenFreeMap이 제공하는 지도 스타일과 화면에 보일 이름 */
        val MAP_STYLES = listOf(
            "liberty" to "기본",
            "bright" to "선명하게",
            "positron" to "심플",
            "fiord" to "차분하게",
            "dark" to "어둡게",
        )
    }
}

class Prefs(context: Context) {
    private val ds = context.applicationContext.dataStore

    private object K {
        val interval = intPreferencesKey("interval_minutes")
        val tracking = booleanPreferencesKey("tracking_enabled")
        val mapStyle = androidx.datastore.preferences.core.stringPreferencesKey("map_style")
        val reportSeen = androidx.datastore.preferences.core.stringPreferencesKey("report_seen")
        val reportNotified = androidx.datastore.preferences.core.stringPreferencesKey("report_notified")
        val suggestDismissed = androidx.datastore.preferences.core.stringPreferencesKey("suggest_dismissed")
    }

    val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            intervalMinutes = (p[K.interval] ?: Settings.DEFAULT_INTERVAL_MINUTES).coerceIn(1, 120),
            trackingEnabled = p[K.tracking] ?: false,
            mapStyle = p[K.mapStyle]?.takeIf { s -> Settings.MAP_STYLES.any { it.first == s } } ?: Settings.DEFAULT_MAP_STYLE,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setIntervalMinutes(minutes: Int) = ds.edit { it[K.interval] = minutes.coerceIn(1, 120) }

    suspend fun setTrackingEnabled(enabled: Boolean) = ds.edit { it[K.tracking] = enabled }

    suspend fun setMapStyle(style: String) = ds.edit { it[K.mapStyle] = style }

    /** 마지막으로 열어 본 리포트 기간 (예: M2026-10) — 앱 안 '리포트 준비됨' 안내를 한 번만 보여 주려고 */
    suspend fun reportSeen(): String? = ds.data.first()[K.reportSeen]
    suspend fun setReportSeen(key: String) = ds.edit { it[K.reportSeen] = key }

    /** '집·회사로 등록할까요?' 제안을 닫은 위치들 (같은 제안을 다시 하지 않으려고) */
    suspend fun suggestDismissed(): Set<String> = ds.data.first()[K.suggestDismissed]?.split(';')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    suspend fun addSuggestDismissed(key: String) = ds.edit {
        val set = (it[K.suggestDismissed]?.split(';')?.filter { s -> s.isNotBlank() }?.toSet() ?: emptySet()) + key
        it[K.suggestDismissed] = set.toList().takeLast(20).joinToString(";")
    }

    /** 마지막으로 알림을 보낸 리포트 기간들 (쉼표로 구분) */
    suspend fun reportNotified(): Set<String> = ds.data.first()[K.reportNotified]?.split(',')?.toSet() ?: emptySet()
    suspend fun addReportNotified(key: String) = ds.edit {
        val set = (it[K.reportNotified]?.split(',')?.toSet() ?: emptySet()) + key
        it[K.reportNotified] = set.toList().takeLast(24).joinToString(",")
    }
}
