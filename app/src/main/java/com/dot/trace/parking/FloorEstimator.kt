package com.dot.trace.parking

import com.dot.trace.data.FloorCalibEntity
import com.dot.trace.data.Geo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 주차한 층 추정 + 건물별 학습.
 *
 * 층 번호: 0 = 지상(1층), -1 = B1, -2 = B2 …, 1 = 2층, 2 = 3층 …
 *
 * 순서 (먼저 맞는 것을 쓴다)
 * 1) 와이파이 지문: 같은 건물에서 확인한 주차 때 보였던 와이파이와 많이 겹치면 그 층
 * 2) 학습한 기압: 같은 건물에서 확인한 (기압 차, 층) 값으로 보정
 *    - 두 층 이상 확인했으면 그 건물의 '층당 기압 차'까지 맞춘 직선으로
 *    - 한 층만 확인했으면 그 층을 기준으로 층당 0.36hPa씩
 * 3) 기본 기압: 입구 대비 기압이 0.36hPa(약 3m) 오를 때마다 한 층 아래
 * 4) 기압 센서가 없으면: 하차 직후 GPS가 정확히 잡혔으면 지상, 아니면 모름
 */
object FloorEstimator {

    /** 한 층(약 3m)당 기압 차 (hPa) */
    const val HPA_PER_FLOOR = 0.36f
    /** 같은 건물로 보는 거리 (입구 기준) */
    const val BUILDING_M = 150f
    /** 이보다 작은 기압 차는 같은 층(지상)으로 본다 */
    private const val SAME_LEVEL_HPA = 0.15f
    /** 와이파이가 이만큼 겹쳐야 같은 층으로 본다 (자카드 유사도) */
    private const val WIFI_MATCH = 0.3

    data class Result(val floor: Int, val source: String)

    fun estimate(
        lat: Double,
        lng: Double,
        deltaHpa: Float?,
        wifi: String?,
        gpsGoodAfter: Boolean,
        calibs: List<FloorCalibEntity>,
    ): Result? {
        val local = calibs.filter { Geo.distance(lat, lng, it.lat, it.lng) <= BUILDING_M }

        // 1) 와이파이 지문
        val w = bssids(wifi)
        if (w.isNotEmpty()) {
            val byFloor = local.filter { !it.wifi.isNullOrBlank() }
                .groupBy { it.floor }
                .mapValues { (_, cs) -> cs.maxOf { jaccard(w, bssids(it.wifi)) } }
                .entries.sortedByDescending { it.value }
            val best = byFloor.firstOrNull()
            val second = byFloor.getOrNull(1)?.value ?: 0.0
            if (best != null && best.value >= WIFI_MATCH && best.value - second >= 0.1) return Result(best.key, "wifi")
        }

        if (deltaHpa != null) {
            // 2) 학습한 기압 (층과 기압 차가 맞지 않는 값은 측정이 잘못된 것이라 뺀다)
            val samples = local.filter { plausible(it) }.mapNotNull { c -> c.deltaHpa?.let { it to c.floor } }
            if (samples.isNotEmpty()) {
                val floors = samples.map { it.second }.distinct()
                if (floors.size >= 2) {
                    fit(samples)?.let { (a, b) -> return Result((a + b * deltaHpa).roundToInt(), "learned") }
                }
                // 한 층만 알면: 가장 비슷한 기압 차를 가진 확인 값을 기준으로
                val (d0, f0) = samples.minBy { abs(it.first - deltaHpa) }
                return Result(f0 - ((deltaHpa - d0) / HPA_PER_FLOOR).roundToInt(), "learned")
            }
            // 3) 기본 기압
            if (abs(deltaHpa) < SAME_LEVEL_HPA) return Result(0, "pressure")
            return Result(-(deltaHpa / HPA_PER_FLOOR).roundToInt(), "pressure")
        }

        // 4) 기압 센서 없음
        return if (gpsGoodAfter) Result(0, "gps") else null
    }

    /**
     * 최소제곱 직선: 층 = a + b × 기압차.
     * 기울기가 상식 밖(층당 0.2~0.6hPa 밖)이면 쓰지 않는다.
     */
    private fun fit(samples: List<Pair<Float, Int>>): Pair<Double, Double>? {
        val n = samples.size.toDouble()
        val mx = samples.sumOf { it.first.toDouble() } / n
        val my = samples.sumOf { it.second.toDouble() } / n
        val sxx = samples.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx < 1e-6) return null
        val sxy = samples.sumOf { (it.first - mx) * (it.second - my) }
        val b = sxy / sxx
        val hpaPerFloor = -1.0 / b
        if (b >= 0 || hpaPerFloor !in 0.2..0.6) return null
        return (my - b * mx) to b
    }

    /** 확인한 층과 그때 기압 차가 상식적으로 맞는지 (층당 0.2~0.6hPa, 지상이면 거의 0) */
    fun plausible(c: FloorCalibEntity): Boolean {
        val d = c.deltaHpa ?: return false
        if (c.floor == 0) return abs(d) < 0.25f
        val perFloor = d / -c.floor
        return perFloor in 0.2f..0.6f
    }

    fun bssids(s: String?): Set<String> =
        s?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    /** 층 표시: 지상, B2, 3층 */
    fun label(floor: Int?): String = when {
        floor == null -> "층 모름"
        floor == 0 -> "지상"
        floor < 0 -> "B${-floor}"
        else -> "${floor + 1}층"
    }

    /** 긴 표시: 지하 2층, 지상, 3층 */
    fun longLabel(floor: Int?): String = when {
        floor == null -> "층 모름"
        floor == 0 -> "지상"
        floor < 0 -> "지하 ${-floor}층"
        else -> "${floor + 1}층"
    }

    fun sourceLabel(source: String?): String = when (source) {
        "wifi" -> "와이파이로 추정"
        "learned" -> "이 건물 기록으로 보정"
        "pressure" -> "기압으로 추정"
        "gps" -> "GPS로 추정"
        else -> "추정"
    }
}
