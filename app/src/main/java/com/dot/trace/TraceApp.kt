package com.dot.trace

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dot.trace.data.AppDatabase
import com.dot.trace.data.Prefs

class TraceApp : Application() {

    val db: AppDatabase by lazy { AppDatabase.get(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "위치 기록 중", NotificationManager.IMPORTANCE_LOW).apply {
                description = "기록하는 동안 항상 표시되는 알림"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "알림 (리포트·기록 상태)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "월간·연간 리포트, 재부팅 후 기록을 다시 시작해야 할 때 등"
            }
        )
        // 매월·매년 리포트 알림 예약
        com.dot.trace.stats.ReportWorker.schedule(this)
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
    }
}
