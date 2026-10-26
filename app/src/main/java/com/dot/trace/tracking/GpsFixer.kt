package com.dot.trace.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 구글 Play 서비스를 거치지 않고 휴대폰의 GPS 칩에서 직접 위치를 받는다.
 * (Fused Location은 Wi-Fi·기지국 정보를 구글 서버로 보낼 수 있어 쓰지 않는다)
 */
class GpsFixer(context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)

    fun isGpsEnabled(): Boolean = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    /**
     * 최대 [windowMs] 동안 GPS를 켜 두고 위치를 기다린다.
     * 충분히 정확한 위치(≤ [goodAccuracyM])가 오면 바로 돌려주고,
     * 시간 안에 덜 정확한 위치만 왔다면 그중 가장 좋은 것을, 아무것도 없으면 null.
     */
    @SuppressLint("MissingPermission")
    suspend fun tryFix(windowMs: Long, goodAccuracyM: Float = 30f): Location? {
        if (!isGpsEnabled()) return null

        val good = CompletableDeferred<Location>()
        var best: Location? = null
        val startedAt = SystemClock.elapsedRealtimeNanos()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // 이번 시도 이전에 캐시된 위치는 버린다
                if (location.elapsedRealtimeNanos < startedAt) return
                val acc = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
                val bestAcc = best?.takeIf { it.hasAccuracy() }?.accuracy ?: Float.MAX_VALUE
                if (best == null || acc < bestAcc) best = location
                if (acc <= goodAccuracyM) good.complete(location)
            }

            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            withTimeoutOrNull(windowMs) { good.await() } ?: best
        } catch (e: SecurityException) {
            null
        } finally {
            lm.removeUpdates(listener)
        }
    }
}
