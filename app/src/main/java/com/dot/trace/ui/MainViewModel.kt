package com.dot.trace.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dot.trace.TraceApp
import com.dot.trace.data.PointEntity
import com.dot.trace.data.Settings
import com.dot.trace.export.GpxExporter
import com.dot.trace.tracking.Tracker
import com.dot.trace.tracking.TrackerStatus
import com.dot.trace.trips.Segment
import com.dot.trace.trips.TripClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class DayPoints(val date: LocalDate?, val points: List<PointEntity>)

data class DayTrips(val date: LocalDate?, val segments: List<Segment>)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TraceApp
    private val dao = app.db.points()
    private val zone: ZoneId = ZoneId.systemDefault()

    val today: LocalDate get() = LocalDate.now(zone)

    private val _date = MutableStateFlow(LocalDate.now(zone))
    val date: StateFlow<LocalDate> = _date

    /** 선택한 날짜의 기록 (자정~자정, 휴대폰 시간대 기준). 어느 날짜의 목록인지 함께 담는다. */
    val day: StateFlow<DayPoints> = _date
        .flatMapLatest { d -> dao.observeBetween(startOf(d), startOf(d.plusDays(1))).map { DayPoints(d, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayPoints(null, emptyList()))

    /** 선택한 날짜를 머무름·이동 구간으로 나누고 이동 수단을 추정한 결과 */
    val trips: StateFlow<DayTrips> = day
        .map { d -> DayTrips(d.date, TripClassifier.classify(d.points)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayTrips(null, emptyList()))

    val latest: StateFlow<PointEntity?> = dao.observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalCount: StateFlow<Int> = dao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val settings: StateFlow<Settings> = app.prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val status: StateFlow<TrackerStatus> = Tracker.status

    fun selectDate(d: LocalDate) {
        _date.value = if (d.isAfter(today)) today else d
    }

    fun shiftDate(days: Long) = selectDate(_date.value.plusDays(days))

    fun setInterval(minutes: Int) = viewModelScope.launch { app.prefs.setIntervalMinutes(minutes) }

    suspend fun setTrackingEnabled(enabled: Boolean) = app.prefs.setTrackingEnabled(enabled)

    /** 선택한 날짜(또는 전체)를 GPX로 저장. 저장한 점 개수를 돌려준다. */
    suspend fun exportGpx(uri: Uri, allDays: Boolean): Int = withContext(Dispatchers.IO) {
        val d = _date.value
        val list = if (allDays) dao.between(0, Long.MAX_VALUE) else dao.between(startOf(d), startOf(d.plusDays(1)))
        app.contentResolver.openOutputStream(uri)?.use { out ->
            GpxExporter.write(list, if (allDays) "DotTrace 전체 기록" else "DotTrace $d", out)
        }
        list.size
    }

    suspend fun deleteSelectedDay(): Int {
        val d = _date.value
        return dao.deleteBetween(startOf(d), startOf(d.plusDays(1)))
    }

    private fun startOf(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
}
