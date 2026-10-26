package com.dot.trace.trips

import com.dot.trace.data.AppDatabase
import com.dot.trace.data.DayCacheEntity
import com.dot.trace.data.Geo
import com.dot.trace.data.ModeRuleEntity
import com.dot.trace.data.OverrideEntity
import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.PoiNameEntity
import com.dot.trace.data.match
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** 사용자가 고친 내용과 이름 정보: 분석 결과에 덧씌운다 */
data class Corrections(
    val overrides: List<OverrideEntity> = emptyList(),
    val rules: List<ModeRuleEntity> = emptyList(),
    val places: List<PlaceEntity> = emptyList(),
    val poi: List<PoiNameEntity> = emptyList(),
)

object DayAnalyzer {

    /** 같은 경로로 볼 출발·도착 반경 */
    const val RULE_RADIUS_M = 300f
    /** 지도 상호 이름을 머문 곳에 붙일 최대 거리 */
    const val POI_RADIUS_M = 60f

    /**
     * 분석 결과에 사용자 수정·학습 규칙·이름을 적용한다.
     * 1) 이 구간을 직접 고쳤으면 그대로
     * 2) 이동은 같은 출발·도착 규칙이 있으면 그 수단
     * 3) 머문 곳 이름: 직접 고친 이름 > 내 장소(집·회사·자주 가는 곳·기억한 이름) > 지도 상호
     */
    fun apply(segments: List<Segment>, c: Corrections): List<Segment> = segments.map { seg ->
        val ov = c.overrides.filter { overlaps(seg, it) }.maxByOrNull { overlap(seg, it) }
        if (seg.stay) {
            val place = c.places.match(seg.lat, seg.lng)
            val poi = c.poi.map { it to Geo.distance(seg.lat, seg.lng, it.lat, it.lng) }
                .filter { it.second <= POI_RADIUS_M }.minByOrNull { it.second }?.first
            seg.copy(
                name = ov?.name ?: place?.name ?: poi?.name,
                edited = ov?.name != null,
                placeKind = place?.kind,
            )
        } else {
            val overridden = ov?.mode?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
            val ruled = if (overridden == null) c.rules.firstOrNull {
                Geo.distance(seg.lat, seg.lng, it.startLat, it.startLng) <= RULE_RADIUS_M &&
                    Geo.distance(seg.endLat, seg.endLng, it.endLat, it.endLng) <= RULE_RADIUS_M
            }?.let { runCatching { TransportMode.valueOf(it.mode) }.getOrNull() } else null
            seg.copy(mode = overridden ?: ruled ?: seg.mode, edited = overridden != null)
        }
    }

    private fun overlap(s: Segment, o: OverrideEntity): Long =
        (minOf(s.end, o.end) - maxOf(s.start, o.start)).coerceAtLeast(0)

    /** 구간의 절반 이상이 겹치면 같은 구간으로 본다 (다시 분석해 경계가 조금 바뀌어도 수정이 유지되게) */
    private fun overlaps(s: Segment, o: OverrideEntity): Boolean {
        val ov = overlap(s, o)
        val shorter = minOf(s.end - s.start, o.end - o.start).coerceAtLeast(1)
        return ov * 2 >= shorter
    }

    fun poiKey(lat: Double, lng: Double) = "%.4f,%.4f".format(Locale.US, lat, lng)
}

/**
 * 하루 분석 결과를 저장해 두고 다시 쓴다 (리포트·자동 제안용).
 * 그날 기록 개수나 마지막 시각이 바뀌었으면 다시 분석한다.
 */
class DayStore(private val db: AppDatabase) {
    private val zone: ZoneId = ZoneId.systemDefault()

    suspend fun segments(day: LocalDate): List<Segment> {
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pd = db.points()
        val count = pd.countBetween(from, to)
        if (count == 0) return emptyList()
        val last = pd.lastBefore(to) ?: 0L
        val key = day.toString()
        db.analysis().cached(key)?.takeIf { it.pointCount == count && it.lastTime == last }?.let { c ->
            decode(c.data)?.let { return it }
        }
        val segs = TripClassifier.classify(pd.between(from, to))
        db.analysis().putCache(DayCacheEntity(key, count, last, encode(segs)))
        return segs
    }

    suspend fun corrections(from: Long, to: Long): Corrections {
        val a = db.analysis()
        return Corrections(a.overridesBetween(from, to), a.rules(), db.places().all(), a.poi())
    }

    private fun encode(segs: List<Segment>): String = buildString {
        append("v1\n")
        for (s in segs) {
            append(if (s.stay) 's' else 'm').append('|')
            append(s.start).append('|').append(s.end).append('|').append(s.mode.name).append('|')
            append(s.distanceM).append('|').append(s.lat).append('|').append(s.lng).append('|')
            append(s.endLat).append('|').append(s.endLng).append('\n')
        }
    }

    private fun decode(data: String): List<Segment>? = runCatching {
        val lines = data.lines()
        if (lines.firstOrNull() != "v1") return null
        lines.drop(1).filter { it.isNotBlank() }.map { l ->
            val f = l.split('|')
            Segment(
                points = emptyList(),
                stay = f[0] == "s",
                mode = TransportMode.valueOf(f[3]),
                start = f[1].toLong(),
                end = f[2].toLong(),
                distanceM = f[4].toDouble(),
                lat = f[5].toDouble(),
                lng = f[6].toDouble(),
                endLat = f[7].toDouble(),
                endLng = f[8].toDouble(),
            )
        }
    }.getOrNull()
}
