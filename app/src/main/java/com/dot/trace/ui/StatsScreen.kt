package com.dot.trace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dot.trace.stats.ModeStat
import com.dot.trace.stats.Period
import com.dot.trace.stats.Report
import com.dot.trace.stats.StayPlace
import com.dot.trace.ui.theme.Dot
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** 월간·연간 이동 리포트 */
@Composable
fun StatsScreen(
    vm: MainViewModel,
    initialKey: String?,
    onBack: () -> Unit,
    onShowPlace: (Double, Double) -> Unit,
    onShowDay: (java.time.LocalDate) -> Unit = {},
) {
    var key by rememberSaveable { mutableStateOf(initialKey ?: periodKey(defaultPeriod())) }
    val period = parsePeriod(key)
    val report by produceState<Report?>(initialValue = null, key) {
        value = null
        value = vm.report(period)
        vm.markReportSeen(key)
    }
    val isMonth = period is Period.Month
    val canNext = !period.next().start.isAfter(LocalDate.now())

    Column(
        Modifier.fillMaxSize().background(Dot.Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = Dot.Text) }
            Text("이동 리포트", style = MaterialTheme.typography.headlineSmall, color = Dot.Text)
        }

        // 월간 / 연간
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("월간", isMonth) { if (!isMonth) key = periodKey(Period.Month(YearMonth.of((period as Period.Year).year, if (period.year == LocalDate.now().year) LocalDate.now().monthValue else 12))) }
            Chip("연간", !isMonth) { if (isMonth) key = periodKey(Period.Year((period as Period.Month).ym.year)) }
        }

        // 기간 이동
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { key = periodKey(period.prev()) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "이전", tint = Dot.Text)
            }
            Text(period.label, style = MaterialTheme.typography.titleLarge, color = Dot.Text, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { key = periodKey(period.next()) }, enabled = canNext) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "다음", tint = if (canNext) Dot.Text else Dot.Faint)
            }
        }

        val r = report
        when {
            r == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Dot.Lime)
                    Spacer(Modifier.height(12.dp))
                    Text("기록을 정리하는 중…", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                }
            }
            r.isEmpty -> Text(
                "이 기간에는 기록이 없어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dot.Muted,
                modifier = Modifier.padding(24.dp),
            )
            else -> ReportBody(r, onShowPlace, onShowDay)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ReportBody(r: Report, onShowPlace: (Double, Double) -> Unit, onShowDay: (java.time.LocalDate) -> Unit) {
    // 요약
    Card {
        Text("총 이동 거리", style = MaterialTheme.typography.labelMedium, color = Dot.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(km(r.totalDistanceM), style = MaterialTheme.typography.displaySmall, color = Dot.Text)
            Text(" km", style = MaterialTheme.typography.titleMedium, color = Dot.Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "기록한 날 ${r.recordedDays}일 · 이동 시간 ${duration(r.movingMs)} · 방문 지역 ${r.regions.size}곳",
            style = MaterialTheme.typography.bodySmall,
            color = Dot.Muted,
        )
    }

    // 출퇴근 (A안)
    CommuteCard(r, onShowDay)

    // 이동 수단별
    if (r.modes.isNotEmpty()) {
        Title("이동 수단")
        Card {
            val max = r.modes.maxOf { it.distanceM }.coerceAtLeast(1.0)
            r.modes.forEachIndexed { i, m ->
                if (i > 0) Spacer(Modifier.height(14.dp))
                ModeRow(m, (m.distanceM / max).toFloat())
            }
        }
    }

    // 가장 오래 머문 곳
    if (r.topPlaces.isNotEmpty()) {
        Title("가장 오래 머문 곳")
        Card(padding = 0) {
            r.topPlaces.take(5).forEachIndexed { i, p ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(Dot.Line))
                PlaceStatRow(i + 1, p) { onShowPlace(p.lat, p.lng) }
            }
        }
        if (com.dot.trace.places.GooglePlaces.available) {
            Text(
                "장소 이름 일부: Google Maps",
                style = MaterialTheme.typography.labelSmall,
                color = Dot.Faint,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }

    // 방문한 지역
    if (r.regions.isNotEmpty()) {
        Title("방문한 지역 ${r.regions.size}곳")
        Card {
            val max = r.regions.maxOf { it.stayMs }.coerceAtLeast(1)
            r.regions.take(10).forEachIndexed { i, g ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, color = Dot.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${duration(g.stayMs)} · ${g.days}일", style = MaterialTheme.typography.labelSmall, color = Dot.Muted)
                }
                Spacer(Modifier.height(6.dp))
                Bar((g.stayMs.toFloat() / max), Dot.Lime)
            }
            if (r.regions.size > 10) {
                Spacer(Modifier.height(10.dp))
                Text("그 밖에 ${r.regions.size - 10}곳", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
            }
        }
    }
}

@Composable
private fun ModeRow(m: ModeStat, ratio: Float) {
    val c = ModeStyle.color(m.mode)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(ModeStyle.icon(m.mode), null, tint = c, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(m.mode.label, style = MaterialTheme.typography.titleMedium, color = Dot.Text, modifier = Modifier.weight(1f))
                Text("${km(m.distanceM)} km", style = MaterialTheme.typography.titleMedium, color = Dot.Text, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Bar(ratio, c)
            Spacer(Modifier.height(4.dp))
            Text("${duration(m.durationMs)} · ${m.trips}번", style = MaterialTheme.typography.labelSmall, color = Dot.Muted)
        }
    }
}

@Composable
private fun PlaceStatRow(rank: Int, p: StayPlace, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (rank == 1) Dot.Lime else Dot.Surface2), contentAlignment = Alignment.Center) {
            Text("$rank", style = MaterialTheme.typography.labelLarge, color = if (rank == 1) Dot.OnLime else Dot.Text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.placeName ?: p.address ?: p.region ?: "%.4f, %.4f".format(Locale.US, p.lat, p.lng),
                style = MaterialTheme.typography.titleMedium,
                color = Dot.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(if (p.placeName != null) p.address else null, "${duration(p.stayMs)} · ${p.days}일 방문")
                    .joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = Dot.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.Place, "지도에서 보기", tint = Dot.Faint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) Dot.OnLime else Dot.Text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Dot.Lime else Dot.Surface2)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Dot.Faint,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun Card(padding: Int = 20, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(Dot.Surface)
            .padding(padding.dp),
    ) { content() }
}

@Composable
private fun Bar(ratio: Float, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Dot.Surface2)) {
        Box(Modifier.fillMaxWidth(ratio.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

private fun km(m: Double) = if (m >= 100_000) "%,.0f".format(Locale.US, m / 1000) else "%.1f".format(Locale.US, m / 1000)

private fun duration(ms: Long): String {
    val min = ms / 60_000
    val h = min / 60
    return when {
        h >= 1 && min % 60 == 0L -> "${h}시간"
        h >= 1 -> "${h}시간 ${min % 60}분"
        else -> "${min}분"
    }
}

/** 기본 기간: 이번 달 초(1~7일)에는 지난달, 그 밖에는 이번 달 */
fun defaultPeriod(): Period {
    val now = YearMonth.now()
    return Period.Month(if (LocalDate.now().dayOfMonth <= 7) now.minusMonths(1) else now)
}

fun periodKey(p: Period) = p.key

fun parsePeriod(key: String): Period = runCatching {
    when (key.first()) {
        'Y' -> Period.Year(key.drop(1).toInt())
        else -> Period.Month(YearMonth.parse(key.drop(1)))
    }
}.getOrElse { defaultPeriod() }

/** 출퇴근 카드: 횟수 · 수단 비율 띠 · 수단별 횟수/비율/평균 시간. [전체·출근·퇴근] */
@Composable
private fun CommuteCard(r: Report, onShowDay: (java.time.LocalDate) -> Unit) {
    var showCal by remember { mutableStateOf(false) }
    if (showCal) CommuteCalendarSheet(r, onDismiss = { showCal = false }, onShowDay = { showCal = false; onShowDay(it) })
    if (!r.hasHomeWork) {
        Spacer(Modifier.height(16.dp))
        Text(
            "내 장소에 집과 회사를 등록하면 출퇴근 통계를 보여 줘요.",
            style = MaterialTheme.typography.bodySmall,
            color = Dot.Muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }
    if (r.commutes.isEmpty()) return
    var filter by remember { mutableStateOf(0) } // 0 전체, 1 출근, 2 퇴근
    val list = when (filter) {
        1 -> r.commutes.filter { it.toWork }
        2 -> r.commutes.filter { !it.toWork }
        else -> r.commutes
    }
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Dot.Surface)
            .border(1.dp, Dot.Lime.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("출퇴근", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Dot.Text)
            Spacer(Modifier.width(8.dp))
            // 출퇴근 달력 (날짜별로 탄 수단 확인)
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Dot.Lime.copy(alpha = 0.12f))
                    .border(1.dp, Dot.Lime.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { showCal = true },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Rounded.CalendarMonth, "출퇴근 달력",
                    tint = Dot.Lime, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            listOf("전체", "출근", "퇴근").forEachIndexed { i, t ->
                val on = i == filter
                Text(
                    t,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Dot.OnLime else Dot.Muted,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50))
                        .background(if (on) Dot.Lime else Dot.Surface2)
                        .clickable { filter = i }.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        if (list.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("이 기간에는 기록이 없어요.", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
        } else {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${list.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Dot.Text)
            Text(
                when (filter) { 1 -> " 회 · 집 → 회사"; 2 -> " 회 · 회사 → 집"; else -> " 회 · 집 ↔ 회사" },
                style = MaterialTheme.typography.bodySmall, color = Dot.Muted, modifier = Modifier.padding(bottom = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "평균 ${duration(list.sumOf { it.durationMs } / list.size)} · 편도 ${km(list.sumOf { it.distanceM } / list.size)}km",
                style = MaterialTheme.typography.labelSmall, color = Dot.Muted, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        // 수단별 (많이 쓴 순)
        val groups = list.groupBy { it.mode }.entries.sortedByDescending { it.value.size }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50))) {
            groups.forEach { (m, l) ->
                Box(Modifier.weight(l.size.toFloat()).fillMaxHeight().background(ModeStyle.color(m)))
            }
        }
        groups.firstOrNull()?.let { (m, _) ->
            Spacer(Modifier.height(6.dp))
            Text("주로 ${m.label}${if (m.label.last().let { val j = (it.code - 0xAC00) % 28; j == 0 || j == 8 }) "로" else "으로"} 출퇴근했어요",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Dot.Lime)
        }
        Spacer(Modifier.height(6.dp))
        groups.forEach { (m, l) ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Dot.Line))
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(ModeStyle.color(m)))
                Spacer(Modifier.width(10.dp))
                Text(m.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Dot.Text, modifier = Modifier.weight(1f))
                Text("평균 ${duration(l.sumOf { it.durationMs } / l.size)}", style = MaterialTheme.typography.labelSmall, color = Dot.Muted, maxLines = 1)
                Text("${l.size}회", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Dot.Text,
                    modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("${l.size * 100 / list.size}%", style = MaterialTheme.typography.labelMedium, color = ModeStyle.color(m),
                    modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        }
        Text(
            "한 번의 출퇴근 수단 = 그중 가장 길게 이동한 수단 (예: 지하철 17km + 자전거 1km → 지하철). " +
                "중간에 들른 곳(탁구 등)이 있어도 집↔회사면 출퇴근으로 세고, 시간은 이동한 시간만 더해요." +
                (if (list.any { it.stops > 0 }) " 들렀다 간 출퇴근 ${list.count { it.stops > 0 }}회." else ""),
            style = MaterialTheme.typography.labelSmall,
            color = Dot.Faint,
        )
    }
}

/**
 * 출퇴근 달력: 날짜마다 출근(왼쪽)·퇴근(오른쪽) 막대를 그날 탄 수단 색으로.
 * 날짜를 누르면 그날 내역, [지도]로 그날 지도.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CommuteCalendarSheet(r: Report, onDismiss: () -> Unit, onShowDay: (java.time.LocalDate) -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    fun dayOf(c: com.dot.trace.stats.Commute) = java.time.Instant.ofEpochMilli(c.start).atZone(zone).toLocalDate()
    val byDay = remember(r) { r.commutes.groupBy { dayOf(it) } }
    val firstMonth = java.time.YearMonth.from(r.period.start)
    val lastMonth = java.time.YearMonth.from(r.period.endExclusive.minusDays(1)).let { m ->
        val now = java.time.YearMonth.now(); if (m.isAfter(now)) now else m
    }
    var month by remember(r) {
        mutableStateOf(byDay.keys.maxOrNull()?.let { java.time.YearMonth.from(it) } ?: lastMonth)
    }
    var picked by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", Locale.KOREA) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dot.Surface,
        contentColor = Dot.Text,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.CalendarMonth, null, tint = Dot.Lime)
                Spacer(Modifier.width(8.dp))
                Text("출퇴근 달력", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Dot.Text)
            }
            // 달 넘기기
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val canPrev = month.isAfter(firstMonth)
                val canNext = month.isBefore(lastMonth)
                Text("‹", fontSize = 26.sp, color = if (canPrev) Dot.Text else Dot.Faint,
                    modifier = Modifier.clickable(enabled = canPrev) { month = month.minusMonths(1); picked = null }.padding(horizontal = 12.dp))
                Text("${month.year}년 ${month.monthValue}월", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = Dot.Text, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("›", fontSize = 26.sp, color = if (canNext) Dot.Text else Dot.Faint,
                    modifier = Modifier.clickable(enabled = canNext) { month = month.plusMonths(1); picked = null }.padding(horizontal = 12.dp))
            }
            // 범례: 이 달 수단별 횟수
            val inMonth = r.commutes.filter { java.time.YearMonth.from(dayOf(it)) == month }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                inMonth.groupBy { it.mode }.entries.sortedByDescending { it.value.size }.forEach { (m, l) ->
                    Box(Modifier.padding(top = 5.dp).width(12.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(ModeStyle.color(m)))
                    Text(" ${m.label} ${l.size}회   ", style = MaterialTheme.typography.labelSmall, color = Dot.Muted)
                }
                if (inMonth.isEmpty()) Text("이 달에는 출퇴근 기록이 없어요", style = MaterialTheme.typography.labelSmall, color = Dot.Muted)
            }
            Text(
                "날짜 아래 막대: 왼쪽 = 출근 · 오른쪽 = 퇴근 (그날 탄 수단 색)",
                style = MaterialTheme.typography.labelSmall, color = Dot.Faint,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            // 요일 머리
            Row(Modifier.fillMaxWidth()) {
                "일월화수목금토".forEachIndexed { i, c ->
                    Text("$c", style = MaterialTheme.typography.labelSmall, color = if (i == 0) Dot.Coral else Dot.Muted,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Spacer(Modifier.height(4.dp))
            val today = java.time.LocalDate.now(zone)
            val lead = month.atDay(1).dayOfWeek.value % 7 // 일요일 = 0
            val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until 7) {
                        val d = week.getOrNull(i)
                        Box(Modifier.weight(1f).height(48.dp)) {
                            if (d != null) {
                                val list = byDay[d].orEmpty()
                                val inC = list.lastOrNull { it.toWork }
                                val outC = list.lastOrNull { !it.toWork }
                                val has = list.isNotEmpty()
                                val future = d.isAfter(today)
                                Column(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                        .background(if (has) Dot.Surface2 else Dot.Surface)
                                        .border(
                                            if (picked == d) 2.dp else 1.dp,
                                            if (picked == d) Dot.Lime else Dot.Surface2,
                                            RoundedCornerShape(12.dp),
                                        )
                                        .clickable(enabled = has) { picked = d },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (has) FontWeight.Bold else FontWeight.Normal,
                                        color = if (future) Dot.Faint else Dot.Text)
                                    if (has) {
                                        Row(Modifier.padding(top = 4.dp).width(30.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                            listOf(inC, outC).forEach { c ->
                                                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                                    .background(c?.let { ModeStyle.color(it.mode) } ?: Dot.Line))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 고른 날 내역
            picked?.let { d ->
                val list = byDay[d].orEmpty().sortedBy { it.start }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dot.Surface2).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        d.format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Dot.Text,
                    )
                    list.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (c.toWork) "출근" else "퇴근", style = MaterialTheme.typography.labelMedium, color = Dot.Muted, modifier = Modifier.width(34.dp))
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(ModeStyle.color(c.mode)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${c.mode.label} · ${fmt.format(java.util.Date(c.start))}–${fmt.format(java.util.Date(c.end))} · ${km(c.distanceM)}km" +
                                    (if (c.stops > 0) " · ${c.stops}곳 들름" else ""),
                                style = MaterialTheme.typography.bodySmall, color = Dot.Text, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        "지도에서 보기 ›",
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Dot.Lime,
                        modifier = Modifier.align(Alignment.End).clickable { onShowDay(d) }.padding(4.dp),
                    )
                }
            }
        }
    }
}
