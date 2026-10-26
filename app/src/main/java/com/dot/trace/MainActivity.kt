package com.dot.trace

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dot.trace.tracking.TrackingService
import com.dot.trace.tracking.Tracker
import com.dot.trace.ui.MainViewModel
import com.dot.trace.ui.MapScreen
import com.dot.trace.ui.PermissionState
import com.dot.trace.ui.SettingsScreen
import com.dot.trace.ui.theme.DotTraceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /** 권한 화면에서 돌아올 때마다 올려서 화면이 권한 상태를 다시 읽게 한다 */
    private var permTick by mutableIntStateOf(0)

    private val foregroundPerms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permTick++
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startTracking()
        } else {
            Toast.makeText(this, "정확한 위치 권한이 있어야 기록할 수 있어요", Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            DotTraceTheme {
                var screen by rememberSaveable { mutableStateOf("map") }
                BackHandler(enabled = screen != "map") { screen = "map" }

                when (screen) {
                    "settings" -> SettingsScreen(
                        vm = vm,
                        perms = permissionState(permTick),
                        onRequestBackground = ::requestBackgroundLocation,
                        onRequestBattery = ::requestBatteryExemption,
                        onBack = { screen = "map" },
                    )
                    else -> MapScreen(
                        vm = vm,
                        onStart = ::requestStart,
                        onStop = ::stopTracking,
                        onOpenSettings = { screen = "settings" },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        permTick++
        // 기록을 켜 두었는데 서비스가 멈춰 있으면(재부팅, 강제 종료 등) 앱을 열 때 이어서 기록
        lifecycleScope.launch {
            val wanted = (application as TraceApp).prefs.current().trackingEnabled
            if (wanted && !Tracker.status.value.running && hasFineLocation()) startTracking()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_RESUME_TRACKING) requestStart()
    }

    private fun requestStart() {
        if (hasFineLocation()) startTracking() else locationLauncher.launch(foregroundPerms)
    }

    private fun startTracking() {
        lifecycleScope.launch {
            vm.setTrackingEnabled(true)
            TrackingService.cancelResumeNotification(this@MainActivity)
            runCatching { TrackingService.start(this@MainActivity) }
                .onFailure { Toast.makeText(this@MainActivity, "기록을 시작하지 못했어요: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun stopTracking() {
        lifecycleScope.launch { vm.setTrackingEnabled(false) }
        TrackingService.stop(this)
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!hasFineLocation()) {
            locationLauncher.launch(foregroundPerms)
            return
        }
        // Android 11 이상은 설정 화면이 열리고, 거기서 '항상 허용'을 고르면 된다
        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    @Suppress("UNUSED_PARAMETER")
    private fun permissionState(tick: Int) = PermissionState(
        fineLocation = hasFineLocation(),
        backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
    )

    companion object {
        const val ACTION_RESUME_TRACKING = "com.dot.trace.RESUME_TRACKING"
    }
}
