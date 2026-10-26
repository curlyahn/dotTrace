package com.dot.trace.stats

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dot.trace.MainActivity
import com.dot.trace.R
import com.dot.trace.TraceApp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 하루 한 번(아침 9시 무렵) 깨어나서, 매월 초에는 지난달 리포트, 매년 초에는 지난해 리포트가
 * 준비됐다고 알려 준다. 리포트 자체는 열 때 기기 안에서 계산한다.
 */
class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TraceApp
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // 1일에 휴대폰이 꺼져 있었을 수 있어 3일까지는 늦게라도 알린다
        if (today.dayOfMonth > 3) return Result.success()

        val due = buildList<Period> {
            add(Period.Month(YearMonth.from(today).minusMonths(1)))
            if (today.monthValue == 1) add(Period.Year(today.year - 1))
        }
        val notified = app.prefs.reportNotified()
        for (p in due) {
            if (p.key in notified) continue
            val from = p.start.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = p.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            if (app.db.points().countBetween(from, to) > 0) notify(p)
            app.prefs.addReportNotified(p.key)
        }
        return Result.success()
    }

    private fun notify(p: Period) {
        val ctx = applicationContext
        val open = PendingIntent.getActivity(
            ctx, p.key.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_REPORT)
                .putExtra(MainActivity.EXTRA_REPORT_KEY, p.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (p is Period.Year) "${p.label} 이동 리포트가 준비됐어요" else "${p.label} 이동 리포트가 준비됐어요"
        val n = NotificationCompat.Builder(ctx, TraceApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("걷기·자전거·지하철·버스·자동차로 얼마나 이동했는지, 어디에 머물렀는지 확인해 보세요")
            .setStyle(NotificationCompat.BigTextStyle().bigText("걷기·자전거·지하철·버스·자동차로 얼마나 이동했는지, 어디에 머물렀는지 확인해 보세요"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(p.key.hashCode(), n) }
    }

    companion object {
        private const val NAME = "monthly-report"

        /** 매일 아침 9시 무렵 한 번 확인하도록 예약 (이미 있으면 그대로 둔다) */
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(9, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes()
            val req = PeriodicWorkRequestBuilder<ReportWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
