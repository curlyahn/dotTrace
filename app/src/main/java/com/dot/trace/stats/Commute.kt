package com.dot.trace.stats

import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.match
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
    /** 집(회사)을 떠난 시각, 회사(집)에 도착한 시각 */
    val start: Long,
    val end: Long,
    val distanceM: Double,
    /** 실제로 이동한 시간 (중간에 들른 곳에 머문 시간은 빼고) */
    val movingMs: Long,
    /** 중간에 들른 곳 수 (예: 탁구장) */
    val stops: Int,
) {
    val durationMs: Long get() = movingMs
}

/**
 * 정리된 기록(사용자 수정까지 반영, 여러 날을 이어 붙인 것)에서 출퇴근을 찾는다.
 * - 집에 머물다 떠나 다음으로 회사에 도착할 때까지 = 출근, 회사 → 집 = 퇴근
 * - 중간에 다른 곳에 들렀어도(탁구, 식사 등 시간과 상관없이) 같은 출퇴근으로 본다
 * - 집을 떠나 다시 집으로 오면(회사에 안 감) 출퇴근이 아니다
 * - 자정을 넘겨도 이어서 본다. 단, 떠난 지 16시간이 넘으면 출퇴근으로 보지 않는다
 * - 이동이 잡히지 않은 경우(지하에서 신호가 끊겨 머무름 두 개만 남음)도 세고, 수단은 '이동'(모름)
 */
object CommuteFinder {

    private const val MAX_TRIP_MS = 16 * 3_600_000L

    /** 이 시간 넘게 기록이 없으면(가만히 있어 위치 업데이트를 끈 동안) 그 앞뒤 자리에 머문 것으로 본다 */
    private const val GAP_MS = 30 * 60_000L

    fun find(segments: List<Segment>, places: List<PlaceEntity> = emptyList()): List<Commute> =
        scan(withGapStays(segments.sortedBy { it.start }, places))

    /**
     * 집·회사에 도착해 가만히 있으면 위치 업데이트를 꺼서 점이 한두 개만 남아, '머무름'으로 잡히지 않는다
     * (밤새 집에 있었는데 타임라인은 걷기로 끝나고 다음 날 걷기로 시작하는 경우).
     * 통계를 낼 때만: 기록이 30분 넘게 비는 자리의 이동 끝·시작점이 집·회사 범위 안이면 그곳에 머문 것으로 본다.
     * (기록 자체는 바꾸지 않는다)
     */
    private fun withGapStays(segs: List<Segment>, places: List<PlaceEntity>): List<Segment> {
        if (places.isEmpty()) return segs
        val out = ArrayList<Segment>(segs.size + 4)
        for ((i, s) in segs.withIndex()) {
            if (!s.stay) {
                val prev = segs.getOrNull(i - 1)
                if (prev == null || s.start - prev.end >= GAP_MS) {
                    places.match(s.lat, s.lng)?.takeIf { it.kind == PlaceEntity.HOME || it.kind == PlaceEntity.WORK }?.let { pl ->
                        out += virtualStay(pl.kind, s.lat, s.lng, (prev?.end ?: s.start - GAP_MS), s.start)
                    }
                }
            }
            out += s
            if (!s.stay) {
                val next = segs.getOrNull(i + 1)
                if (next == null || next.start - s.end >= GAP_MS) {
                    places.match(s.endLat, s.endLng)?.takeIf { it.kind == PlaceEntity.HOME || it.kind == PlaceEntity.WORK }?.let { pl ->
                        out += virtualStay(pl.kind, s.endLat, s.endLng, s.end, next?.start ?: (s.end + GAP_MS))
                    }
                }
            }
        }
        return out
    }

    private fun virtualStay(kind: String, lat: Double, lng: Double, start: Long, end: Long) = Segment(
        points = emptyList(), stay = true, mode = TransportMode.Unknown, start = start, end = end,
        distanceM = 0.0, lat = lat, lng = lng, endLat = lat, endLng = lng, placeKind = kind,
    )

    private fun scan(segments: List<Segment>): List<Commute> {
        val out = ArrayList<Commute>()
        var origin: Segment? = null
        val moves = ArrayList<Segment>()
        var stops = 0
        for (s in segments) {
            val kind = s.placeKind
            if (s.stay && (kind == PlaceEntity.HOME || kind == PlaceEntity.WORK)) {
                val o = origin
                if (o != null && o.placeKind != kind && s.start > o.end && s.start - o.end <= MAX_TRIP_MS) {
                    val byMode = moves.groupBy { it.mode }.mapValues { (_, l) -> l.sumOf { it.distanceM } }
                    val main = byMode.maxByOrNull { it.value }?.key ?: TransportMode.Unknown
                    out += Commute(
                        toWork = o.placeKind == PlaceEntity.HOME,
                        mode = main,
                        start = o.end,
                        end = s.start,
                        distanceM = moves.sumOf { it.distanceM },
                        movingMs = if (moves.isEmpty()) s.start - o.end else moves.sumOf { it.durationMs },
                        stops = stops,
                    )
                }
                // 집·회사에 머물렀으니 여기서 새로 출발 (자정에 잘린 같은 머무름이 이어져도 마찬가지)
                origin = s
                moves.clear()
                stops = 0
            } else if (origin != null) {
                if (s.stay) stops++ else moves += s
            }
        }
        return out
    }
}
