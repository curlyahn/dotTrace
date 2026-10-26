package com.dot.trace.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dot.trace.MainActivity
import com.dot.trace.R
import com.dot.trace.TraceApp
import com.dot.trace.data.PointEntity
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 위치 기록 서비스 — 배터리를 아끼는 방식.
 *
 * - 위치는 OS의 Fused Location(GPS + Wi-Fi + 센서)에서 받는다. GPS만 계속 켜 두는 것보다 전력이 적게 든다.
 * - 움직이는 중: 설정한 주기(기본 1분)로 기록. OS가 위치를 모아서(batching) 전달하므로 앱이 매분 깨어나지 않는다.
 * - 가만히 있음: 위치 업데이트를 완전히 끈다. '정지'는 OS 활동 인식(STILL) 또는
 *   최근 5분간 위치가 25m 안에만 머무른 것으로 판단한다.
 *   다시 움직이면 활동 인식이나 '큰 움직임 감지' 하드웨어 센서(Significant Motion)가 깨워 준다.
 *   (둘 다 없는 휴대폰에서만 15분에 한 번 확인)
 * - 제때 위치가 오지 않으면(터널·엘리베이터): 10초마다 다시 시도하고, 잡히면 원래 주기로 돌아간다.
 * - 정확도가 [MAX_ACCURACY_M]보다 나쁜 위치(기지국 위치 등)는 저장하지 않는다.
 * - CPU를 깨워 두는 WakeLock은 쓰지 않는다. 위치가 올 때만 OS가 앱을 깨운다.
 */
@SuppressLint("MissingPermission") // 서비스 시작 전에 화면에서 위치 권한을 확인한다
class TrackingService : LifecycleService() {

    private enum class Mode { Normal, Still, Retry }

    private val app get() = application as TraceApp
    private lateinit var fused: FusedLocationProviderClient
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    private var started = false
    private var mode = Mode.Normal
    private var intervalMs = 60_000L
    /** 마지막으로 '저장한' 위치를 받은 시각 (elapsed 기준이 아니라 벽시계, 위치의 time 값) */
    private var lastAcceptedAt = 0L
    private var lastSavedTime = 0L
    private var modeSince = 0L
    private var watchdog: Job? = null
    private var transitionsOn = false
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val motionSensor: Sensor? by lazy { sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private var motionArmed = false
    /** 정지 자동 판단용 최근 위치 */
    private val recent = ArrayDeque<Location>()

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            motionArmed = false
            if (mode == Mode.Still) resumeMoving()
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            handleLocations(result.locations)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!isLocationEnabled()) setPhase(Phase.LocationOff)
            else if (Tracker.status.value.phase == Phase.LocationOff) setPhase(phaseFor(mode))
        }
    }

    private val transitionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!ActivityTransitionResult.hasResult(intent)) return
            val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
            if (last.activityType != DetectedActivity.STILL) return
            if (last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                if (mode != Mode.Still) applyMode(Mode.Still)
            } else if (mode == Mode.Still) {
                resumeMoving()
            }
        }
    }

    /** 다시 움직이기 시작: 정상 주기로 돌아가고 출발 지점을 바로 한 번 잡는다 */
    private fun resumeMoving() {
        applyMode(Mode.Normal)
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc -> loc?.let { handleLocations(listOf(it)) } }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch { app.prefs.setTrackingEnabled(false) }
            shutdown()
            return START_NOT_STICKY
        }

        if (!enterForeground()) return START_NOT_STICKY

        if (!started) {
            started = true
            lifecycleScope.launch {
                intervalMs = app.prefs.current().intervalMinutes * 60_000L
                lastAcceptedAt = System.currentTimeMillis()
                applyMode(Mode.Normal)
                startTransitions()
                startWatchdog()
                // 기록 중에 주기 설정을 바꾸면 바로 반영
                app.prefs.settings.map { it.intervalMinutes }.distinctUntilChanged().drop(1).collect { m ->
                    intervalMs = m * 60_000L
                    if (mode == Mode.Normal) applyMode(Mode.Normal)
                }
            }
        }
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

    // ── 위치 요청 ────────────────────────────────────────────

    private fun applyMode(newMode: Mode) {
        mode = newMode
        modeSince = System.currentTimeMillis()
        val request = when (newMode) {
            // 주기마다 정확한 위치. OS가 최대 주기 2배까지 모아서 한 번에 전달 → 앱 깨우는 횟수 절반
            Mode.Normal -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .setMaxUpdateDelayMillis(intervalMs * 2)
                .build()
            // 정지 중: 깨워 줄 센서가 있으면 위치를 아예 받지 않는다. 없을 때만 15분에 한 번
            Mode.Still -> if (canWakeOnMotion()) null else
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, maxOf(STILL_INTERVAL_MS, intervalMs))
                    .setMinUpdateIntervalMillis(maxOf(STILL_INTERVAL_MS, intervalMs))
                    .build()
            // 신호를 잃음: 잡힐 때까지 10초마다, 모으지 않고 바로 전달
            Mode.Retry -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, RETRY_INTERVAL_MS)
                .setMinUpdateIntervalMillis(RETRY_INTERVAL_MS)
                .build()
        }
        fused.removeLocationUpdates(callback)
        if (request != null) runCatching { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
        if (newMode == Mode.Still) {
            armMotionSensor()
        } else {
            disarmMotionSensor()
            recent.clear()
        }
        setPhase(if (isLocationEnabled()) phaseFor(newMode) else Phase.LocationOff)
    }

    private fun phaseFor(m: Mode) = when (m) {
        Mode.Normal -> Phase.Moving
        Mode.Still -> Phase.Still
        Mode.Retry -> Phase.Searching
    }

    private fun handleLocations(locations: List<Location>) {
        val good = locations.filter { it.hasAccuracy() && it.accuracy <= MAX_ACCURACY_M && it.time > lastSavedTime }
        if (good.isEmpty()) {
            checkOverdue()
            return
        }
        // 한 번에 여러 개가 오면(모아서 전달) 주기보다 촘촘한 것은 건너뛴다
        val minGap = (if (mode == Mode.Still) STILL_INTERVAL_MS else intervalMs) - 5_000L
        val toSave = ArrayList<Location>()
        var last = lastSavedTime
        for (l in good.sortedBy { it.time }) {
            if (l.time - last >= minGap || toSave.isEmpty() && mode == Mode.Retry) {
                toSave += l
                last = l.time
            }
        }
        if (toSave.isEmpty()) return
        lastSavedTime = last
        lastAcceptedAt = System.currentTimeMillis()

        lifecycleScope.launch {
            toSave.forEach { l ->
                app.db.points().insert(
                    PointEntity(
                        time = l.time,
                        lat = l.latitude,
                        lng = l.longitude,
                        accuracy = l.accuracy,
                        altitude = if (l.hasAltitude()) l.altitude else null,
                        speed = if (l.hasSpeed()) l.speed else null,
                    )
                )
            }
        }
        val newest = toSave.last()
        updateStatus { it.copy(lastFixAt = newest.time, lastAccuracy = newest.accuracy) }
        when (mode) {
            Mode.Retry -> applyMode(Mode.Normal)
            Mode.Normal -> detectStill(toSave)
            Mode.Still -> {}
        }
    }

    /** 최근 5분 이상 위치가 반경 25m(또는 오차) 안에만 있었다면 정지로 보고 위치 업데이트를 끈다 */
    private fun detectStill(added: List<Location>) {
        if (!canWakeOnMotion()) return // 깨워 줄 수단이 없으면 끄지 않는다
        recent.addAll(added)
        val newest = recent.last()
        while (recent.isNotEmpty() && newest.time - recent.first().time > STILL_WINDOW_MS + intervalMs) recent.removeFirst()
        if (newest.time - recent.first().time < STILL_WINDOW_MS) return
        val radius = maxOf(STILL_RADIUS_M, newest.accuracy)
        if (recent.all { it.distanceTo(newest) <= radius }) applyMode(Mode.Still)
    }

    private fun canWakeOnMotion() = transitionsOn || motionSensor != null

    private fun armMotionSensor() {
        val s = motionSensor ?: return
        if (!motionArmed) motionArmed = sensors.requestTriggerSensor(motionListener, s)
    }

    private fun disarmMotionSensor() {
        val s = motionSensor ?: return
        if (motionArmed) sensors.cancelTriggerSensor(motionListener, s)
        motionArmed = false
    }

    // ── 신호 잃음 감시 ───────────────────────────────────────

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = lifecycleScope.launch {
            while (true) {
                delay(30_000L)
                checkOverdue()
            }
        }
    }

    /** 움직이는 중인데 (주기 × 3 + 여유) 동안 쓸 만한 위치가 없으면 10초 재시도로 바꾼다 */
    private fun checkOverdue() {
        if (mode != Mode.Normal) return
        if (!isLocationEnabled()) {
            setPhase(Phase.LocationOff)
            return
        }
        val waited = System.currentTimeMillis() - maxOf(lastAcceptedAt, modeSince)
        if (waited > intervalMs * 3 + 15_000L) applyMode(Mode.Retry)
    }

    // ── 정지/이동 감지 ───────────────────────────────────────

    private fun startTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return // 권한이 없으면 정지 감지 없이 항상 정상 주기로 기록

        ContextCompat.registerReceiver(this, transitionReceiver, IntentFilter(ACTION_TRANSITION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val request = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder().setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
            )
        )
        runCatching {
            ActivityRecognition.getClient(this).requestActivityTransitionUpdates(request, transitionIntent())
            transitionsOn = true
        }
    }

    private fun transitionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(this, 3, Intent(ACTION_TRANSITION).setPackage(packageName), flags)
    }

    private fun stopTransitions() {
        if (!transitionsOn) return
        transitionsOn = false
        runCatching { ActivityRecognition.getClient(this).removeActivityTransitionUpdates(transitionIntent()) }
        runCatching { unregisterReceiver(transitionReceiver) }
    }

    // ── 상태·알림 ───────────────────────────────────────────

    private fun isLocationEnabled() =
        LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))

    private fun setPhase(phase: Phase) = updateStatus { it.copy(phase = phase) }

    private fun updateStatus(transform: (TrackerStatus) -> TrackerStatus) {
        val before = Tracker.status.value
        Tracker.update(transform)
        val after = Tracker.status.value
        if (started && notificationText(before) != notificationText(after)) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(after))
        }
    }

    private fun notificationText(s: TrackerStatus): String {
        val last = s.lastFixAt?.let { " · 마지막 ${timeFmt.format(Date(it))}" } ?: ""
        return when (s.phase) {
            Phase.Moving -> "기록 중$last"
            Phase.Still -> "머무는 중 · 위치 업데이트 쉬는 중$last"
            Phase.Searching -> "위치 신호 찾는 중 · 10초마다 시도$last"
            Phase.LocationOff -> "휴대폰 위치 설정이 꺼져 있어요"
            Phase.Idle -> "기록 준비 중"
        }
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

    private fun shutdown() {
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        watchdog?.cancel()
        watchdog = null
        fused.removeLocationUpdates(callback)
        disarmMotionSensor()
        stopTransitions()
        started = false
        Tracker.reset()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.dot.trace.STOP"
        private const val ACTION_TRANSITION = "com.dot.trace.ACTIVITY_TRANSITION"
        private const val NOTIFICATION_ID = 1
        private const val RESUME_NOTIFICATION_ID = 2

        /** 신호를 잃었을 때 재시도 간격 */
        const val RETRY_INTERVAL_MS = 10_000L
        /** 정지인데 깨워 줄 센서가 없는 휴대폰에서만 쓰는 확인 간격 */
        const val STILL_INTERVAL_MS = 15 * 60_000L
        /** 이 시간 동안 이 반경 안에만 있으면 정지로 본다 */
        const val STILL_WINDOW_MS = 5 * 60_000L
        const val STILL_RADIUS_M = 25f
        /** 이보다 오차가 큰 위치는 저장하지 않는다 */
        const val MAX_ACCURACY_M = 50f

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
