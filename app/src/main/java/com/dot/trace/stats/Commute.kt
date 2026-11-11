package com.dot.trace.stats

import com.dot.trace.data.PlaceEntity
import com.dot.trace.trips.Segment
import com.dot.trace.trips.TransportMode

/**
 * 출퇴근 한 번.
 * mode = 그 출퇴근에서 가장 긴 거리를 이동한 수단 (예: 지하철 17km + 자전거 1km → 지하철)
 */
data class Commute(
    /** true = 출근(집 → 회사), false = 퇴근(회사 → 집) */
    val toWork: Boolean,
    val mode: TransportMode,
    val start: Long,
    val end: Long,
    val distanceM: Double,
) {
    val durationMs: Long get() = end - start
}

/**
 * 하루 정리 결과(사용자 수정까지 반영된 것)에서 출퇴근을 찾는다.
 * - 집에 머물다 떠나 회사에 도착할 때까지 = 출근, 회사 → 집 = 퇴근
 * - 사이에 잠깐 들른 곳(30분 미만)은 같은 출퇴근으로 묶는다
 * - 30분 넘게 다른 곳에 머물렀거나 3시간이 넘게 걸리면 출퇴근으로 보지 않는다
 */
object CommuteFinder {

    private const val SHORT_STOP_MS = 30 * 60_000L
    private const val MAX_TRIP_MS = 3 * 3_600_000L

    fun find(segments: List<Segment>): List<Commute> {
        val out = ArrayList<Commute>()
        var origin: Segment? = null
        val moves = ArrayList<Segment>()
        for (s in segments.sortedBy { it.start }) {
            val kind = s.placeKind
            if (s.stay && (kind == PlaceEntity.HOME || kind == PlaceEntity.WORK)) {
                val o = origin
                if (o != null && o.placeKind != kind && moves.isNotEmpty() && s.start - o.end <= MAX_TRIP_MS) {
                    val byMode = moves.groupBy { it.mode }.mapValues { (_, l) -> l.sumOf { it.distanceM } }
                    val main = byMode.maxByOrNull { it.value }?.key ?: TransportMode.Unknown
                    out += Commute(
                        toWork = o.placeKind == PlaceEntity.HOME,
                        mode = main,
                        start = o.end,
                        end = s.start,
                        distanceM = moves.sumOf { it.distanceM },
                    )
                }
                // 집·회사에 머물렀으니 여기서 새로 출발
                origin = s
                moves.clear()
            } else if (s.stay) {
                // 다른 곳에 오래 머물렀으면 출퇴근이 아니다
                if (s.durationMs >= SHORT_STOP_MS) { origin = null; moves.clear() }
            } else if (origin != null) {
                moves += s
            }
        }
        return out
    }
}
