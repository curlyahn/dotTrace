package com.dot.trace.trips

import com.dot.trace.data.Geo
import com.dot.trace.data.PlaceEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** '여기를 집(회사)으로 등록할까요?' 제안 */
data class PlaceSuggestion(val kind: String, val lat: Double, val lng: Double, val days: Int) {
    /** 닫은 제안을 기억하는 열쇠 (종류 + 약 100m 단위 위치) */
    val key: String get() = "$kind@" + "%.3f,%.3f".format(Locale.US, lat, lng)
}

/**
 * 최근 3주 기록으로 집·회사를 추정한다.
 * - 집: 새벽 3~5시에 머문 곳이 3일 이상 같은 곳
 * - 회사: 평일 낮(11~15시)에 3시간 넘게 머문 곳이 3일 이상 같은 곳 (집과 다른 곳)
 * 이미 등록했거나, 제안을 닫은 곳은 다시 제안하지 않는다.
 */
object PlaceSuggester {

    private const val SAME_PLACE_M = 150f
    private const val MIN_DAYS = 3

    fun suggest(
        byDay: Map<LocalDate, List<Segment>>,
        places: List<PlaceEntity>,
        dismissed: Set<String>,
        zone: ZoneId,
    ): PlaceSuggestion? {
        val home = places.firstOrNull { it.kind == PlaceEntity.HOME }
        val work = places.firstOrNull { it.kind == PlaceEntity.WORK }

        if (home == null) {
            val night = clusters(byDay, zone) { day, s ->
                // 그날 새벽 3~5시(또는 전날 밤부터 이어진 머무름)를 덮는 머무름
                val a = day.atTime(LocalTime.of(3, 0)).atZone(zone).toInstant().toEpochMilli()
                val b = day.atTime(LocalTime.of(5, 0)).atZone(zone).toInstant().toEpochMilli()
                s.start <= b && s.end >= a
            }
            night.firstOrNull { it.days.size >= MIN_DAYS }
                ?.let { PlaceSuggestion(PlaceEntity.HOME, it.lat, it.lng, it.days.size) }
                ?.takeIf { it.key !in dismissed }
                ?.let { return it }
        }

        if (work == null) {
            val day = clusters(byDay, zone) { d, s ->
                if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return@clusters false
                val a = d.atTime(LocalTime.of(11, 0)).atZone(zone).toInstant().toEpochMilli()
                val b = d.atTime(LocalTime.of(15, 0)).atZone(zone).toInstant().toEpochMilli()
                s.start <= b && s.end >= a && s.durationMs >= 3 * 3_600_000L
            }
            day.firstOrNull { c ->
                c.days.size >= MIN_DAYS && (home == null || Geo.distance(c.lat, c.lng, home.lat, home.lng) > 500f)
            }
                ?.let { PlaceSuggestion(PlaceEntity.WORK, it.lat, it.lng, it.days.size) }
                ?.takeIf { it.key !in dismissed }
                ?.let { return it }
        }
        return null
    }

    private class Cluster(var lat: Double, var lng: Double, val days: HashSet<LocalDate>, var n: Int)

    /** 조건에 맞는 머무름을 같은 곳끼리 묶어, 여러 날 나온 곳부터 */
    private fun clusters(
        byDay: Map<LocalDate, List<Segment>>,
        zone: ZoneId,
        pick: (LocalDate, Segment) -> Boolean,
    ): List<Cluster> {
        val out = ArrayList<Cluster>()
        for ((day, segs) in byDay) {
            for (s in segs) {
                if (!s.stay || !pick(day, s)) continue
                val c = out.firstOrNull { Geo.distance(it.lat, it.lng, s.lat, s.lng) <= SAME_PLACE_M }
                if (c == null) out += Cluster(s.lat, s.lng, hashSetOf(day), 1)
                else {
                    // 가운데를 조금씩 맞춰 간다
                    c.lat = (c.lat * c.n + s.lat) / (c.n + 1)
                    c.lng = (c.lng * c.n + s.lng) / (c.n + 1)
                    c.n++
                    c.days += day
                }
            }
        }
        return out.sortedByDescending { it.days.size }
    }
}
