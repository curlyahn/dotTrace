package com.dot.trace.trips

import com.dot.trace.data.AppDatabase
import com.dot.trace.data.DayCacheEntity
import com.dot.trace.data.Geo
import com.dot.trace.data.ModeRuleEntity
import com.dot.trace.data.ModeSpanEntity
import com.dot.trace.data.OverrideEntity
import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.PoiNameEntity
import com.dot.trace.data.PointEntity
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
    val spans: List<ModeSpanEntity> = emptyList(),
) {
    /** 경로를 학습한 규칙과 그 경로 (한 번만 풀어 둔다) */
    val routes: List<Pair<ModeRuleEntity, List<RouteMatcher.LL>>> by lazy {
        rules.mapNotNull { r -> RouteMatcher.decode(r.path).takeIf { it.size >= 2 }?.let { r to it } }
    }
}

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
    fun apply(segments: List<Segment>, c: Corrections): List<Segment> = applySpans(segments, c.spans).map { seg ->
        // 탑승~하차로 정한 구간은 그대로
        if (seg.spanId != null) return@map seg
        val ov = c.overrides.filter { overlaps(seg, it) }.maxByOrNull { overlap(seg, it) }
        if (seg.stay) {
            val place = c.places.match(seg.lat, seg.lng)
            // Google 장소 이름이 있으면 먼저, 없으면 예전 지도에서 찾은 이름
            val near = c.poi.map { it to Geo.distance(seg.lat, seg.lng, it.lat, it.lng) }
                .filter { it.second <= POI_RADIUS_M && it.first.name.isNotBlank() }
            val poi = (near.filter { it.first.source == PoiNameEntity.SOURCE_GOOGLE }.minByOrNull { it.second }
                ?: near.minByOrNull { it.second })?.first
            val name = ov?.name ?: place?.name ?: poi?.name
            seg.copy(
                name = name,
                edited = ov?.name != null,
                placeKind = place?.kind,
                nameFromGoogle = ov?.name == null && place == null && poi?.source == PoiNameEntity.SOURCE_GOOGLE,
            )
        } else {
            val overridden = ov?.mode?.let { modeOf(it) }
            // 1) 학습한 경로와 대부분 겹치면 그 수단 (가장 많이 겹치는 경로)
            val routed = if (overridden == null) routeMode(seg, c) else null
            // 2) 같은 출발지·도착지 규칙
            val ruled = if (overridden == null && routed == null) c.rules.firstOrNull {
                Geo.distance(seg.lat, seg.lng, it.startLat, it.startLng) <= RULE_RADIUS_M &&
                    Geo.distance(seg.endLat, seg.endLng, it.endLat, it.endLng) <= RULE_RADIUS_M
            }?.let { modeOf(it.mode) } else null
            seg.copy(
                mode = overridden ?: routed ?: ruled ?: seg.mode,
                edited = overridden != null,
                learned = overridden == null && (routed ?: ruled) != null,
            )
        }
    }

    /**
     * 탑승~하차 구간 적용: 그 시간 사이를 한 이동(정한 수단)으로 만들고, 앞뒤는 원래 판단대로 잘라 남긴다.
     * 머무름으로 판단됐던 부분도 그 사이에 있으면 이동으로 바꾼다 (정류장에서 기다린 시간 등).
     */
    fun applySpans(segments: List<Segment>, spans: List<ModeSpanEntity>): List<Segment> {
        var cur = segments
        for (sp in spans.sortedBy { it.start }) {
            val mode = modeOf(sp.mode) ?: continue
            if (sp.end <= sp.start || cur.none { it.end > sp.start && it.start < sp.end }) continue
            val before = ArrayList<Segment>()
            val inside = ArrayList<Segment>()
            val after = ArrayList<Segment>()
            for (s in cur) {
                piece(s, Long.MIN_VALUE, sp.start)?.let { before += it }
                piece(s, sp.start, sp.end)?.let { inside += it }
                piece(s, sp.end, Long.MAX_VALUE)?.let { after += it }
            }
            if (inside.isEmpty()) continue
            val pts = inside.flatMap { it.points }.sortedBy { it.time }
            val first = inside.first(); val last = inside.last()
            val ride = Segment(
                points = pts,
                stay = false,
                mode = mode,
                start = maxOf(sp.start, first.start),
                end = minOf(sp.end, last.end),
                distanceM = inside.sumOf { if (it.stay) 0.0 else it.distanceM },
                lat = pts.firstOrNull()?.lat ?: first.lat,
                lng = pts.firstOrNull()?.lng ?: first.lng,
                endLat = pts.lastOrNull()?.lat ?: last.endLat,
                endLng = pts.lastOrNull()?.lng ?: last.endLng,
                edited = true,
                spanId = sp.id,
            )
            cur = before + ride + after
        }
        return cur
    }

    /** 구간 s 중 [from, to) 시간 부분. 기록 점의 시각이 없으면(저장해 둔 분석) 시간 비율로 나눈다 */
    private fun piece(s: Segment, from: Long, to: Long): Segment? {
        val a = maxOf(s.start, from)
        val b = minOf(s.end, to)
        if (b <= a) return null
        if (a == s.start && b == s.end) return s
        val timed = s.points.any { it.time > 0 }
        val pts = if (timed) s.points.filter { it.time in a until b } else s.points
        val frac = (b - a).toDouble() / (s.end - s.start).coerceAtLeast(1)
        val dist = when {
            s.stay -> 0.0
            timed && pts.size >= 2 -> pts.zipWithNext { p, q -> Geo.distance(p.lat, p.lng, q.lat, q.lng).toDouble() }.sum()
            else -> s.distanceM * frac
        }
        return s.copy(
            points = pts,
            start = a,
            end = b,
            distanceM = dist,
            lat = if (s.stay) s.lat else pts.firstOrNull()?.lat ?: s.lat,
            lng = if (s.stay) s.lng else pts.firstOrNull()?.lng ?: s.lng,
            endLat = if (s.stay) s.lat else pts.lastOrNull()?.lat ?: s.endLat,
            endLng = if (s.stay) s.lng else pts.lastOrNull()?.lng ?: s.endLng,
        )
    }

    private fun modeOf(name: String): TransportMode? = runCatching { TransportMode.valueOf(name) }.getOrNull()

    private fun routeMode(seg: Segment, c: Corrections): TransportMode? {
        if (c.routes.isEmpty() || seg.distanceM < RouteMatcher.MIN_LENGTH_M || seg.points.size < 2) return null
        val hours = seg.durationMs / 3_600_000.0
        val kmh = if (hours > 0) seg.distanceM / 1000 / hours else 0.0
        val path = RouteMatcher.simplify(seg.points)
        return c.routes.asSequence()
            .mapNotNull { (rule, learned) ->
                val m = modeOf(rule.mode) ?: return@mapNotNull null
                if (!RouteMatcher.speedFits(m, kmh)) return@mapNotNull null
                m to RouteMatcher.overlap(path, learned)
            }
            .filter { it.second >= RouteMatcher.MIN_OVERLAP }
            .maxByOrNull { it.second }
            ?.first
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
        val segs = TripClassifier.classify(PathCleaner.clean(pd.between(from, to)))
        db.analysis().putCache(DayCacheEntity(key, count, last, encode(segs)))
        return segs
    }

    suspend fun corrections(from: Long, to: Long): Corrections {
        val a = db.analysis()
        return Corrections(a.overridesBetween(from, to), a.rules(), db.places().all(), a.poi(), a.spansBetween(from, to))
    }

    // v2: 이동 구간에는 줄인 경로도 저장 (경로 학습을 리포트에도 적용하려고)
    private fun encode(segs: List<Segment>): String = buildString {
        append("v3\n")
        for (s in segs) {
            append(if (s.stay) 's' else 'm').append('|')
            append(s.start).append('|').append(s.end).append('|').append(s.mode.name).append('|')
            append(s.distanceM).append('|').append(s.lat).append('|').append(s.lng).append('|')
            append(s.endLat).append('|').append(s.endLng).append('|')
            if (!s.stay) append(RouteMatcher.encode(RouteMatcher.simplify(s.points)))
            append('\n')
        }
    }

    private fun decode(data: String): List<Segment>? = runCatching {
        val lines = data.lines()
        if (lines.firstOrNull() != "v3") return null
        lines.drop(1).filter { it.isNotBlank() }.map { l ->
            val f = l.split('|')
            val path = RouteMatcher.decode(f.getOrNull(9)?.takeIf { it.isNotEmpty() })
            Segment(
                points = path.map { PointEntity(time = 0, lat = it.lat, lng = it.lng, accuracy = null, altitude = null, speed = null) },
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
