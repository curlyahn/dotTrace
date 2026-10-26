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
import com.dot.trace.ui.AboutScreen
import com.dot.trace.ui.MainViewModel
import com.dot.trace.ui.MapScreen
import com.dot.trace.ui.PermissionState
import com.dot.trace.ui.PlaceEditScreen
import com.dot.trace.ui.PlacesScreen
import com.dot.trace.ui.SettingsScreen
import com.dot.trace.ui.StatsScreen
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
        // 정지/이동 감지(배터리 절약). 거부해도 기록은 되지만 큰 움직임 센서만으로 판단한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
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
        // 시스템 바 아이콘은 항상 흰색, 그 뒤에는 어두운 바탕을 깔아 밝은 지도 위에서도 잘 보이게 한다
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x990B0B0C.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xE60B0B0C.toInt()),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            DotTraceTheme {
                // 화면 경로: map, settings, places, place/<종류>/<id>, stats/<기간>, about
                val r = route
                BackHandler(enabled = r != "map") { route = parentOf(r) }

                when {
                    r == "settings" -> SettingsScreen(
                        vm = vm,
                        perms = permissionState(permTick),
                        onRequestBackground = ::requestBackgroundLocation,
                        onRequestBattery = ::requestBatteryExemption,
                        onRequestActivity = ::requestActivityRecognition,
                        onOpenPlaces = { route = "places" },
                        onOpenStats = { route = "stats/-" },
                        onOpenAbout = { route = "about" },
                        onBack = { route = "map" },
                    )
                    r == "places" -> PlacesScreen(
                        vm = vm,
                        onBack = { route = "settings" },
                        onEdit = { kind, id -> route = "place/$kind/${id ?: "-"}" },
                    )
                    r.startsWith("place/") -> {
                        val parts = r.split("/")
                        PlaceEditScreen(
                            vm = vm,
                            kind = parts.getOrElse(1) { "fav" },
                            id = parts.getOrNull(2)?.toLongOrNull(),
                            onBack = { route = "places" },
                        )
                    }
                    r.startsWith("stats/") -> StatsScreen(
                        vm = vm,
                        initialKey = r.removePrefix("stats/").takeIf { it != "-" },
                        onBack = { route = "map" },
                        onShowPlace = { lat, lng ->
                            focusTarget = lat to lng
                            route = "map"
                        },
                    )
                    r == "about" -> AboutScreen(onBack = { route = "settings" })
                    else -> MapScreen(
                        vm = vm,
                        onStart = ::requestStart,
                        onStop = ::stopTracking,
                        onOpenSettings = { route = "settings" },
                        onOpenStats = { key -> route = "stats/${key ?: "-"}" },
                        focusTarget = focusTarget,
                        onFocusConsumed = { focusTarget = null },
                    )
                }
            }
        }
    }

    /** 지금 보이는 화면 */
    private var route by mutableStateOf("map")

    /** 리포트에서 '지도에서 보기'를 눌렀을 때 지도가 이동할 곳 */
    private var focusTarget by mutableStateOf<Pair<Double, Double>?>(null)

    private fun parentOf(r: String) = when {
        r == "places" || r == "about" -> "settings"
        r.startsWith("place/") -> "places"
        else -> "map"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        permTick++
        vm.checkPendingReport()
        vm.checkSuggestions()
        // 기록을 켜 두었는데 서비스가 멈춰 있으면(재부팅, 강제 종료 등) 앱을 열 때 이어서 기록
        lifecycleScope.launch {
            val wanted = (application as TraceApp).prefs.current().trackingEnabled
            if (wanted && !Tracker.status.value.running && hasFineLocation()) startTracking()
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_RESUME_TRACKING -> requestStart()
            // 리포트 알림을 눌렀을 때
            ACTION_OPEN_REPORT -> route = "stats/${intent.getStringExtra(EXTRA_REPORT_KEY) ?: "-"}"
        }
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

    private val activityLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permTick++
        // 이미 기록 중이면 서비스를 다시 띄워 정지 감지를 켠다
        if (ok && Tracker.status.value.running) {
            TrackingService.stop(this)
            startTracking()
        }
    }

    private fun requestActivityRecognition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
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
        activityRecognition = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACTIVITY_RECOGNITION),
    )

    companion object {
        const val ACTION_RESUME_TRACKING = "com.dot.trace.RESUME_TRACKING"
        const val ACTION_OPEN_REPORT = "com.dot.trace.OPEN_REPORT"
        const val EXTRA_REPORT_KEY = "report_key"
    }
}
