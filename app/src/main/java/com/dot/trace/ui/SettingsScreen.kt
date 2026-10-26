package com.dot.trace.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.trace.ui.theme.Dot
import com.dot.trace.data.Settings
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PermissionState(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val batteryExempt: Boolean,
    val activityRecognition: Boolean,
)

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    perms: PermissionState,
    onRequestBackground: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestActivity: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val dayLabel = date.format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN))

    var exportAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = runCatching { vm.exportGpx(uri, exportAll) }
            Toast.makeText(
                context,
                n.fold({ "${it}개 지점을 저장했어요" }, { "저장하지 못했어요: ${it.message}" }),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Dot.Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로", tint = Dot.Text)
            }
            Text("설정", style = MaterialTheme.typography.headlineSmall, color = Dot.Text)
        }

        Section("기록 주기")
        Text(
            "움직이는 동안 위치를 몇 분마다 저장할지 고르세요. 빠르게 이동하면(시속 30km 이상) 15초마다 기록하고, 가만히 있으면 위치 업데이트를 꺼요. 신호가 없으면 10초마다 다시 시도하다가 오래 이어지면 최대 15분 간격까지 늘려요.",
            style = MaterialTheme.typography.bodySmall,
            color = Dot.Muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Settings.INTERVAL_CHOICES.forEach { m ->
                val on = settings.intervalMinutes == m
                Text(
                    "${m}분",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Dot.OnLime else Dot.Text,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Dot.Lime else Dot.Surface2)
                        .clickable { vm.setInterval(m) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        Section("안정적으로 기록하기")
        StatusRow(
            title = "신체 활동 감지",
            body = "가만히 있을 때를 알아내 위치 업데이트를 꺼요. 배터리를 가장 많이 아껴 줘요.",
            ok = perms.activityRecognition,
            onClick = onRequestActivity,
        )
        StatusRow(
            title = "위치 '항상 허용'",
            body = "휴대폰을 다시 켜도 앱을 열지 않고 기록을 이어가요.",
            ok = perms.backgroundLocation,
            onClick = onRequestBackground,
        )
        StatusRow(
            title = "배터리 최적화 제외",
            body = "화면이 꺼져 있을 때 기록이 멈추지 않게 해요. 삼성 휴대폰은 '절전 예외 앱'에도 추가해 주세요.",
            ok = perms.batteryExempt,
            onClick = onRequestBattery,
        )

        Section("내 기록")
        Text(
            "모든 기록은 이 휴대폰 안에만 저장돼요. 지금까지 ${total}개 지점.",
            style = MaterialTheme.typography.bodySmall,
            color = Dot.Muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(4.dp))
        ActionRow("$dayLabel 기록을 GPX로 저장") {
            exportAll = false
            exporter.launch("DotTrace-$date.gpx")
        }
        ActionRow("전체 기록을 GPX로 저장") {
            exportAll = true
            exporter.launch("DotTrace-all.gpx")
        }
        ActionRow("$dayLabel 기록 삭제", color = Dot.Coral) { confirmDelete = true }

        Spacer(Modifier.height(32.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("$dayLabel 기록을 지울까요?") },
            text = { Text("지운 기록은 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val n = vm.deleteSelectedDay()
                        Toast.makeText(context, "${n}개 지점을 지웠어요", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("삭제", color = Dot.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
            containerColor = Dot.Surface,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Dot.Faint,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp),
    )
}

@Composable
private fun StatusRow(title: String, body: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !ok, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Dot.Text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(if (ok) Dot.Surface2 else Dot.Lime)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ok) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Dot.Lime))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (ok) "완료" else "설정",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) Dot.Muted else Dot.OnLime,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, color: Color = Dot.Text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Dot.Faint)
    }
}
