package com.dot.trace.export

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import com.dot.trace.data.PointDao
import com.dot.trace.data.PointEntity
import com.dot.trace.trips.ActivityCode
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.time.OffsetDateTime
import kotlin.coroutines.coroutineContext

/**
 * 구글 타임라인 기록을 DotTrace로 가져온다. 파일은 휴대폰 안에서만 읽는다.
 *
 * 읽을 수 있는 파일
 * - 휴대폰에서 내보낸 `Timeline.json` (설정 → 위치 → 위치 서비스 → 타임라인 → 타임라인 데이터 내보내기)
 *   - semanticSegments: 머문 곳(visit), 이동(activity: 구글이 판단한 이동 수단), 이동 경로(timelinePath)
 *   - rawSignals: 휴대폰이 남긴 원래 위치들
 * - 아이폰 구글 지도에서 내보낸 타임라인 (맨 바깥이 배열인 형식)
 * - 예전 구글 테이크아웃의 `Records.json` (locations)
 *
 * 파일이 수백 MB일 수 있어 한 번에 읽지 않고 조금씩 읽는다.
 * 이동 수단을 위치에 붙이려고 파일을 두 번 읽는다: 1) 이동 구간 모으기 2) 위치 저장.
 */
class GoogleTimelineImporter(
    private val context: Context,
    private val dao: PointDao,
    private val onProgress: (Progress) -> Unit,
) {
    data class Progress(val read: Int, val added: Int, val skipped: Int, val phase: String)

    data class Result(val added: Int, val skipped: Int, val from: Long?, val to: Long?)

    private data class Activity(val start: Long, val end: Long, val code: Int)

    private val activities = ArrayList<Activity>()
    private val batch = ArrayList<PointEntity>()
    private var read = 0
    private var added = 0
    private var skipped = 0
    private var minTime: Long? = null
    private var maxTime: Long? = null

    suspend fun run(uri: Uri): Result = withContext(Dispatchers.IO) {
        // 1) 이동 구간과 이동 수단
        onProgress(Progress(0, 0, 0, "파일 살펴보는 중"))
        parse(uri, collectActivities = true)
        activities.sortBy { it.start }
        // 2) 위치 저장
        parse(uri, collectActivities = false)
        flush()
        Result(added, skipped, minTime, maxTime)
    }

    private suspend fun parse(uri: Uri, collectActivities: Boolean) {
        val input = context.contentResolver.openInputStream(uri) ?: error("파일을 열 수 없어요")
        JsonReader(InputStreamReader(input.buffered(1 shl 16), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            when (r.peek()) {
                // 아이폰 형식: 구간들의 배열
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) segment(readAny(r), collectActivities)
                    r.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "semanticSegments" -> eachInArray(r) { segment(readAny(r), collectActivities) }
                            "rawSignals" -> if (collectActivities) r.skipValue() else eachInArray(r) { rawSignal(readAny(r)) }
                            "locations" -> if (collectActivities) r.skipValue() else eachInArray(r) { takeoutRecord(readAny(r)) }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                else -> error("구글 타임라인 파일이 아니에요")
            }
        }
    }

    private suspend fun eachInArray(r: JsonReader, each: suspend () -> Unit) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
        r.beginArray()
        while (r.hasNext()) {
            each()
        }
        r.endArray()
    }

    // ── 구간 (Timeline.json의 semanticSegments, 아이폰 형식의 각 항목) ──

    private suspend fun segment(seg: Any?, collectActivities: Boolean) {
        val m = seg as? Map<*, *> ?: return
        val start = time(m["startTime"]) ?: return
        val end = time(m["endTime"]) ?: start

        val activity = m["activity"] as? Map<*, *>
        val visit = m["visit"] as? Map<*, *>

        if (collectActivities) {
            if (activity != null) {
                val type = ((activity["topCandidate"] as? Map<*, *>)?.get("type") as? String)
                    ?: (activity["topCandidate"] as? Map<*, *>)?.get("type")?.toString()
                code(type)?.let { activities += Activity(start, end, it) }
            }
            return
        }

        // 이동 경로
        (m["timelinePath"] as? List<*>)?.forEach { item ->
            val p = item as? Map<*, *> ?: return@forEach
            val ll = latLng(p["point"]) ?: return@forEach
            val t = time(p["time"]) ?: offsetTime(start, p["durationMinutesOffsetFromStartTime"]) ?: return@forEach
            add(t, ll, null, null, null)
        }
        // 이동: 출발·도착 지점
        if (activity != null) {
            latLng(activity["start"])?.let { add(start, it, null, null, null) }
            latLng(activity["end"])?.let { add(end, it, null, null, null) }
        }
        // 머문 곳: 도착·떠난 시각에 같은 자리 두 점 → 머무름으로 잡힌다
        if (visit != null) {
            val loc = (visit["topCandidate"] as? Map<*, *>)?.get("placeLocation")
            latLng(loc)?.let { ll ->
                add(start, ll, null, null, DetectedActivity.STILL)
                if (end > start) add(end, ll, null, null, DetectedActivity.STILL)
            }
        }
    }

    // ── rawSignals: 휴대폰이 남긴 원래 위치 ──

    private suspend fun rawSignal(sig: Any?) {
        val pos = (sig as? Map<*, *>)?.get("position") as? Map<*, *> ?: return
        val ll = latLng(pos["LatLng"] ?: pos["latLng"]) ?: return
        val t = time(pos["timestamp"]) ?: return
        val acc = (pos["accuracyMeters"] as? Number)?.toFloat()
        if (acc != null && acc > 100f) return // 너무 부정확한 위치는 건너뜀
        add(t, ll, acc, (pos["altitudeMeters"] as? Number)?.toDouble(), null, (pos["speedMetersPerSecond"] as? Number)?.toFloat())
    }

    // ── 예전 테이크아웃 Records.json ──

    private suspend fun takeoutRecord(rec: Any?) {
        val m = rec as? Map<*, *> ?: return
        val lat = (m["latitudeE7"] as? Number)?.toDouble()?.div(1e7) ?: return
        val lng = (m["longitudeE7"] as? Number)?.toDouble()?.div(1e7) ?: return
        val t = time(m["timestamp"]) ?: (m["timestampMs"]?.toString()?.toLongOrNull()) ?: return
        val acc = (m["accuracy"] as? Number)?.toFloat()
        if (acc != null && acc > 100f) return
        add(t, lat to lng, acc, (m["altitude"] as? Number)?.toDouble(), null, (m["velocity"] as? Number)?.toFloat())
    }

    // ── 저장 ──

    private suspend fun add(
        time: Long,
        ll: Pair<Double, Double>,
        accuracy: Float?,
        altitude: Double?,
        activity: Int?,
        speed: Float? = null,
    ) {
        read++
        batch += PointEntity(
            time = time,
            lat = ll.first,
            lng = ll.second,
            accuracy = accuracy,
            altitude = altitude,
            speed = speed,
            activity = activity ?: activityAt(time),
            source = PointEntity.SOURCE_GOOGLE,
        )
        if (batch.size >= 2_000) flush()
    }

    /** 이미 있는 기록과 1분 안에 겹치는 위치는 건너뛰고(중복·다시 가져오기 방지) 나머지를 저장 */
    private suspend fun flush() {
        if (batch.isEmpty()) return
        coroutineContext.ensureActive()
        batch.sortBy { it.time }
        val existing = dao.timesBetween(batch.first().time - 60_000, batch.last().time + 60_000)
        val keep = ArrayList<PointEntity>(batch.size)
        var lastKept = Long.MIN_VALUE
        for (p in batch) {
            val near = nearest(existing, p.time)
            if ((near != null && kotlin.math.abs(near - p.time) < 60_000) || p.time - lastKept < 15_000) {
                skipped++
                continue
            }
            keep += p
            lastKept = p.time
        }
        if (keep.isNotEmpty()) {
            dao.insertAll(keep)
            added += keep.size
            minTime = minOf(minTime ?: Long.MAX_VALUE, keep.first().time)
            maxTime = maxOf(maxTime ?: Long.MIN_VALUE, keep.last().time)
        }
        batch.clear()
        onProgress(Progress(read, added, skipped, "가져오는 중"))
    }

    private fun nearest(sorted: List<Long>, t: Long): Long? {
        if (sorted.isEmpty()) return null
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid] < t) lo = mid + 1 else hi = mid
        }
        val a = sorted[lo]
        val b = if (lo > 0) sorted[lo - 1] else a
        return if (kotlin.math.abs(a - t) < kotlin.math.abs(b - t)) a else b
    }

    /** 이 시각이 속한 이동 구간의 이동 수단 */
    private fun activityAt(t: Long): Int? {
        if (activities.isEmpty()) return null
        var lo = 0
        var hi = activities.size - 1
        var found: Activity? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val a = activities[mid]
            if (a.start <= t) { found = a; lo = mid + 1 } else hi = mid - 1
        }
        return found?.takeIf { t <= it.end }?.code
    }

    // ── 값 읽기 도우미 ──

    /** 구글 이동 수단 이름 → DotTrace 활동 코드 */
    private fun code(type: String?): Int? = when (type?.uppercase()) {
        null -> null
        "WALKING", "ON_FOOT", "HIKING" -> DetectedActivity.WALKING
        "RUNNING" -> DetectedActivity.RUNNING
        "CYCLING", "ON_BICYCLE" -> DetectedActivity.ON_BICYCLE
        "IN_PASSENGER_VEHICLE", "DRIVING", "IN_TAXI", "MOTORCYCLING", "IN_CAR" -> ActivityCode.CAR
        "IN_BUS" -> ActivityCode.BUS
        "IN_SUBWAY", "IN_TRAM" -> ActivityCode.SUBWAY
        "IN_TRAIN" -> ActivityCode.TRAIN
        "FLYING" -> ActivityCode.FLIGHT
        "IN_VEHICLE" -> DetectedActivity.IN_VEHICLE
        else -> null
    }

    private val numberRegex = Regex("-?\\d+(?:\\.\\d+)?")

    /** "37.5012°, 127.0312°", "geo:37.50,127.03", {latLng: ...}, {latitudeE7, longitudeE7} 모두 처리 */
    private fun latLng(v: Any?): Pair<Double, Double>? = when (v) {
        null -> null
        is String -> numberRegex.findAll(v).map { it.value.toDouble() }.take(2).toList()
            .takeIf { it.size == 2 && it[0] in -90.0..90.0 && it[1] in -180.0..180.0 }
            ?.let { it[0] to it[1] }
        is Map<*, *> -> latLng(v["latLng"] ?: v["LatLng"] ?: v["point"])
            ?: run {
                val lat = (v["latitudeE7"] as? Number)?.toDouble()?.div(1e7)
                val lng = (v["longitudeE7"] as? Number)?.toDouble()?.div(1e7)
                if (lat != null && lng != null) lat to lng else null
            }
        else -> null
    }

    private fun time(v: Any?): Long? = (v as? String)?.let { s ->
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
    }

    private fun offsetTime(start: Long, minutes: Any?): Long? =
        (minutes?.toString()?.toDoubleOrNull())?.let { start + (it * 60_000).toLong() }

    /** JSON 값 하나를 Map/List/String/Number/Boolean 으로 */
    private fun readAny(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = HashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) m[r.nextName()] = readAny(r)
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l += readAny(r)
            r.endArray()
            l
        }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull() }
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }
}
