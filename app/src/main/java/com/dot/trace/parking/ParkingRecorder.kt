package com.dot.trace.parking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.dot.trace.TraceApp
import com.dot.trace.data.Geo
import com.dot.trace.data.ParkingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 내 차 승차·하차 기록 (기록 서비스 안에서 동작).
 *
 * - 차와 연결된 동안: 정확한 GPS(오차 25m 이내) 중 가장 최근 것 = 주차장 입구 후보,
 *   기압계 값을 시각과 함께 40분치 기억
 * - 연결이 끊기면(하차): 입구 위치 + 입구 기압 + 지금 기압 + 지금 보이는 와이파이로 주차 기록과 층 추정 → 알림
 * - 하차 후 1분 안에 정확한 GPS가 잡히고 기압 차가 작으면 지상 주차로 보고 위치를 그 자리로 고친다
 * - 다시 연결되면(승차): 열린 주차 기록에 승차 시각을 적는다
 *
 * 기압계는 차와 연결된 동안(+ 하차 후 1분)만 켠다. 전력 소모는 아주 작다.
 */
class ParkingRecorder(
    private val context: Context,
    private val app: TraceApp,
    private val scope: CoroutineScope,
) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    // 화면이 꺼져도 값이 들어오도록 깨우는 방식의 기압 센서를 먼저 쓴다
    private val baro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE, true) ?: sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var listening = false

    /** (벽시계 ms, hPa) */
    private val history = ArrayDeque<Pair<Long, Float>>()

    private var lastCarFix: Location? = null
    private var disconnectAt = 0L
    private var afterFix: Location? = null
    private var connected = false

    fun onConnected() {
        connected = true
        disconnectAt = 0L
        afterFix = null
        startBaro()
        scope.launch {
            val a = app.db.analysis()
            a.openParking()?.let { a.updateParking(it.copy(leftAt = System.currentTimeMillis())) }
            ParkingRepo.cancelNotification(context)
        }
    }

    /**
     * 하차. [flush]는 모아 둔 위치를 바로 받게 하는 요청(입구 위치가 늦게 도착할 수 있어서).
     * [fallback]은 정확한 위치가 하나도 없을 때 쓸 마지막 저장 위치.
     */
    fun onDisconnected(fallback: Location?, flush: () -> Unit) {
        connected = false
        disconnectAt = System.currentTimeMillis()
        afterFix = null
        val at = disconnectAt
        val wifi = wifiFingerprint()
        runCatching(flush)
        scope.launch {
            delay(5_000)
            val entry = lastCarFix ?: fallback ?: return@launch
            // 입구: 입구 GPS 전 90초 동안의 기압 중앙값 (값 하나만 집으면 하차 값과 같은 값이 될 수 있음)
            val entryWin = history.filter { it.first in (entry.time - 90_000L)..(entry.time + 5_000L) }.map { it.second }
            val entryHpa = if (entryWin.size >= 3) median(entryWin) else null
            // 하차: 지금 4초 동안 빠르게 새로 재서 중앙값
            val fresh = freshPressure(4_000L)
            val parkHpa = if (fresh.size >= 3) median(fresh) else null
            var p = ParkingEntity(
                parkedAt = at,
                lat = entry.latitude,
                lng = entry.longitude,
                accuracy = if (entry.hasAccuracy()) entry.accuracy else null,
                entryAt = entry.time,
                entryHpa = entryHpa,
                parkHpa = parkHpa,
                wifi = wifi,
                entrySamples = entryWin.size,
                parkSamples = fresh.size,
            )
            val a = app.db.analysis()
            // 같은 시각 기록이 이미 있으면(연결이 짧게 끊겼다 붙은 경우) 새로 만들지 않는다
            a.openParking()?.takeIf { at - it.parkedAt < 60_000L }?.let { return@launch }
            val est = ParkingRepo.estimate(app.db, p, gpsGoodAfter = false)
            p = p.copy(floorEst = est?.floor, floorSource = est?.source)
            val id = a.insertParking(p)
            p = p.copy(id = id)
            ParkingRepo.notifyParked(context, p)

            // 지상 주차 확인: 하차 직후 정확한 GPS가 잡히면 그 자리를 주차 위치로
            delay(55_000)
            val after = afterFix
            val delta = p.deltaHpa
            if (after != null && Geo.distance(p.lat, p.lng, after.latitude, after.longitude) < 300f &&
                (delta == null || abs(delta) < 0.25f)
            ) {
                val cur = a.parking(id)
                if (cur != null && cur.floor == null) {
                    val moved = cur.copy(lat = after.latitude, lng = after.longitude, accuracy = after.accuracy)
                    val est2 = ParkingRepo.estimate(app.db, moved, gpsGoodAfter = true)
                    val updated = moved.copy(floorEst = est2?.floor ?: 0, floorSource = est2?.source ?: "gps")
                    a.updateParking(updated)
                    ParkingRepo.notifyParked(context, updated)
                }
            }
            if (!connected) stopBaro()
        }
    }

    /** 기록 서비스가 받은 위치를 모두 넘겨받는다 */
    fun onLocations(locations: List<Location>) {
        for (l in locations) {
            if (!l.hasAccuracy()) continue
            if (l.accuracy <= 25f && (connected || (disconnectAt > 0 && l.time <= disconnectAt))) {
                val cur = lastCarFix
                if (cur == null || l.time > cur.time) lastCarFix = l
            }
            if (disconnectAt > 0 && l.time > disconnectAt && l.time - disconnectAt < 90_000L && l.accuracy <= 15f && afterFix == null) {
                afterFix = l
            }
        }
    }

    fun stop() {
        stopBaro()
    }

    // ── 기압계 ──

    private fun startBaro() {
        if (listening || baro == null || sm == null) return
        // 1초마다, 최대 10초 모아서 전달
        listening = sm.registerListener(this, baro, 1_000_000, 10_000_000)
    }

    private fun stopBaro() {
        if (!listening) return
        runCatching { sm?.unregisterListener(this) }
        listening = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000L
        history.addLast(wall to e.values[0])
        val cut = wall - 40 * 60_000L
        while (history.isNotEmpty() && history.first().first < cut) history.removeFirst()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun median(v: List<Float>): Float = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }

    /** 지금 기압을 [ms] 동안 빠르게 읽은 값들 */
    private suspend fun freshPressure(ms: Long): List<Float> {
        val s = baro ?: return emptyList()
        val m = sm ?: return emptyList()
        val values = java.util.Collections.synchronizedList(ArrayList<Float>())
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values += e.values[0] }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (!m.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST)) return emptyList()
        delay(ms)
        runCatching { m.unregisterListener(l) }
        return synchronized(values) { values.toList() }
    }

    /** 그 시각에 가장 가까운 기압 (30초 안에 값이 없으면 null) */
    private fun pressureAt(t: Long): Float? =
        history.minByOrNull { abs(it.first - t) }?.takeIf { abs(it.first - t) <= 30_000L }?.second

    /** 하차 순간 보인 와이파이 (신호 센 10개). 새로 검색하지 않고 OS가 해 둔 결과만 */
    @Suppress("DEPRECATION")
    private fun wifiFingerprint(): String? = runCatching {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val nowUs = SystemClock.elapsedRealtime() * 1000
        wm.scanResults.filter { nowUs - it.timestamp < 180_000_000L }
            .sortedByDescending { it.level }.take(10)
            .joinToString(",") { it.BSSID }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}
