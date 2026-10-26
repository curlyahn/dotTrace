package com.dot.trace.stats

import android.content.Context
import com.dot.trace.data.Geo
import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.PointDao
import com.dot.trace.data.match
import com.dot.trace.trips.TransportMode
import com.dot.trace.trips.TripClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 통계 기간: 한 달 또는 한 해 */
sealed class Period {
    abstract val start: LocalDate
    abstract val endExclusive: LocalDate
    abstract val label: String
    abstract fun prev(): Period
    abstract fun next(): Period
    abstract val key: String

    data class Month(val ym: YearMonth) : Period() {
        override val start: LocalDate get() = ym.atDay(1)
        override val endExclusive: LocalDate get() = ym.plusMonths(1).atDay(1)
        override val label get() = "${ym.year}년 ${ym.monthValue}월"
        override fun prev() = Month(ym.minusMonths(1))
        override fun next() = Month(ym.plusMonths(1))
        override val key get() = "M$ym"
    }

    data class Year(val year: Int) : Period() {
        override val start: LocalDate get() = LocalDate.of(year, 1, 1)
        override val endExclusive: LocalDate get() = LocalDate.of(year + 1, 1, 1)
        override val label get() = "${year}년"
        override fun prev() = Year(year - 1)
        override fun next() = Year(year + 1)
        override val key get() = "Y$year"
    }
}

data class ModeStat(val mode: TransportMode, val distanceM: Double, val durationMs: Long, val trips: Int)

/** 머문 곳 (반경 150m로 묶음) */
data class StayPlace(
    val lat: Double,
    val lng: Double,
    val stayMs: Long,
    val visits: Int,
    val days: Int,
    /** 내 장소 이름, 없으면 null */
    val placeName: String?,
    /** 주소 한 줄 (찾은 경우) */
    val address: String?,
    /** 시·구 단위 지역 */
    val region: String?,
)

data class RegionStat(val name: String, val stayMs: Long, val visits: Int, val days: Int)

data class Report(
    val period: Period,
    val recordedDays: Int,
    val points: Int,
    val totalDistanceM: Double,
    val movingMs: Long,
    val modes: List<ModeStat>,
    val topPlaces: List<StayPlace>,
    val regions: List<RegionStat>,
) {
    val isEmpty get() = points == 0
}

object ReportBuilder {

    private const val CLUSTER_RADIUS_M = 150f
    /** 주소를 찾아볼 머문 곳 최대 개수 (오래 머문 순) */
    private const val GEOCODE_LIMIT = 40

    suspend fun build(context: Context, dao: PointDao, places: List<PlaceEntity>, period: Period): Report =
        withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            val modeDist = HashMap<TransportMode, Double>()
            val modeTime = HashMap<TransportMode, Long>()
            val modeTrips = HashMap<TransportMode, Int>()
            val clusters = ArrayList<Cluster>()
            var points = 0
            var days = 0

            // 하루씩 나눠 머무름·이동을 구한다 (한 번에 메모리에 너무 많이 올리지 않도록)
            var d = period.start
            val today = LocalDate.now(zone)
            while (d.isBefore(period.endExclusive) && !d.isAfter(today)) {
                val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val dayPoints = dao.between(from, to)
                if (dayPoints.isNotEmpty()) {
                    days++
                    points += dayPoints.size
                    for (seg in TripClassifier.classify(dayPoints)) {
                        val dur = seg.end - seg.start
                        if (seg.stay) {
                            val lat = seg.points.map { it.lat }.average()
                            val lng = seg.points.map { it.lng }.average()
                            val c = clusters.firstOrNull { Geo.distance(it.lat, it.lng, lat, lng) <= CLUSTER_RADIUS_M }
                            if (c == null) clusters += Cluster(lat, lng, dur, 1, hashSetOf(d))
                            else { c.stayMs += dur; c.visits++; c.days += d }
                        } else if (seg.mode != TransportMode.Unknown || seg.distanceM > 0) {
                            modeDist[seg.mode] = (modeDist[seg.mode] ?: 0.0) + seg.distanceM
                            modeTime[seg.mode] = (modeTime[seg.mode] ?: 0L) + dur
                            modeTrips[seg.mode] = (modeTrips[seg.mode] ?: 0) + 1
                        }
                    }
                }
                d = d.plusDays(1)
            }

            // 오래 머문 곳부터 주소·지역을 찾는다
            val sorted = clusters.sortedByDescending { it.stayMs }
            val stays = sorted.mapIndexed { i, c ->
                val place = places.match(c.lat, c.lng)
                val addr = if (i < GEOCODE_LIMIT) Geo.address(context, c.lat, c.lng) else null
                StayPlace(
                    lat = c.lat, lng = c.lng, stayMs = c.stayMs, visits = c.visits, days = c.days.size,
                    placeName = place?.name,
                    address = addr?.let { Geo.line(it) },
                    region = addr?.let { Geo.region(it) },
                )
            }
            val regions = stays.filter { it.region != null }.groupBy { it.region!! }.map { (name, list) ->
                RegionStat(name, list.sumOf { it.stayMs }, list.sumOf { it.visits }, list.sumOf { it.days })
            }.sortedByDescending { it.stayMs }

            val modes = TransportMode.entries.mapNotNull { m ->
                val dist = modeDist[m] ?: return@mapNotNull null
                ModeStat(m, dist, modeTime[m] ?: 0L, modeTrips[m] ?: 0)
            }.sortedByDescending { it.distanceM }

            Report(
                period = period,
                recordedDays = days,
                points = points,
                totalDistanceM = modes.sumOf { it.distanceM },
                movingMs = modes.sumOf { it.durationMs },
                modes = modes,
                topPlaces = stays.take(10),
                regions = regions,
            )
        }

    private class Cluster(val lat: Double, val lng: Double, var stayMs: Long, var visits: Int, val days: HashSet<LocalDate>)
}
