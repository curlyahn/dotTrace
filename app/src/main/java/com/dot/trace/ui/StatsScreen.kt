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
fun StatsScreen(vm: MainViewModel, initialKey: String?, onBack: () -> Unit, onShowPlace: (Double, Double) -> Unit) {
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
            else -> ReportBody(r, onShowPlace)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ReportBody(r: Report, onShowPlace: (Double, Double) -> Unit) {
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
