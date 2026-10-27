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
    /** 지도 스타일 (standard, silver, night, satellite) */
    val mapStyle: String = DEFAULT_MAP_STYLE,
    /** 머문 곳 이름을 Google 장소 정보에서 찾을지 (머문 곳 좌표가 Google로 전송됨) */
    val googlePlaces: Boolean = true,
    /** 지도 위 카드·버튼의 불투명도 (0.3~1, 낮을수록 투명) */
    val panelAlpha: Float = DEFAULT_PANEL_ALPHA,
    /** 내 차 블루투스 (연결되어 있는 동안은 자동차로 기록) */
    val carDevices: List<com.dot.trace.tracking.BtDevice> = emptyList(),
) {
    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 1
        val INTERVAL_CHOICES = listOf(1, 2, 3, 5, 10, 15, 30)
        const val DEFAULT_MAP_STYLE = "standard"
        const val DEFAULT_PANEL_ALPHA = 0.95f
        const val MIN_PANEL_ALPHA = 0.3f
        /** 지도 스타일과 화면에 보일 이름 */
        val MAP_STYLES = listOf(
            "standard" to "기본",
            "silver" to "심플",
            "night" to "어둡게",
            "satellite" to "위성",
        )

        /** 예전(OpenFreeMap) 스타일 이름을 새 이름으로 */
        fun migrateStyle(s: String?): String? = when (s) {
            "liberty", "bright" -> "standard"
            "positron" -> "silver"
            "fiord", "dark" -> "night"
            else -> s
        }
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
        val googlePlaces = booleanPreferencesKey("google_places")
        val panelAlpha = androidx.datastore.preferences.core.floatPreferencesKey("panel_alpha")
        val carDevices = androidx.datastore.preferences.core.stringSetPreferencesKey("car_devices")
        val placesQuota = androidx.datastore.preferences.core.stringPreferencesKey("places_quota")
    }

    val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            intervalMinutes = (p[K.interval] ?: Settings.DEFAULT_INTERVAL_MINUTES).coerceIn(1, 120),
            trackingEnabled = p[K.tracking] ?: false,
            googlePlaces = p[K.googlePlaces] ?: true,
            carDevices = p[K.carDevices].orEmpty().mapNotNull { e ->
                val i = e.indexOf('|')
                if (i <= 0) null else com.dot.trace.tracking.BtDevice(e.substring(0, i), e.substring(i + 1))
            }.sortedBy { it.name },
            panelAlpha = (p[K.panelAlpha] ?: Settings.DEFAULT_PANEL_ALPHA).coerceIn(Settings.MIN_PANEL_ALPHA, 1f),
            mapStyle = Settings.migrateStyle(p[K.mapStyle])?.takeIf { s -> Settings.MAP_STYLES.any { it.first == s } } ?: Settings.DEFAULT_MAP_STYLE,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setIntervalMinutes(minutes: Int) = ds.edit { it[K.interval] = minutes.coerceIn(1, 120) }

    suspend fun setTrackingEnabled(enabled: Boolean) = ds.edit { it[K.tracking] = enabled }

    suspend fun setMapStyle(style: String) = ds.edit { it[K.mapStyle] = style }

    suspend fun setCarDevices(list: List<com.dot.trace.tracking.BtDevice>) =
        ds.edit { it[K.carDevices] = list.map { d -> "${d.address}|${d.name}" }.toSet() }

    suspend fun setPanelAlpha(a: Float) = ds.edit { it[K.panelAlpha] = a.coerceIn(Settings.MIN_PANEL_ALPHA, 1f) }

    suspend fun setGooglePlaces(on: Boolean) = ds.edit { it[K.googlePlaces] = on }

    /**
     * 장소 조회 하루 횟수 제한. 남아 있으면 1 쓰고 true.
     * 저장 형식: "2026-10-26:12"
     */
    suspend fun takePlacesQuota(today: String, limit: Int): Boolean {
        var ok = false
        ds.edit {
            val (d, n) = it[K.placesQuota]?.split(':')?.let { f -> f[0] to (f.getOrNull(1)?.toIntOrNull() ?: 0) } ?: ("" to 0)
            val used = if (d == today) n else 0
            if (used < limit) {
                ok = true
                it[K.placesQuota] = "$today:${used + 1}"
            }
        }
        return ok
    }

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
