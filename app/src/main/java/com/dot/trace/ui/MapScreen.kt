package com.dot.trace.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.trace.data.PointEntity
import com.dot.trace.data.rememberAddress
import com.dot.trace.tracking.Phase
import com.dot.trace.tracking.TrackerStatus
import com.dot.trace.tracking.TrackingService
import com.dot.trace.trips.Segment
import com.dot.trace.trips.TransportMode
import com.dot.trace.ui.theme.Dot
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: MainViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: (String?) -> Unit,
    focusTarget: Pair<Double, Double>?,
    onFocusConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val date by vm.date.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    // 날짜를 막 바꿨을 때 이전 날짜의 목록이 잠깐 보이지 않도록
    val points = if (day.date == date) day.points else emptyList()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val tripsState by vm.trips.collectAsStateWithLifecycle()
    val segments = if (tripsState.date == date) tripsState.segments else emptyList()
    val moves = remember(segments) { segments.filter { !it.stay } }
    val stays = remember(segments) { segments.filter { it.stay } }
    var selectedStay by remember { mutableStateOf<Segment?>(null) }
    var showTimeline by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Segment?>(null) }
    val suggestion by vm.suggestion.collectAsStateWithLifecycle()
    var selectedTrip by remember { mutableStateOf<Segment?>(null) }
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val pendingReport by vm.pendingReport.collectAsStateWithLifecycle()
    // '내 위치' 버튼으로 잡은 현재 위치 (기록과 별개로 지도에만 표시)
    var me by remember { mutableStateOf<android.location.Location?>(null) }
    var locating by remember { mutableStateOf(false) }
    val controller = remember { MapController() }
    var meNote by remember { mutableStateOf<String?>(null) }
    var noteSeq by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    fun note(text: String) { meNote = text; noteSeq++ }

    /**
     * 현재 위치 찾기.
     * 1) OS가 갖고 있던 최근 위치를 바로 보여 주고 (즉시 반응)
     * 2) 새로 정확한 위치를 최대 15초 동안 찾아 바꿔 준다.
     */
    fun locate(moveCamera: Boolean) {
        if (!hasLocationPermission(context)) {
            note("위치 권한이 필요해요")
            (points.lastOrNull() ?: latest)?.let { controller.focus(it) }
            return
        }
        if (locating) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        locating = true
        note("현재 위치 찾는 중…")
        lastKnownLocation(context).addOnSuccessListener { last ->
            if (last != null && locating) {
                me = last
                if (moveCamera) controller.focusAt(last.latitude, last.longitude)
            }
        }
        freshLocation(context)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    me = loc
                    if (moveCamera) controller.focusAt(loc.latitude, loc.longitude)
                    note("오차 약 ${loc.accuracy.toInt()}m")
                } else if (me != null) {
                    note("최근 위치를 표시했어요")
                } else {
                    note("위치를 못 찾았어요")
                    (points.lastOrNull() ?: latest)?.let { controller.focus(it) }
                }
            }
            .addOnFailureListener { note(if (me != null) "최근 위치를 표시했어요" else "위치를 못 찾았어요") }
            .addOnCompleteListener { locating = false }
    }

    // 안내 문구는 3초 뒤 사라진다
    LaunchedEffect(noteSeq) {
        if (meNote == null || locating) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        if (!locating) meNote = null
    }
    LaunchedEffect(locating) {
        if (!locating && meNote != null) { kotlinx.coroutines.delay(3_000); if (!locating) meNote = null }
    }

    var selected by remember { mutableStateOf<PointEntity?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showPoints by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    var fittedFor by remember { mutableStateOf<LocalDate?>(null) }

    with(density) {
        controller.paddingTop = (WindowInsets.statusBars.getTop(this) + 84.dp.roundToPx())
        controller.paddingBottom = (WindowInsets.navigationBars.getBottom(this) + (if (moves.isEmpty()) 280.dp else 350.dp).roundToPx())
        controller.paddingSide = 40.dp.roundToPx()
    }

    // 머물러 있던 구간은 점이 겹치므로, 15m 이상 움직였을 때만 점을 하나씩 찍는다
    // 머문 곳 안의 점(GPS 흔들림)은 숨기고 원 하나로 보여 준다 → 이동 중 점만 찍는다
    val dots = remember(points, stays) {
        val inStay = stays.flatMap { st -> st.points.map { it.id } }.toHashSet()
        thinOut(points.filter { it.id !in inStay }, minDistanceM = 15f)
    }
    val distanceKm = remember(points) { distanceKm(points) }

    LaunchedEffect(date) { selected = null; selectedTrip = null; selectedStay = null }

    // 각 위치가 어느 이동 수단 구간에 속하는지 (점 색칠용)
    val modeOfPoint = remember(moves) {
        HashMap<Long, TransportMode>().apply { moves.forEach { m -> m.points.forEach { put(it.id, m.mode) } } }
    }

    fun fit(pts: List<PointEntity>) = controller.fit(pts)

    // 리포트에서 '지도에서 보기'로 넘어왔을 때
    LaunchedEffect(focusTarget, mapLoaded) {
        val t = focusTarget ?: return@LaunchedEffect
        if (!mapLoaded) return@LaunchedEffect
        kotlinx.coroutines.delay(400) // 날짜 맞춤 이동이 끝난 뒤에
        controller.focusAt(t.first, t.second)
        onFocusConsumed()
    }

    // 날짜를 바꾸면(또는 그날 첫 기록이 생기면) 그날의 경로가 모두 보이게 카메라를 맞춘다
    // 앱을 열면 현재 위치를 한 번 찾아 파란 점으로 보여 준다 (오늘 기록이 없으면 그쪽으로 이동)
    LaunchedEffect(mapLoaded) {
        if (mapLoaded && me == null) locate(moveCamera = points.isEmpty() && date == vm.today)
    }

    LaunchedEffect(mapLoaded, day.date, points.isNotEmpty()) {
        if (!mapLoaded || day.date != date || fittedFor == date) return@LaunchedEffect
        if (points.isEmpty()) {
            latest?.let { controller.focus(it) }
            return@LaunchedEffect
        }
        controller.fit(points)
        fittedFor = date
    }

    Box(Modifier.fillMaxSize().background(Dot.Ink)) {
        TraceMap(
            controller = controller,
            points = points,
            dots = dots,
            moves = moves,
            modeOfPoint = modeOfPoint,
            selectedTrip = selectedTrip,
            selected = selected,
            onPointClick = { p ->
                selected = p
                selectedStay = null
                if (p == null) selectedTrip = null
            },
            stays = stays,
            onStayClick = { st -> selectedStay = st; selected = null; selectedTrip = null },
            styleName = settings.mapStyle,
            places = places,
            me = me,
            onReady = { mapLoaded = true },
            modifier = Modifier.fillMaxSize(),
        )

        // 시스템 바 뒤 어두운 바탕: 지도 색과 관계없이 시계·배터리 아이콘과 하단 내비게이션 버튼이 잘 보이게
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Dot.Ink.copy(alpha = 0.6f)),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(Dot.Ink.copy(alpha = 0.92f)),
        )

        // 위: 날짜 선택 + 리포트 + 설정, 그리고 리포트 준비 안내
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
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
            RoundIconButton(onClick = { onOpenStats(null) }) {
                Icon(Icons.Rounded.BarChart, contentDescription = "이동 리포트", tint = Dot.Text)
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "설정", tint = Dot.Text)
            }
        }
        suggestion?.let { sg ->
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Dot.Surface.copy(alpha = 0.97f), shadowElevation = 6.dp) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(
                        if (sg.kind == com.dot.trace.data.PlaceEntity.HOME) "최근 ${sg.days}일 밤을 보낸 곳이 있어요. '집'으로 등록할까요?"
                        else "최근 평일 ${sg.days}일 낮에 머문 곳이 있어요. '회사'로 등록할까요?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dot.Text,
                    )
                    rememberAddress(sg.lat, sg.lng)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dot.Muted, maxLines = 1) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { controller.focusAt(sg.lat, sg.lng) }) { Text("지도에서 보기", color = Dot.Muted) }
                        androidx.compose.material3.TextButton(onClick = { vm.dismissSuggestion() }) { Text("아니요", color = Dot.Muted) }
                        androidx.compose.material3.TextButton(onClick = { vm.acceptSuggestion() }) { Text("등록", color = Dot.Lime) }
                    }
                }
            }
        }
        pendingReport?.let { p ->
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = { vm.dismissPendingReport(); onOpenStats(p.key) },
                shape = RoundedCornerShape(18.dp),
                color = Dot.Lime,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BarChart, null, tint = Dot.OnLime, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${p.label} 이동 리포트가 준비됐어요", style = MaterialTheme.typography.labelLarge, color = Dot.OnLime, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.dismissPendingReport() }) {
                        Icon(Icons.Rounded.Close, "닫기", tint = Dot.OnLime, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        }

        // 아래: 상태 카드
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 위치를 잡으면 버튼 옆에 정확도를 잠깐 보여 준다
                meNote?.let { note ->
                    Surface(shape = RoundedCornerShape(50), color = Dot.Surface.copy(alpha = 0.94f), shadowElevation = 6.dp) {
                        Text(
                            note,
                            style = MaterialTheme.typography.labelLarge,
                            color = Dot.Text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                RoundIconButton(onClick = { locate(moveCamera = true) }) {
                    if (locating) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Dot.Lime, strokeWidth = 2.5.dp)
                    } else {
                        Icon(Icons.Rounded.MyLocation, contentDescription = "현재 위치", tint = if (me != null) Dot.Lime else Dot.Text)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            StatusCard(
                status = status,
                pointCount = points.size,
                distanceKm = distanceKm,
                selected = selected,
                onClearSelected = { selected = null },
                trips = moves,
                selectedTrip = selectedTrip,
                onSelectTrip = { t ->
                    selected = null
                    selectedTrip = if (selectedTrip == t) null else t
                    fit(if (selectedTrip == null) points else t.points)
                },
                onShowPoints = { if (points.isNotEmpty()) showPoints = true },
                onShowTimeline = { showTimeline = true },
                selectedStay = selectedStay,
                onClearStay = { selectedStay = null },
                onEditStay = { editing = it },
                onStart = onStart,
                onStop = onStop,
            )
        }
    }

    if (showTimeline) {
        TimelineSheet(
            segments = segments,
            onDismiss = { showTimeline = false },
            onPick = { seg ->
                showTimeline = false
                selected = null
                if (seg.stay) {
                    selectedTrip = null
                    selectedStay = seg
                    controller.focusAt(seg.lat, seg.lng)
                } else {
                    selectedStay = null
                    selectedTrip = seg
                    fit(seg.points)
                }
            },
            onEdit = { editing = it },
        )
    }

    editing?.let { seg ->
        if (seg.stay) {
            StayEditDialog(seg, onDismiss = { editing = null }) { name, rememberIt ->
                vm.renameStay(seg, name, rememberIt)
                editing = null
                selectedStay = null
            }
        } else {
            MoveEditDialog(seg, onDismiss = { editing = null }) { mode, learn ->
                vm.changeMode(seg, mode, learn)
                editing = null
                selectedTrip = null
            }
        }
    }

    if (showPoints) {
        PointsSheet(
            points = points,
            modeOfPoint = modeOfPoint,
            places = places,
            onDismiss = { showPoints = false },
            onPick = { p ->
                showPoints = false
                selectedTrip = null
                selected = p
                controller.focus(p)
            },
        )
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
    // 오늘이면 요일 대신 '오늘' 표시 → 버튼이 셋이라 좁아진 막대에서도 한 줄에 들어가게
    val todayFmt = remember { DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN) }
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(50),
        color = Dot.Surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, modifier = Modifier.width(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "전날", tint = Dot.Text)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).clickable(onClick = onPick).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    date.format(if (isToday) todayFmt else fmt),
                    style = MaterialTheme.typography.labelLarge,
                    color = Dot.Text,
                    maxLines = 1,
                    softWrap = false,
                )
                if (isToday) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "오늘",
                        style = MaterialTheme.typography.labelSmall,
                        color = Dot.OnLime,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Dot.Lime).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = onNext, enabled = !isToday, modifier = Modifier.width(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "다음날",
                    tint = if (isToday) Dot.Faint else Dot.Text,
                )
            }
        }
    }
}

private fun hasLocationPermission(context: android.content.Context): Boolean =
    listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/** OS가 이미 알고 있는 최근 위치 (바로 응답) */
@android.annotation.SuppressLint("MissingPermission")
private fun lastKnownLocation(context: android.content.Context) =
    com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context).lastLocation

/** 새로 잡는 정확한 위치. 10초 이내 위치가 있으면 그걸 쓰고, 없으면 최대 15초 동안 찾는다. */
@android.annotation.SuppressLint("MissingPermission")
private fun freshLocation(context: android.content.Context) =
    com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
        com.google.android.gms.location.CurrentLocationRequest.Builder()
            .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(10_000)
            .setDurationMillis(15_000)
            .build(),
        null,
    )

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
    trips: List<Segment>,
    selectedTrip: Segment?,
    onSelectTrip: (Segment) -> Unit,
    onShowPoints: () -> Unit,
    onShowTimeline: () -> Unit,
    selectedStay: Segment?,
    onClearStay: () -> Unit,
    onEditStay: (Segment) -> Unit,
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

            if (selectedStay != null && selected == null) {
                // 지도에서 머문 곳 원을 눌렀을 때
                val st = selectedStay
                val address = rememberAddress(st.lat, st.lng)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dot.Surface2)
                        .border(1.dp, Dot.Line, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(st.name ?: address ?: "머문 곳", style = MaterialTheme.typography.titleMedium, color = Dot.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "${timeFmt.format(Date(st.start))}–${timeFmt.format(Date(st.end))} · ${durationText(st.durationMs)}" +
                                (if (st.name != null && address != null) "\n$address" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = Dot.Muted,
                            maxLines = 2,
                        )
                    }
                    IconButton(onClick = { onEditStay(st) }) { Icon(Icons.Rounded.Edit, "이름 고치기", tint = Dot.Muted) }
                    IconButton(onClick = onClearStay) { Icon(Icons.Rounded.Close, contentDescription = "닫기", tint = Dot.Muted) }
                }
            } else if (selected != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dot.Surface2)
                        .border(1.dp, Dot.Line, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(secFmt.format(Date(selected.time)), style = MaterialTheme.typography.titleMedium, color = Dot.Text, fontWeight = FontWeight.SemiBold)
                        rememberAddress(selected.lat, selected.lng)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Dot.Text, maxLines = 2)
                        }
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
                // 거리 | 지점 수 (구분선으로 나눈 두 칸)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%.1f".format(Locale.US, distanceKm), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Dot.Text)
                            Text(" km", style = MaterialTheme.typography.titleSmall, color = Dot.Muted, modifier = Modifier.padding(bottom = 5.dp))
                        }
                        Text("이동 거리", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                    }
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).width(1.dp).fillMaxHeight().background(Dot.Line))
                    // 지점 수를 누르면 어디였는지 목록으로
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = pointCount > 0, onClick = onShowPoints),
                    ) {
                        Text("$pointCount", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Dot.Text)
                        Text(if (pointCount > 0) "기록 지점 ›" else "기록 지점", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                    }
                }
            }
            if (trips.isNotEmpty() && selected == null && selectedStay == null) {
                Spacer(Modifier.height(14.dp))
                TripStrip(trips, selectedTrip, onSelectTrip)
            }
            Spacer(Modifier.height(16.dp))

            // [타임라인] [기록 정지/시작] 반반 버튼
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canTimeline = pointCount > 0
                Button(
                    onClick = onShowTimeline,
                    enabled = canTimeline,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Dot.Surface2, contentColor = Dot.Text,
                        disabledContainerColor = Dot.Surface2, disabledContentColor = Dot.Faint,
                    ),
                ) {
                    Icon(Icons.Rounded.Timeline, null, tint = if (canTimeline) Dot.Lime else Dot.Faint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("타임라인", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
                if (status.running) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Dot.Coral),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Dot.Coral),
                    ) { Text("기록 정지", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false) }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Dot.Lime, contentColor = Dot.OnLime),
                    ) { Text("기록 시작", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** 하루의 이동을 시간순 칩으로: 아이콘 · 수단 · 시각 · 거리. 탭하면 그 구간만 강조 */
@Composable
private fun TripStrip(trips: List<Segment>, selected: Segment?, onSelect: (Segment) -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(trips, key = { it.start }) { t ->
            val on = selected == t
            val c = ModeStyle.color(t.mode)
            Surface(
                onClick = { onSelect(t) },
                shape = RoundedCornerShape(16.dp),
                color = if (on) Dot.Surface2 else Dot.Ink.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (on) c else Dot.Line),
            ) {
                Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(ModeStyle.icon(t.mode), contentDescription = null, tint = c, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "${t.mode.label} · ${"%.1f".format(Locale.US, t.distanceM / 1000)}km",
                            style = MaterialTheme.typography.labelLarge,
                            color = Dot.Text,
                        )
                        Text(
                            "${fmt.format(Date(t.start))}–${fmt.format(Date(t.end))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Dot.Muted,
                        )
                    }
                }
            }
        }
    }
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
