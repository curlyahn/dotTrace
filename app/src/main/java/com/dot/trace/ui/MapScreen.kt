package com.dot.trace.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.trace.R
import com.dot.trace.data.PointEntity
import com.dot.trace.tracking.Phase
import com.dot.trace.tracking.TrackerStatus
import com.dot.trace.tracking.TrackingService
import com.dot.trace.ui.theme.Dot
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val SEOUL = LatLng(37.5665, 126.9780)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: MainViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val date by vm.date.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    // 날짜를 막 바꿨을 때 이전 날짜의 목록이 잠깐 보이지 않도록
    val points = if (day.date == date) day.points else emptyList()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<PointEntity?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    var fittedFor by remember { mutableStateOf<LocalDate?>(null) }

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(SEOUL, 11f)
    }
    val mapStyle = remember { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark) }

    val path = remember(points) { points.map { LatLng(it.lat, it.lng) } }
    // 머물러 있던 구간은 점이 겹치므로, 15m 이상 움직였을 때만 점을 하나씩 찍는다
    val dots = remember(points) { thinOut(points, minDistanceM = 15f) }
    val distanceKm = remember(points) { distanceKm(points) }

    LaunchedEffect(date) { selected = null }

    // 날짜를 바꾸면(또는 그날 첫 기록이 생기면) 그날의 경로가 모두 보이게 카메라를 맞춘다
    LaunchedEffect(mapLoaded, day.date, points.isNotEmpty()) {
        if (!mapLoaded || day.date != date || fittedFor == date) return@LaunchedEffect
        if (path.isEmpty()) {
            latest?.let { cameraState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), 14f)) }
            return@LaunchedEffect
        }
        if (path.distinct().size == 1) {
            cameraState.animate(CameraUpdateFactory.newLatLngZoom(path.first(), 16f))
        } else {
            val bounds = LatLngBounds.builder().apply { path.forEach { include(it) } }.build()
            val pad = with(density) { 48.dp.roundToPx() }
            cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, pad))
        }
        fittedFor = date
    }

    Box(Modifier.fillMaxSize().background(Dot.Ink)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            properties = MapProperties(mapStyleOptions = mapStyle, isBuildingEnabled = false),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false,
                myLocationButtonEnabled = false,
                rotationGesturesEnabled = false,
                tiltGesturesEnabled = false,
            ),
            // 위·아래 떠 있는 카드에 지도 로고와 경로가 가리지 않도록
            contentPadding = PaddingValues(top = 96.dp, bottom = 230.dp),
            onMapLoaded = { mapLoaded = true },
            onMapClick = { selected = null },
        ) {
            val dotIcon = remember { dotDescriptor(Dot.Lime.copy(alpha = 0.85f), Dot.Ink, 9, density.density) }
            val headIcon = remember { dotDescriptor(Dot.Lime, Dot.Ink, 18, density.density) }
            val selectedIcon = remember { dotDescriptor(Color.White, Dot.Lime, 16, density.density) }

            if (path.size >= 2) {
                Polyline(
                    points = path,
                    color = Dot.Lime.copy(alpha = 0.55f),
                    width = with(density) { 3.dp.toPx() },
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                )
            }
            dots.forEach { p ->
                key(p.id) {
                    Marker(
                        state = remember { MarkerState(LatLng(p.lat, p.lng)) },
                        icon = dotIcon,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 1f,
                        onClick = { selected = p; true },
                    )
                }
            }
            points.lastOrNull()?.let { last -> key("head", last.id) {
                Marker(
                    state = remember(last.id) { MarkerState(LatLng(last.lat, last.lng)) },
                    icon = headIcon,
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 2f,
                    onClick = { selected = last; true },
                )
            } }
            selected?.let { s -> key("sel", s.id) {
                Marker(
                    state = remember(s.id) { MarkerState(LatLng(s.lat, s.lng)) },
                    icon = selectedIcon,
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 3f,
                    onClick = { true },
                )
            } }
        }

        // 위: 날짜 선택 + 설정
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DatePill(
                date = date,
                isToday = date == vm.today,
                onPrev = { vm.shiftDate(-1) },
                onNext = { vm.shiftDate(1) },
                onPick = { showPicker = true },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            RoundIconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "설정", tint = Dot.Text)
            }
        }

        // 아래: 상태 카드
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            RoundIconButton(onClick = {
                val target = points.lastOrNull() ?: latest
                target?.let { t ->
                    scope.launch { cameraState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(t.lat, t.lng), 16f)) }
                }
            }) {
                Icon(Icons.Rounded.MyLocation, contentDescription = "마지막 위치로", tint = Dot.Text)
            }
            Spacer(Modifier.height(12.dp))
            StatusCard(
                status = status,
                pointCount = points.size,
                distanceKm = distanceKm,
                selected = selected,
                onClearSelected = { selected = null },
                onStart = onStart,
                onStop = onStop,
            )
        }
    }

    if (showPicker) {
        val todayUtc = remember { vm.today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        vm.selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("보기") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("취소") } },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}

@Composable
private fun DatePill(
    date: LocalDate,
    isToday: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = remember { DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN) }
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(50),
        color = Dot.Surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "전날", tint = Dot.Text)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).clickable(onClick = onPick).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(date.format(fmt), style = MaterialTheme.typography.labelLarge, color = Dot.Text)
                if (isToday) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "오늘",
                        style = MaterialTheme.typography.labelSmall,
                        color = Dot.OnLime,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Dot.Lime).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = onNext, enabled = !isToday) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "다음날",
                    tint = if (isToday) Dot.Faint else Dot.Text,
                )
            }
        }
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = Dot.Surface.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun StatusCard(
    status: TrackerStatus,
    pointCount: Int,
    distanceKm: Double,
    selected: PointEntity?,
    onClearSelected: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val secFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }

    val last = status.lastFixAt?.let { " · 마지막 ${timeFmt.format(Date(it))}" } ?: ""
    val (dotColor, line) = when (status.phase) {
        Phase.Idle -> Dot.Faint to "기록 꺼짐"
        Phase.Moving -> Dot.Lime to "기록 중$last"
        Phase.Still -> Dot.Muted to "머무는 중 · 위치 업데이트 쉬는 중$last"
        Phase.MovingFast -> Dot.Lime to "빠르게 이동 중 · 15초마다 기록$last"
        Phase.Searching -> Dot.Amber to "위치 신호 찾는 중 · ${TrackingService.retryLabel(status.retryIntervalMs)}마다 시도$last"
        Phase.LocationOff -> Dot.Coral to "휴대폰 위치 설정을 켜 주세요"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Dot.Surface.copy(alpha = 0.96f),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(10.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Dot.Text, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))

            if (selected != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dot.Surface2)
                        .border(1.dp, Dot.Line, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(secFmt.format(Date(selected.time)), style = MaterialTheme.typography.titleMedium, color = Dot.Text, fontWeight = FontWeight.SemiBold)
                        Text(
                            buildString {
                                append("%.5f, %.5f".format(Locale.US, selected.lat, selected.lng))
                                selected.accuracy?.let { append(" · ±${it.toInt()}m") }
                                selected.speed?.takeIf { it > 0.5f }?.let { append(" · ${(it * 3.6f).toInt()}km/h") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Dot.Muted,
                        )
                    }
                    IconButton(onClick = onClearSelected) {
                        Icon(Icons.Rounded.Close, contentDescription = "닫기", tint = Dot.Muted)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$pointCount", style = MaterialTheme.typography.displaySmall, color = Dot.Text)
                    Text(" 지점", style = MaterialTheme.typography.titleMedium, color = Dot.Muted, modifier = Modifier.padding(bottom = 6.dp))
                    Spacer(Modifier.width(18.dp))
                    Text("%.1f".format(Locale.US, distanceKm), style = MaterialTheme.typography.displaySmall, color = Dot.Text)
                    Text(" km", style = MaterialTheme.typography.titleMedium, color = Dot.Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            if (status.running) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Dot.Coral),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Dot.Coral),
                ) { Text("기록 정지", style = MaterialTheme.typography.labelLarge) }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Dot.Lime, contentColor = Dot.OnLime),
                ) { Text("기록 시작", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** 가운데가 채워진 원 + 테두리 모양의 지도 점 아이콘 */
private fun dotDescriptor(fill: Color, stroke: Color, sizeDp: Int, density: Float): BitmapDescriptor {
    val px = (sizeDp * density).toInt().coerceAtLeast(4)
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = px / 2f
    val strokeW = (px * 0.18f).coerceAtLeast(1.5f * density)
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke.toArgb() })
    c.drawCircle(r, r, r - strokeW, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() })
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

private fun thinOut(points: List<PointEntity>, minDistanceM: Float): List<PointEntity> {
    val out = ArrayList<PointEntity>()
    val d = FloatArray(1)
    for (p in points) {
        val prev = out.lastOrNull()
        if (prev == null) {
            out += p
            continue
        }
        Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, d)
        if (d[0] >= minDistanceM) out += p
    }
    return out
}

/** 하루 이동 거리. GPS 오차로 제자리에서 흔들린 거리는 빼기 위해, 오차보다 큰 이동만 더한다. */
private fun distanceKm(points: List<PointEntity>): Double {
    var total = 0.0
    var anchor: PointEntity? = null
    val d = FloatArray(1)
    for (p in points) {
        val a = anchor
        if (a == null) {
            anchor = p
            continue
        }
        Location.distanceBetween(a.lat, a.lng, p.lat, p.lng, d)
        val noise = maxOf(a.accuracy ?: 20f, p.accuracy ?: 20f, 15f)
        if (d[0] > noise) {
            total += d[0]
            anchor = p
        }
    }
    return total / 1000.0
}
