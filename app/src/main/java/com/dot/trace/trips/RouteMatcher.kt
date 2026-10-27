package com.dot.trace.trips

import com.dot.trace.data.Geo
import com.dot.trace.data.PointEntity
import java.util.Locale
import kotlin.math.cos

/**
 * 경로 모양으로 이동 수단 학습하기.
 *
 * 사용자가 이동 수단을 고치면 그 이동의 경로를 약 100m 간격 점으로 줄여 저장해 둔다.
 * 다음 이동이 그 경로와 대부분 겹치면(80m 안) 같은 수단으로 본다.
 * 버스·셔틀처럼 늘 같은 길로 다니는 수단은 타고 내린 곳이 달라도(경로 일부만 타도) 알아본다.
 * 같은 길을 걸어서 지날 때 버스로 잘못 보지 않도록 속도도 함께 확인한다.
 */
object RouteMatcher {

    /** 경로에서 이만큼 안이면 '같은 길' */
    const val NEAR_M = 80.0
    /** 이동의 이 비율 이상이 학습한 경로 위에 있어야 같은 경로 */
    const val MIN_OVERLAP = 0.8
    /** 너무 짧은 이동은 어느 길인지 판단하기 어려워 적용하지 않는다 */
    const val MIN_LENGTH_M = 500.0
    private const val STEP_M = 100.0

    data class LL(val lat: Double, val lng: Double)

    /** 약 100m 간격으로 줄인 경로 */
    fun simplify(points: List<PointEntity>): List<LL> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<LL>()
        var last: PointEntity? = null
        for (p in points) {
            if (last == null || Geo.distance(last.lat, last.lng, p.lat, p.lng) >= STEP_M) {
                out += LL(p.lat, p.lng)
                last = p
            }
        }
        val end = points.last()
        if (out.last().lat != end.lat || out.last().lng != end.lng) out += LL(end.lat, end.lng)
        return out
    }

    fun encode(path: List<LL>): String =
        path.joinToString(";") { "%.5f,%.5f".format(Locale.US, it.lat, it.lng) }

    fun decode(s: String?): List<LL> =
        s?.split(';')?.mapNotNull { t ->
            val f = t.split(',')
            val a = f.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val b = f.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            LL(a, b)
        } ?: emptyList()

    /** 이동 경로 중 학습한 경로 위(NEAR_M 안)에 있는 점의 비율 (0~1) */
    fun overlap(move: List<LL>, learned: List<LL>): Double {
        if (move.isEmpty() || learned.size < 2) return 0.0
        val near = move.count { distanceToPath(it, learned) <= NEAR_M }
        return near.toDouble() / move.size
    }

    /** 이 수단으로 보기에 속도가 그럴듯한지 (같은 길을 걸을 때 버스로 보지 않게) */
    fun speedFits(mode: TransportMode, kmh: Double): Boolean = when (mode) {
        TransportMode.Walk -> kmh < 9
        TransportMode.Bicycle -> kmh in 7.0..35.0
        TransportMode.Bus, TransportMode.Car -> kmh >= 10
        TransportMode.Subway, TransportMode.Train -> kmh >= 15
        TransportMode.Flight -> kmh >= 150
        TransportMode.Unknown -> true
    }

    private fun distanceToPath(p: LL, path: List<LL>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val d = distanceToSegment(p, path[i], path[i + 1])
            if (d < best) best = d
            if (best <= 5) break
        }
        return best
    }

    /** 점과 선분 사이 거리 (m). 좁은 범위라 평면으로 근사 */
    private fun distanceToSegment(p: LL, a: LL, b: LL): Double {
        val k = cos(Math.toRadians(p.lat)) * 111_320.0
        val ax = (a.lng - p.lng) * k; val ay = (a.lat - p.lat) * 110_540.0
        val bx = (b.lng - p.lng) * k; val by = (b.lat - p.lat) * 110_540.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx; val y = ay + t * dy
        return Math.sqrt(x * x + y * y)
    }
}
