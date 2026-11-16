package com.dot.trace.parking

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dot.trace.MainActivity
import com.dot.trace.R
import com.dot.trace.TraceApp
import com.dot.trace.data.AppDatabase
import com.dot.trace.data.FloorCalibEntity
import com.dot.trace.data.ParkingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 주차 기록 저장·층 확인(학습)·알림 */
object ParkingRepo {

    const val NOTIFICATION_ID = 7301
    const val ACTION_CONFIRM = "com.dot.trace.PARKING_CONFIRM"
    const val EXTRA_ID = "parking_id"

    /**
     * 층을 확인·고친다. 그때의 기압 차와 와이파이를 이 건물의 학습 값으로 남겨
     * 다음에 같은 건물에 주차하면 이 값으로 층을 보정한다.
     */
    suspend fun setFloor(db: AppDatabase, id: Long, floor: Int) {
        val a = db.analysis()
        val p = a.parking(id) ?: return
        a.updateParking(p.copy(floor = floor))
        a.deleteFloorCalibFor(id)
        a.insertFloorCalib(
            FloorCalibEntity(lat = p.lat, lng = p.lng, floor = floor, deltaHpa = p.deltaHpa, wifi = p.wifi, parkingId = id)
        )
    }

    /** 이 기록의 층·위치·와이파이로 다시 추정 (학습 값 반영) */
    suspend fun estimate(db: AppDatabase, p: ParkingEntity, gpsGoodAfter: Boolean): FloorEstimator.Result? {
        val places = db.places().all()
        FloorEstimator.splitPlaces = places.filter { it.splitLevels }
        return FloorEstimator.estimate(
            p.lat, p.lng, p.deltaHpa, p.wifi, gpsGoodAfter,
            db.analysis().floorCalibs().filter { it.parkingId != p.id },
            split = FloorEstimator.isSplit(p.lat, p.lng, places),
        )
    }

    /** 나올 때 기압 차(주차 자리 − 지상)만으로 층을 다시 계산 (와이파이는 쓰지 않아 독립된 검증) */
    suspend fun estimateFromDelta(db: AppDatabase, p: ParkingEntity, delta: Float): FloorEstimator.Result? {
        val places = db.places().all()
        return FloorEstimator.estimate(
            p.lat, p.lng, delta, null, false,
            db.analysis().floorCalibs().filter { it.parkingId != p.id },
            split = FloorEstimator.isSplit(p.lat, p.lng, places),
        )
    }

    /**
     * 주차 자리에서 와이파이를 새로 검색해 지문을 바꿔 저장한다.
     * (앱이 화면에 떠 있을 때만 새 검색이 허용된다. 실패하면 예전 값을 그대로 둔다)
     */
    @Suppress("DEPRECATION")
    suspend fun refreshWifi(context: Context, db: AppDatabase, id: Long) {
        val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return
        val got = kotlinx.coroutines.CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { got.complete(Unit) }
        }
        val app = context.applicationContext
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                app, receiver, android.content.IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        if (started) kotlinx.coroutines.withTimeoutOrNull(8_000L) { got.await() }
        runCatching { app.unregisterReceiver(receiver) }
        val nowUs = android.os.SystemClock.elapsedRealtime() * 1000
        val fresh = runCatching {
            wm.scanResults.filter { nowUs - it.timestamp < 20_000_000L }.sortedByDescending { it.level }.take(10)
        }.getOrDefault(emptyList())
        if (fresh.isEmpty()) return
        val a = db.analysis()
        val p = a.parking(id) ?: return
        var updated = p.copy(wifi = fresh.joinToString(",") { it.BSSID }, wifiScanned = fresh.size)
        val confirmed = p.floor
        if (confirmed == null) {
            // 아직 확인 전이면 새 지문으로 다시 추정
            estimate(db, updated, gpsGoodAfter = false)?.let { updated = updated.copy(floorEst = it.floor, floorSource = it.source) }
            a.updateParking(updated)
        } else {
            a.updateParking(updated)
            setFloor(db, id, confirmed) // 학습 값의 와이파이도 새 지문으로
        }
    }

    /** 하차 알림: "B2에 주차했어요 · 맞아요 / 고치기" */
    fun notifyParked(context: Context, p: ParkingEntity) {
        val open = PendingIntent.getActivity(
            context, 31,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PARKING)
                .putExtra(EXTRA_ID, p.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val floor = p.shownFloor
        val title = if (floor == null) "주차 위치를 기록했어요" else "${FloorEstimator.longLabelAt(floor, p.lat, p.lng)}에 주차했어요"
        val text = if (floor == null) "눌러서 층·구역을 남겨 주세요"
        else "${FloorEstimator.sourceLabel(p.floorSource)} · 맞으면 '맞아요', 다르면 눌러서 고쳐 주세요"
        val b = NotificationCompat.Builder(context, TraceApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "층·구역·사진", open)
        if (floor != null) {
            val confirm = PendingIntent.getBroadcast(
                context, 32,
                Intent(context, ParkingActionReceiver::class.java).setAction(ACTION_CONFIRM).putExtra(EXTRA_ID, p.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "맞아요", confirm)
        }
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, b.build()) }
    }

    fun cancelNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }
}

/** 알림의 '맞아요': 추정한 층을 확인으로 저장하고 학습 */
class ParkingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ParkingRepo.ACTION_CONFIRM) return
        val id = intent.getLongExtra(ParkingRepo.EXTRA_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext as TraceApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val p = app.db.analysis().parking(id)
                p?.floorEst?.let { ParkingRepo.setFloor(app.db, id, it) }
                ParkingRepo.cancelNotification(context)
            } finally {
                pending.finish()
            }
        }
    }
}
