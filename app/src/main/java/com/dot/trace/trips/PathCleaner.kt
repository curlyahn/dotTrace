package com.dot.trace.trips

import com.dot.trace.data.Geo
import com.dot.trace.data.PointEntity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * 튀는 위치 걸러내기.
 *
 * GPS가 건물·고가에 반사되거나 잠깐 와이파이·기지국 위치로 바뀌면, 한 점만 옆으로 수십~100m 밀려 찍힌다.
 * 앞뒤 점은 길 위에 있는데 가운데 점만 옆으로 나갔다 돌아오는 '뾰족한' 모양이면 그 점을 뺀다.
 * 기록(DB)은 그대로 두고, 화면·거리·이동 수단 판단에서만 뺀다.
 */
object PathCleaner {

    /** 이보다 덜 벗어난 건 흔들림으로 보고 둔다 */
    private const val MIN_OFFSET_M = 25.0
    /** 앞뒤 점과 이 시간 안에 있을 때만 판단 (오래 끊긴 사이는 판단하지 않음) */
    private const val MAX_GAP_MS = 5 * 60_000L
    /** 사람·차가 낼 수 없는 속도 (km/h) */
    private const val IMPOSSIBLE_KMH = 250.0

    fun clean(points: List<PointEntity>): List<PointEntity> {
        if (points.size < 3) return points
        var cur = points
        // 튄 점이 연달아 있을 수 있어 몇 번 반복한다
        repeat(3) {
            val out = ArrayList<PointEntity>(cur.size)
            out += cur.first()
            var removed = false
            for (i in 1 until cur.size - 1) {
                val a = out.last()
                val b = cur[i]
                val c = cur[i + 1]
                if (isSpike(a, b, c)) { removed = true; continue }
                out += b
            }
            out += cur.last()
            if (!removed) return out
            cur = out
        }
        return cur
    }

    private fun isSpike(a: PointEntity, b: PointEntity, c: PointEntity): Boolean {
        if (b.time - a.time > MAX_GAP_MS || c.time - b.time > MAX_GAP_MS) return false
        val ab = Geo.distance(a.lat, a.lng, b.lat, b.lng).toDouble()
        val bc = Geo.distance(b.lat, b.lng, c.lat, c.lng).toDouble()
        val ac = Geo.distance(a.lat, a.lng, c.lat, c.lng).toDouble()

        // 1) 불가능한 속도로 갔다가 돌아옴
        val kmhIn = kmh(ab, b.time - a.time)
        val kmhOut = kmh(bc, c.time - b.time)
        if (kmhIn > IMPOSSIBLE_KMH && kmhOut > IMPOSSIBLE_KMH) return true

        val offset = offsetFromLine(b, a, c)
        if (offset < MIN_OFFSET_M) return false
        val accA = a.accuracy ?: 0f
        val accB = b.accuracy ?: 0f
        val accC = c.accuracy ?: 0f

        // 2) 앞뒤는 정확한 GPS인데 가운데만 부정확한 위치(와이파이·기지국)로 옆에 찍힘
        //    → 차로 큰길을 가다 한 점만 개천·건물 쪽으로 튀는 경우
        if (accB >= 15f && accB >= 2f * maxOf(accA, accC, 5f) && offset >= 0.5 * accB) return true

        // 3) 옆으로 나갔다가 되돌아오는 뾰족한 모양
        //    진행 방향이 100도 넘게 꺾이고, 가운데 점을 거치는 길이 앞뒤를 바로 잇는 길보다 훨씬 김
        //    (진짜 길모퉁이는 이 비율이 작다)
        val turn = turnDegrees(a, b, c)
        if (turn < 100) return false
        val detour = (ab + bc) / ac.coerceAtLeast(1.0)
        val worseAcc = accB > maxOf(accA, accC) + 5f
        return detour >= (if (worseAcc) 1.4 else 1.8)
    }

    private fun kmh(m: Double, ms: Long): Double = if (ms <= 0) Double.MAX_VALUE else m / 1000.0 / (ms / 3_600_000.0)

    /** 점 p가 선분 a–c에서 떨어진 거리 (m) */
    private fun offsetFromLine(p: PointEntity, a: PointEntity, c: PointEntity): Double {
        val k = cos(Math.toRadians(p.lat)) * 111_320.0
        val ax = (a.lng - p.lng) * k; val ay = (a.lat - p.lat) * 110_540.0
        val cx = (c.lng - p.lng) * k; val cy = (c.lat - p.lat) * 110_540.0
        val dx = cx - ax; val dy = cy - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx; val y = ay + t * dy
        return Math.sqrt(x * x + y * y)
    }

    /** a→b 방향과 b→c 방향 사이 꺾인 각도 (0 = 직진, 180 = 되돌아감) */
    private fun turnDegrees(a: PointEntity, b: PointEntity, c: PointEntity): Double {
        val k = cos(Math.toRadians(b.lat))
        val h1 = atan2((b.lat - a.lat), (b.lng - a.lng) * k)
        val h2 = atan2((c.lat - b.lat), (c.lng - b.lng) * k)
        var d = Math.toDegrees(abs(h2 - h1))
        if (d > 180) d = 360 - d
        return d
    }
}
