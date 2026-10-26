package com.dot.trace.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dot.trace.TraceApp
import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.PointEntity
import com.dot.trace.data.Settings
import com.dot.trace.export.GoogleTimelineImporter
import com.dot.trace.export.GpxExporter
import com.dot.trace.tracking.Tracker
import com.dot.trace.tracking.TrackerStatus
import com.dot.trace.stats.Period
import com.dot.trace.stats.Report
import com.dot.trace.stats.ReportBuilder
import com.dot.trace.trips.Segment
import com.dot.trace.trips.TripClassifier
import com.dot.trace.trips.Corrections
import com.dot.trace.trips.DayAnalyzer
import com.dot.trace.trips.DayStore
import com.dot.trace.trips.PlaceSuggester
import com.dot.trace.trips.PlaceSuggestion
import com.dot.trace.trips.TransportMode
import com.dot.trace.data.ModeRuleEntity
import com.dot.trace.data.OverrideEntity
import com.dot.trace.data.PoiNameEntity
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
import java.time.YearMonth
import java.time.ZoneId

data class DayPoints(val date: LocalDate?, val points: List<PointEntity>)

data class DayTrips(val date: LocalDate?, val segments: List<Segment>)

/** 구글 타임라인 가져오기 진행 상황 */
data class ImportUi(
    val running: Boolean,
    val phase: String = "",
    val read: Int = 0,
    val added: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
    val from: Long? = null,
    val to: Long? = null,
)

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

    private val analysis = app.db.analysis()

    /** 선택한 날짜를 머무름·이동 구간으로 나누고 이동 수단을 추정한 결과 (수정 전) */
    private val rawTrips: kotlinx.coroutines.flow.Flow<DayTrips> = day
        .map { d -> DayTrips(d.date, TripClassifier.classify(d.points)) }
        .flowOn(Dispatchers.Default)

    /** 사용자 수정·학습 규칙·장소 이름 */
    private val corrections: kotlinx.coroutines.flow.Flow<Corrections> = kotlinx.coroutines.flow.combine(
        analysis.observeOverrides(), analysis.observeRules(), app.db.places().observeAll(), analysis.observePoi(),
    ) { o, r, p, poi -> Corrections(o, r, p, poi) }

    /** 화면에 보여 줄 하루 정리: 분석 결과 + 사용자 수정 + 이름 */
    val trips: StateFlow<DayTrips> = kotlinx.coroutines.flow.combine(rawTrips, corrections) { t, c ->
        DayTrips(t.date, DayAnalyzer.apply(t.segments, c))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayTrips(null, emptyList()))

    // ── 타임라인에서 고치기 ──

    /** 머문 곳 이름 고치기. remember면 다음에도 이 근처에 이 이름을 붙인다 */
    fun renameStay(seg: Segment, name: String, remember: Boolean) = viewModelScope.launch {
        val n = name.trim()
        analysis.deleteOverridesBetween(seg.start + 1, seg.end - 1)
        if (n.isNotEmpty()) analysis.insertOverride(OverrideEntity(start = seg.start, end = seg.end, name = n))
        if (remember && n.isNotEmpty()) {
            app.db.places().upsert(PlaceEntity(kind = PlaceEntity.LABEL, name = n, lat = seg.lat, lng = seg.lng, radius = 100f))
        }
    }

    /** 이동 수단 고치기. learn이면 같은 출발·도착 이동은 다음에도 이 수단으로 */
    fun changeMode(seg: Segment, mode: TransportMode, learn: Boolean) = viewModelScope.launch {
        analysis.deleteOverridesBetween(seg.start + 1, seg.end - 1)
        analysis.insertOverride(OverrideEntity(start = seg.start, end = seg.end, mode = mode.name))
        if (learn) {
            analysis.insertRule(ModeRuleEntity(startLat = seg.lat, startLng = seg.lng, endLat = seg.endLat, endLng = seg.endLng, mode = mode.name))
        }
    }

    /** 지도에서 찾은 상호 이름 저장 */
    fun savePoi(lat: Double, lng: Double, name: String) = viewModelScope.launch {
        analysis.putPoi(PoiNameEntity(DayAnalyzer.poiKey(lat, lng), lat, lng, name))
    }

    val rules: StateFlow<List<ModeRuleEntity>> = analysis.observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteRule(id: Long) = viewModelScope.launch { analysis.deleteRule(id) }

    // ── 집·회사 자동 제안 ──

    private val _suggestion = MutableStateFlow<PlaceSuggestion?>(null)
    val suggestion: StateFlow<PlaceSuggestion?> = _suggestion

    fun checkSuggestions() = viewModelScope.launch(Dispatchers.Default) {
        val places = app.db.places().all()
        val dismissed = app.prefs.suggestDismissed()
        val store = DayStore(app.db)
        val days = (1L..21L).map { today.minusDays(it) }
        val segs = days.associateWith { store.segments(it) }
        _suggestion.value = PlaceSuggester.suggest(segs, places, dismissed, zone)
    }

    fun acceptSuggestion() = viewModelScope.launch {
        val s = _suggestion.value ?: return@launch
        app.db.places().upsert(PlaceEntity(kind = s.kind, name = if (s.kind == PlaceEntity.HOME) "집" else "회사", lat = s.lat, lng = s.lng))
        _suggestion.value = null
    }

    fun dismissSuggestion() = viewModelScope.launch {
        val s = _suggestion.value ?: return@launch
        app.prefs.addSuggestDismissed(s.key)
        _suggestion.value = null
    }

    /** 내 장소 (집·회사·자주 가는 곳) */
    val places: StateFlow<List<PlaceEntity>> = app.db.places().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 월간·연간 리포트 만들기 */
    suspend fun report(period: Period): Report =
        ReportBuilder.build(app, app.db, period)

    fun markReportSeen(key: String) = viewModelScope.launch { app.prefs.setReportSeen(key) }

    /** 지난달 리포트가 준비됐는데 아직 안 봤다면 그 기간 (매월 1~7일에만) */
    private val _pendingReport = MutableStateFlow<Period?>(null)
    val pendingReport: StateFlow<Period?> = _pendingReport

    fun checkPendingReport() = viewModelScope.launch {
        val now = LocalDate.now(zone)
        if (now.dayOfMonth > 7) { _pendingReport.value = null; return@launch }
        // 1월 초에는 지난해 리포트를, 그 밖에는 지난달 리포트를
        val p: Period = if (now.monthValue == 1) Period.Year(now.year - 1) else Period.Month(YearMonth.from(now).minusMonths(1))
        val seen = app.prefs.reportSeen()
        val has = dao.countBetween(startOf(p.start), startOf(p.endExclusive)) > 0
        _pendingReport.value = if (has && seen != p.key) p else null
    }

    fun dismissPendingReport() {
        _pendingReport.value?.let { markReportSeen(it.key) }
        _pendingReport.value = null
    }

    // ── 구글 타임라인 가져오기 ──

    private val _import = MutableStateFlow<ImportUi?>(null)
    val import: StateFlow<ImportUi?> = _import

    val googleCount: StateFlow<Int> = dao.observeCountBySource(PointEntity.SOURCE_GOOGLE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var importJob: kotlinx.coroutines.Job? = null

    fun importGoogle(uri: Uri) {
        if (importJob?.isActive == true) return
        _import.value = ImportUi(running = true, phase = "시작하는 중")
        importJob = viewModelScope.launch {
            try {
                val res = GoogleTimelineImporter(app, dao) { p ->
                    _import.value = ImportUi(true, p.phase, p.read, p.added, p.skipped)
                }.run(uri)
                _import.value = ImportUi(false, "완료", 0, res.added, res.skipped, null, res.from, res.to)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _import.value = _import.value?.copy(running = false, phase = "멈춤")
                throw e
            } catch (e: Throwable) {
                _import.value = (_import.value ?: ImportUi(false)).copy(running = false, error = e.message ?: "파일을 읽지 못했어요")
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    fun dismissImport() { if (_import.value?.running != true) _import.value = null }

    suspend fun deleteGoogle(): Int = dao.deleteBySource(PointEntity.SOURCE_GOOGLE)

    suspend fun place(id: Long): PlaceEntity? = app.db.places().get(id)

    suspend fun placeByKind(kind: String): PlaceEntity? = app.db.places().byKind(kind)

    fun savePlace(place: PlaceEntity) = viewModelScope.launch { app.db.places().upsert(place) }

    fun deletePlace(id: Long) = viewModelScope.launch { app.db.places().delete(id) }

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

    /**
     * 전날·다음날로 이동. 그날 기록이 없으면 기록이 있는 가장 가까운 날로 건너뛴다
     * (가져온 구글 기록처럼 듬성듬성한 과거 기록을 하루씩 넘기지 않아도 되게).
     */
    fun shiftDate(days: Long) = viewModelScope.launch {
        val cur = _date.value
        val target = cur.plusDays(days)
        if (dao.countBetween(startOf(target), startOf(target.plusDays(1))) > 0) {
            selectDate(target)
            return@launch
        }
        val jump = if (days < 0) dao.lastBefore(startOf(cur)) else dao.firstFrom(startOf(cur.plusDays(1)))
        selectDate(jump?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: target)
    }

    fun selectDateOf(epochMs: Long) = selectDate(java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    fun setInterval(minutes: Int) = viewModelScope.launch { app.prefs.setIntervalMinutes(minutes) }

    fun setMapStyle(style: String) = viewModelScope.launch { app.prefs.setMapStyle(style) }

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
