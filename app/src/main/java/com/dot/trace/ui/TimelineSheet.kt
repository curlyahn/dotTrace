package com.dot.trace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.rememberAddress
import com.dot.trace.trips.Segment
import com.dot.trace.trips.TransportMode
import com.dot.trace.ui.theme.Dot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 하루 타임라인: 머문 곳과 이동을 시간순으로. 누르면 지도에서, 연필로 고치기 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineSheet(
    segments: List<Segment>,
    onDismiss: () -> Unit,
    onPick: (Segment) -> Unit,
    onEdit: (Segment) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Dot.Surface,
        contentColor = Dot.Text,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "하루 타임라인",
                style = MaterialTheme.typography.titleLarge,
                color = Dot.Text,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
            Text(
                "누르면 지도에서 보여 주고, 연필을 누르면 이름이나 이동 수단을 고칠 수 있어요. 고친 내용은 다음에도 기억해요.",
                style = MaterialTheme.typography.bodySmall,
                color = Dot.Muted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            if (segments.isEmpty()) {
                Text(
                    "이 날은 아직 정리할 기록이 없어요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dot.Muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                itemsIndexed(segments, key = { _, s -> "${s.stay}-${s.start}" }) { i, s ->
                    TimelineRow(s, first = i == 0, last = i == segments.lastIndex, onClick = { onPick(s) }, onEdit = { onEdit(s) })
                }
            }
            // Google 장소 정보로 붙인 이름이 있으면 출처 표시
            if (segments.any { it.nameFromGoogle }) {
                Text(
                    "장소 이름 일부: Google Maps",
                    style = MaterialTheme.typography.labelSmall,
                    color = Dot.Faint,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TimelineRow(s: Segment, first: Boolean, last: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val color = if (s.stay) (if (s.placeKind != null) Dot.Lime else Dot.Text) else ModeStyle.color(s.mode)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽 세로 줄 + 아이콘
        Box(Modifier.width(36.dp).height(if (s.stay) 72.dp else 64.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(2.dp).weight(1f).background(if (first) Dot.Surface else Dot.Line))
                Box(Modifier.width(2.dp).weight(1f).background(if (last) Dot.Surface else Dot.Line))
            }
            Box(
                Modifier.size(if (s.stay) 34.dp else 28.dp).clip(CircleShape)
                    .background(if (s.stay) Dot.Surface2 else color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(s), null, tint = color, modifier = Modifier.size(if (s.stay) 18.dp else 16.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (s.stay) {
                val address = rememberAddress(s.lat, s.lng)
                Text(
                    s.name ?: address ?: "머문 곳",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Dot.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${fmt.format(Date(s.start))}–${fmt.format(Date(s.end))} · ${durationText(s.durationMs)}" +
                        (if (s.name != null && address != null) "\n$address" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Dot.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "${s.mode.label} · ${"%.1f".format(Locale.US, s.distanceM / 1000)}km",
                    style = MaterialTheme.typography.titleMedium,
                    color = Dot.Text,
                )
                Text(
                    "${fmt.format(Date(s.start))}–${fmt.format(Date(s.end))} · ${durationText(s.durationMs)}" +
                        (if (s.edited) " · 직접 고침" else if (s.learned) " · 학습한 경로" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Dot.Muted,
                )
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "고치기", tint = Dot.Faint, modifier = Modifier.size(20.dp)) }
    }
}

private fun iconFor(s: Segment): ImageVector = when {
    !s.stay -> ModeStyle.icon(s.mode)
    s.placeKind == PlaceEntity.HOME -> Icons.Rounded.Home
    s.placeKind == PlaceEntity.WORK -> Icons.Rounded.Work
    s.placeKind == PlaceEntity.FAV -> Icons.Rounded.Star
    else -> Icons.Rounded.Place
}

fun durationText(ms: Long): String {
    val min = ms / 60_000
    val h = min / 60
    return when {
        h >= 1 && min % 60 == 0L -> "${h}시간"
        h >= 1 -> "${h}시간 ${min % 60}분"
        else -> "${min}분"
    }
}

/** 머문 곳 이름 고치기 */
@Composable
fun StayEditDialog(seg: Segment, onDismiss: () -> Unit, onSave: (name: String, remember: Boolean) -> Unit) {
    val address = rememberAddress(seg.lat, seg.lng)
    var name by remember { mutableStateOf(seg.name ?: "") }
    var rememberIt by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("머문 곳 이름") },
        text = {
            Column {
                address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dot.Muted) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("예: 헬스장, 부모님 댁") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Dot.Lime, focusedLabelColor = Dot.Lime, cursorColor = Dot.Lime),
                )
                Spacer(Modifier.height(8.dp))
                CheckRow("다음에도 이곳은 이 이름으로", rememberIt) { rememberIt = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, rememberIt) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        containerColor = Dot.Surface,
    )
}

/** 이동 수단 고치기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoveEditDialog(seg: Segment, onDismiss: () -> Unit, onSave: (mode: TransportMode, learn: Boolean) -> Unit) {
    var mode by remember { mutableStateOf(seg.mode) }
    var learn by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이동 수단") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransportMode.entries.filter { it != TransportMode.Unknown }.forEach { m ->
                        val on = m == mode
                        val c = ModeStyle.color(m)
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) c else Dot.Surface2)
                                .clickable { mode = m }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(ModeStyle.icon(m), null, tint = if (on) Dot.Ink else c, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(m.label, style = MaterialTheme.typography.labelLarge, color = if (on) Dot.Ink else Dot.Text)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                CheckRow("이 경로로 다니는 이동은 다음에도 이 수단으로 (일부 구간만 겹쳐도)", learn) { learn = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(mode, learn) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        containerColor = Dot.Surface,
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = Dot.Lime, checkmarkColor = Dot.OnLime),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Dot.Text)
    }
}
