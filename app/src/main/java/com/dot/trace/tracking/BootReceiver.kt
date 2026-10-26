package com.dot.trace.tracking

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dot.trace.TraceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 재부팅하거나 앱이 업데이트된 뒤, 기록을 켜 두었다면 자동으로 이어서 기록한다. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as TraceApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (!app.prefs.current().trackingEnabled) return@launch
                // 화면 밖에서 위치 서비스를 시작하려면 '항상 허용' 위치 권한이 필요하다
                if (canStartInBackground(context)) {
                    runCatching { TrackingService.start(context) }
                        .onFailure { TrackingService.notifyResumeNeeded(context) }
                } else {
                    TrackingService.notifyResumeNeeded(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun canStartInBackground(context: Context): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}
