package com.dot.trace.trips

import android.location.Location
import com.dot.trace.data.PointEntity
import com.google.android.gms.location.DetectedActivity

enum class TransportMode(val label: String) {
    Walk("걷기"),
    Bicycle("자전거"),
    Bus("버스"),
    Subway("지하철"),
    Car("자가용"),
    Train("기차"),
    Flight("비행기"),
    Unknown("이동"),
}

/**
 * 구글 타임라인처럼 이동 수단이 정해져 들어온 기록에 붙이는 활동 코드.
 * OS 활동 인식 값(DetectedActivity: 0~8)과 겹치지 않게 100부터 쓴다.
 */
object ActivityCode {
    const val BUS = 100
    const val SUBWAY = 101
    const val TRAIN = 102
    const val FLIGHT = 103
    const val CAR = 104

    fun mode(code: Int): TransportMode? = when (code) {
        BUS -> TransportMode.Bus
        SUBWAY -> TransportMode.Subway
        TRAIN -> TransportMode.Train
        FLIGHT -> TransportMode.Flight
        CAR -> TransportMode.Car
        else -> null
    }
}

/** 하루 기록을 나눈 한 구간: 머무름 또는 한 가지 수단으로 한 이동 */
data class Segment(
    val points: List<PointEntity>,
    val stay: Boolean,
    val mode: TransportMode,
    val start: Long,
    val end: Long,
    val distanceM: Double,
)

/**
 * 기록된 위치들로 이동 수단을 추정한다. 모두 기기 안에서 계산한다.
 *
 * 쓰는 단서
 * - OS 활동 인식: 걷기·뛰기·자전거·차량
 * - 속도: 평균, 상위 15% 속도, 최고 속도
 * - 신호 끊김: 위치가 안 잡힌 채 수 km를 이동 → 지하(지하철)
 * - 함께 이동하는 와이파이: 같은 공유기가 1km 넘게 계속 보임 → 버스·지하철 같은 대중교통 차량 안
 * - 정차 패턴: 1km당 정차 횟수가 많고 최고 속도가 낮음 → 버스, 아니면 자가용
 */
object TripClassifier {

    private const val STAY_RADIUS_M = 100f
    private const val STAY_MIN_MS = 5 * 60_000L
    private const val WALK_MAX_KMH = 7.0
    private const val MIN_SEGMENT_MS = 2 * 60_000L

    fun classify(points: List<PointEntity>): List<Segment> {
        if (points.size < 2) return emptyList()
        val stays = findStays(points)

        val out = ArrayList<Segment>()
        var prevEnd = -1 // 앞 머무름의 마지막 위치
        for (range in stays + listOf(points.size..points.size)) {
            // 앞 머무름의 끝 ~ 이번 머무름의 처음 사이가 이동 (두 끝 위치를 포함해 거리를 잇는다)
            val from = prevEnd.coerceAtLeast(0)
            val to = range.first.coerceAtMost(points.lastIndex)
            if (to > from && (to - from >= 2 || dist(points[from], points[to]) > STAY_RADIUS_M)) {
                out += splitTrip(points.subList(from, to + 1))
            }
            if (range.first < points.size) {
                val sp = points.subList(range.first, range.last + 1)
                out += Segment(sp, true, TransportMode.Unknown, sp.first().time, sp.last().time, 0.0)
                prevEnd = range.last
            }
        }
        return out.filter { it.end > it.start }
    }

    /** 반경 100m 안에 5분 이상 머문 구간들 */
    private fun findStays(p: List<PointEntity>): List<IntRange> {
        val stays = ArrayList<IntRange>()
        var i = 0
        while (i < p.size) {
            var j = i + 1
            while (j < p.size && dist(p[i], p[j]) <= STAY_RADIUS_M) j++
            if (p[j - 1].time - p[i].time >= STAY_MIN_MS) {
                stays += i..(j - 1)
                i = j
            } else {
                i++
            }
        }
        return stays
    }

    /** 한 번의 이동을 걷기 구간과 탈것 구간으로 나눈 뒤 각각 수단을 정한다 (예: 걷기 → 지하철 → 걷기) */
    private fun splitTrip(all: List<PointEntity>): List<Segment> {
        // 정지 중(위치 업데이트 꺼짐)이라 길게 비어 있는 처음·마지막 구간은 이동이 아니므로 잘라 낸다
        var a = 0
        var z = all.lastIndex
        while (z - a >= 1 && isIdleLeg(all[a], all[a + 1])) a++
        while (z - a >= 1 && isIdleLeg(all[z - 1], all[z])) z--
        val p = all.subList(a, z + 1)
        if (p.size < 2) return emptyList()
        // 구간(leg)마다 걷기 속도인지 표시
        val walkLeg = BooleanArray(p.size - 1) { k ->
            val a = p[k]; val b = p[k + 1]
            val dt = (b.time - a.time) / 3_600_000.0
            val kmh = if (dt > 0) dist(a, b) / 1000.0 / dt else 0.0
            val act = b.activity
            when (act) {
                DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT -> kmh < 15
                DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE -> false
                in 100..199 -> false // 버스·지하철 등 정해진 이동 수단
                else -> kmh < WALK_MAX_KMH && (b.lostMs ?: 0L) == 0L
            }
        }
        // 같은 종류끼리 묶기
        val runs = ArrayList<IntRange>() // leg 인덱스 범위
        var s = 0
        for (k in 1..walkLeg.size) {
            if (k == walkLeg.size || walkLeg[k] != walkLeg[s]) {
                runs += s until k
                s = k
            }
        }
        // 2분 미만의 짧은 구간은 앞 구간에 붙인다 (신호 대기, 잠깐 걷기 등)
        val merged = ArrayList<IntRange>()
        for (r in runs) {
            val dur = p[r.last + 1].time - p[r.first].time
            val prev = merged.lastOrNull()
            if (prev != null && (dur < MIN_SEGMENT_MS || legDuration(p, prev) < MIN_SEGMENT_MS)) {
                merged[merged.lastIndex] = prev.first..r.last
            } else {
                merged += r
            }
        }
        return merged.map { r ->
            val sp = p.subList(r.first, r.last + 2)
            Segment(sp, false, modeOf(sp), sp.first().time, sp.last().time, pathLength(sp))
        }
    }

    /** 20분 넘게 비어 있고 그 사이 시속 3km도 안 되게 움직였다면 그냥 머물러 있던 것 */
    private fun isIdleLeg(a: PointEntity, b: PointEntity): Boolean {
        val dtMs = b.time - a.time
        if (dtMs < 20 * 60_000L) return false
        return dist(a, b) / 1000.0 / (dtMs / 3_600_000.0) < 3.0
    }

    private fun legDuration(p: List<PointEntity>, r: IntRange) = p[r.last + 1].time - p[r.first].time

    private fun modeOf(p: List<PointEntity>): TransportMode {
        val durH = (p.last().time - p.first().time) / 3_600_000.0
        val distKm = pathLength(p) / 1000.0
        if (durH <= 0 || distKm < 0.05) return TransportMode.Unknown
        val avg = distKm / durH

        // 구간별 속도 (끊긴 구간 제외)
        val legKmh = ArrayList<Double>()
        var gapMs = 0L
        for (k in 0 until p.size - 1) {
            val a = p[k]; val b = p[k + 1]
            val dtMs = b.time - a.time
            val d = dist(a, b)
            val kmh = if (dtMs > 0) d / 1000.0 / (dtMs / 3_600_000.0) else 0.0
            // 위치가 안 잡힌 채로 멀리 이동한 구간 = 지하·실내 이동
            val lost = (b.lostMs ?: 0L) > 90_000L || (dtMs > 3 * 60_000L && d > 500)
            if (lost) gapMs += dtMs else legKmh += kmh
        }
        val gapFrac = gapMs.toDouble() / (p.last().time - p.first().time)
        val sorted = legKmh.sorted()
        val p85 = if (sorted.isEmpty()) avg else sorted[(sorted.size * 0.85).toInt().coerceAtMost(sorted.lastIndex)]
        val max = (sorted.lastOrNull() ?: avg).coerceAtLeast(p.mapNotNull { it.speed }.maxOrNull()?.times(3.6) ?: 0.0)

        // 활동 인식 결과 (시간 비중)
        val acts = p.mapNotNull { it.activity }.groupingBy { it }.eachCount()
        val actTotal = acts.values.sum().coerceAtLeast(1)
        fun share(vararg t: Int) = t.sumOf { acts[it] ?: 0 }.toDouble() / actTotal
        val walkShare = share(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT)
        val bikeShare = share(DetectedActivity.ON_BICYCLE)
        val vehicleShare = share(DetectedActivity.IN_VEHICLE)

        val movingWifi = hasMovingWifi(p)
        val transitWifi = p.count { it.transitWifi } >= 2
        val stopsPerKm = countStops(p) / distKm.coerceAtLeast(0.3)

        // 이동 수단이 이미 정해져 들어온 기록(구글 타임라인 가져오기)은 그대로 따른다
        acts.filterKeys { ActivityCode.mode(it) != null }.maxByOrNull { it.value }?.let { (code, n) ->
            if (n.toDouble() / actTotal >= 0.5) return ActivityCode.mode(code)!!
        }

        return when {
            avg >= 250 -> TransportMode.Flight
            avg >= 110 || max >= 170 -> TransportMode.Train
            gapFrac >= 0.35 && avg in 15.0..90.0 -> TransportMode.Subway
            movingWifi && gapFrac >= 0.15 -> TransportMode.Subway
            walkShare >= 0.6 || avg < 6 -> TransportMode.Walk
            bikeShare >= 0.5 -> TransportMode.Bicycle
            vehicleShare < 0.3 && avg in 8.0..28.0 && p85 < 35 -> TransportMode.Bicycle
            movingWifi || (transitWifi && max < 80) -> TransportMode.Bus
            stopsPerKm >= 1.2 && avg <= 30 && max <= 70 -> TransportMode.Bus
            vehicleShare >= 0.3 || avg >= 20 -> TransportMode.Car
            else -> TransportMode.Unknown
        }
    }

    /** 같은 와이파이 공유기가 1km 넘게 떨어진 두 위치에서 모두 보였다면 '함께 이동하는 와이파이' */
    private fun hasMovingWifi(p: List<PointEntity>): Boolean {
        val firstSeen = HashMap<String, PointEntity>()
        for (pt in p) {
            val list = pt.wifi?.split(',')?.filter { it.isNotBlank() } ?: continue
            for (b in list) {
                val f = firstSeen[b]
                if (f == null) firstSeen[b] = pt
                else if (dist(f, pt) > 1000f) return true
            }
        }
        return false
    }

    /** 움직이다 멈춘(시속 3km 미만) 뒤 다시 움직인 횟수 */
    private fun countStops(p: List<PointEntity>): Int {
        var stops = 0
        var moving = false
        var stopped = false
        for (k in 1 until p.size) {
            val a = p[k - 1]; val b = p[k]
            val dt = (b.time - a.time) / 1000.0
            if (dt <= 0 || dt > 120) continue
            val mps = b.speed?.toDouble() ?: (dist(a, b) / dt)
            if (mps > 4.0) {
                if (stopped && moving) stops++
                moving = true
                stopped = false
            } else if (mps < 0.8) {
                stopped = true
            }
        }
        return stops
    }

    fun pathLength(p: List<PointEntity>): Double {
        var d = 0.0
        for (k in 1 until p.size) d += dist(p[k - 1], p[k])
        return d
    }

    private val tmp = FloatArray(1)
    private fun dist(a: PointEntity, b: PointEntity): Float {
        Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, tmp)
        return tmp[0]
    }
}
