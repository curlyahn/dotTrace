package com.dot.trace.tracking

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dot.trace.MainActivity
import com.dot.trace.R
import com.dot.trace.TraceApp
import com.dot.trace.data.PointEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 위치 기록 서비스.
 *
 * 1. 깨어나면 GPS를 켜고 최대 10초 동안 위치를 기다린다.
 * 2. 못 잡으면(터널, 엘리베이터, 지하) 잡힐 때까지 10초 단위로 계속 다시 시도한다.
 * 3. 잡으면 기기 DB에 저장하고, 설정한 주기(기본 1분)만큼 쉰 뒤 1로 돌아간다.
 */
class TrackingService : LifecycleService() {

    private val app get() = application as TraceApp
    private lateinit var gps: GpsFixer
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    override fun onCreate() {
        super.onCreate()
        gps = GpsFixer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch { app.prefs.setTrackingEnabled(false) }
            stopEverything()
            return START_NOT_STICKY
        }

        if (!enterForeground()) return START_NOT_STICKY

        if (loop?.isActive != true) {
            acquireWakeLock()
            loop = lifecycleScope.launch { runLoop() }
        }
        // 시스템이 서비스를 정리해도 다시 살려서 이어서 기록한다
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(Tracker.status.value), type)
        true
    } catch (e: Exception) {
        // 위치 권한이 없거나, 백그라운드에서(재부팅 직후 등) 위치 서비스를 시작할 수 없는 경우
        notifyResumeNeeded(this)
        stopSelf()
        false
    }

    private suspend fun runLoop() {
        Tracker.update { it.copy(phase = Phase.Searching, attempts = 0) }
        while (currentCoroutineContext().isActive) {
            // 1~2. 위치를 잡을 때까지 10초 단위로 시도
            var attempts = 0
            var fix: Location? = null
            while (fix == null) {
                attempts++
                if (!gps.isGpsEnabled()) {
                    setStatus { it.copy(phase = Phase.GpsOff, attempts = attempts) }
                    delay(RETRY_WINDOW_MS)
                    continue
                }
                setStatus { it.copy(phase = Phase.Searching, attempts = attempts) }
                fix = gps.tryFix(RETRY_WINDOW_MS)
            }
            val loc: Location = fix ?: continue

            // 3. 저장
            val now = System.currentTimeMillis()
            app.db.points().insert(
                PointEntity(
                    time = now,
                    lat = loc.latitude,
                    lng = loc.longitude,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                    altitude = if (loc.hasAltitude()) loc.altitude else null,
                    speed = if (loc.hasSpeed()) loc.speed else null,
                )
            )
            val fixedAt = SystemClock.elapsedRealtime()

            // 다음 기록까지 대기. 기다리는 동안 주기 설정을 바꾸면 바로 반영한다.
            while (true) {
                val intervalMs = app.prefs.current().intervalMinutes * 60_000L
                val remaining = fixedAt + intervalMs - SystemClock.elapsedRealtime()
                setStatus { it.copy(phase = Phase.Waiting, attempts = attempts, lastFixAt = now, nextAt = System.currentTimeMillis() + remaining.coerceAtLeast(0)) }
                if (remaining <= 0) break
                delay(remaining.coerceAtMost(15_000L))
            }
        }
    }

    private fun setStatus(transform: (TrackerStatus) -> TrackerStatus) {
        val before = Tracker.status.value
        Tracker.update(transform)
        val after = Tracker.status.value
        // 알림 글자가 실제로 바뀔 때만 갱신 (불필요한 깨움 방지)
        if (notificationText(before) != notificationText(after)) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(after))
        }
    }

    private fun notificationText(s: TrackerStatus): String = when (s.phase) {
        Phase.Searching -> if (s.attempts <= 1) "위치 찾는 중…" else "GPS 신호 기다리는 중 · ${s.attempts}번째 시도"
        Phase.GpsOff -> "휴대폰 위치(GPS)가 꺼져 있어요"
        Phase.Waiting -> "기록 중 · 마지막 ${s.lastFixAt?.let { timeFmt.format(Date(it)) } ?: "-"}"
        Phase.Idle -> "기록 준비 중"
    }

    private fun buildNotification(s: TrackerStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TraceApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DotTrace")
            .setContentText(notificationText(s))
            .setContentIntent(open)
            .addAction(0, "기록 정지", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // 화면이 꺼져 있어도 주기 타이머가 멈추지 않도록 CPU만 깨워 둔다
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DotTrace:tracking")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun stopEverything() {
        loop?.cancel()
        loop = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Tracker.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loop?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        Tracker.reset()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.dot.trace.STOP"
        private const val NOTIFICATION_ID = 1
        private const val RESUME_NOTIFICATION_ID = 2

        /** GPS를 기다리는 한 번의 시도 길이이자 재시도 간격 */
        const val RETRY_WINDOW_MS = 10_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }

        /** 기록을 자동으로 이어가지 못했을 때 "탭해서 다시 시작" 알림을 띄운다 */
        fun notifyResumeNeeded(context: Context) {
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_RESUME_TRACKING)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, TraceApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("위치 기록이 멈춰 있어요")
                .setContentText("탭해서 기록을 다시 시작하세요")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, n)
            }
        }

        fun cancelResumeNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(RESUME_NOTIFICATION_ID)
        }
    }
}
