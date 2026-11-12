package com.dot.trace.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dot.trace.data.ParkingEntity
import com.dot.trace.data.rememberAddress
import com.dot.trace.parking.FloorEstimator
import com.dot.trace.ui.theme.Dot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 주차 사진이 저장되는 폴더 (앱 안, 백업·공유 안 됨) */
fun parkingPhotoDir(context: Context): File = File(context.filesDir, "parking").apply { mkdirs() }

/** 연두 P 배지 */
@Composable
fun ParkingBadge(size: Int = 36, filled: Boolean = true) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp))
            .background(if (filled) Dot.Lime else Dot.Surface)
            .border(2.dp, Dot.Lime, RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("P", color = if (filled) Dot.Ink else Dot.Lime, fontWeight = FontWeight.Black, fontSize = (size * 0.5f).sp)
    }
}

/** 층 배지 (확인한 층은 연두, 추정은 회색 테두리) */
@Composable
fun FloorChip(floor: Int?, confirmed: Boolean) {
    Text(
        FloorEstimator.label(floor),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (confirmed) Dot.Lime else Dot.Text,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (confirmed) Dot.Lime.copy(alpha = 0.14f) else Dot.Surface2)
            .border(1.dp, if (confirmed) Dot.Lime.copy(alpha = 0.5f) else Dot.Line, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * A안: 지도 위 주차 카드.
 * 층(추정/확인) · 장소 · 입구에서 들어간 시간 · 구역 메모 · 사진 · 길 안내
 */
@Composable
fun ParkingCard(
    p: ParkingEntity,
    alpha: Float,
    onClose: () -> Unit,
    onConfirmFloor: () -> Unit,
    onEdit: () -> Unit,
    onTakePhoto: () -> Unit,
    onShowPhoto: () -> Unit,
) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val address = rememberAddress(p.lat, p.lng)
    val now = System.currentTimeMillis()
    val parkedFor = (p.leftAt ?: now) - p.parkedAt
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Dot.Surface.copy(alpha = alpha),
        shadowElevation = if (alpha > 0.9f) 10.dp else 0.dp,
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
            Box(Modifier.fillMaxWidth().clickable(onClick = onClose).padding(top = 10.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(56.dp).height(8.dp)
                        .shadow(8.dp, RoundedCornerShape(50), ambientColor = Dot.Lime, spotColor = Dot.Lime)
                        .clip(RoundedCornerShape(50)).background(Dot.Lime),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ParkingBadge(36)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (p.leftAt == null) "내 차 · 지금 주차 중" else "내 차 주차 기록",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Dot.Text,
                    )
                    Text(
                        "${fmt.format(Date(p.parkedAt))} 하차 · " +
                            (if (p.leftAt == null) "${durationText(parkedFor)}째" else "${fmt.format(Date(p.leftAt))} 승차 · ${durationText(parkedFor)}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Dot.Muted,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        FloorEstimator.label(p.shownFloor),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = if (p.confirmed) Dot.Lime else Dot.Text,
                    )
                    Text(if (p.confirmed) "확인함" else "추정", style = MaterialTheme.typography.labelSmall, color = Dot.Muted)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "닫기", tint = Dot.Muted) }
            }
            Spacer(Modifier.height(10.dp))
            // 자세한 정보
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dot.Surface2).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InfoRow("장소", address ?: "%.5f, %.5f".format(Locale.US, p.lat, p.lng))
                InfoRow(
                    "층",
                    FloorEstimator.longLabel(p.shownFloor) + "  " +
                        (if (p.confirmed) "(직접 확인)" else "(${FloorEstimator.sourceLabel(p.floorSource)})"),
                )
                p.entryAt?.let { InfoRow("입구", "정확한 GPS ${fmt.format(Date(it))} · 진입 후 ${durationText((p.parkedAt - it).coerceAtLeast(0))}") }
                InfoRow(
                    "기압 차",
                    p.deltaHpa?.let { "%+.2f hPa".format(Locale.US, it) + (p.entrySamples?.let { n -> " (입구 ${n}개 · 주차 ${p.parkSamples ?: 0}개 측정)" } ?: "") }
                        ?: if (p.entrySamples != null) "측정 부족 (입구 ${p.entrySamples}개 · 주차 ${p.parkSamples ?: 0}개)" else "없음",
                )
            }
            // 층 확인 (추정일 때만)
            if (!p.confirmed && p.floorEst != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Dot.Lime.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${FloorEstimator.longLabel(p.floorEst)} 맞나요? 확인하면 이 건물의 층을 배워요",
                        style = MaterialTheme.typography.bodySmall,
                        color = Dot.Text,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onConfirmFloor) { Text("맞아요", color = Dot.Lime, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onEdit) { Text("고치기", color = Dot.Muted) }
                }
            }
            // 구역 메모·사진
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!p.zone.isNullOrBlank()) {
                    Text(
                        "구역 ${p.zone}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Dot.Lime,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Dot.Lime.copy(alpha = 0.14f))
                            .border(1.dp, Dot.Lime.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                p.photo?.let { name ->
                    val img = rememberParkingPhoto(name, maxPx = 200)
                    Box(Modifier.size(width = 48.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(Dot.Surface2).clickable(onClick = onShowPhoto)) {
                        img?.let { Image(it, "주차 사진", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                if (p.zone.isNullOrBlank() && p.photo == null) {
                    Text("기둥 번호나 사진을 남겨 두면 찾기 쉬워요", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Dot.Surface2, contentColor = Dot.Text),
                ) { Text("층·구역", maxLines = 1, softWrap = false) }
                Button(
                    onClick = onTakePhoto,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Dot.Surface2, contentColor = Dot.Text),
                ) { Text(if (p.photo == null) "사진" else "다시 찍기", maxLines = 1, softWrap = false) }
                Button(
                    onClick = { openWalkingNavigation(context, p.lat, p.lng) },
                    modifier = Modifier.weight(1.2f).height(48.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Dot.Lime, contentColor = Dot.OnLime),
                ) { Text("길 안내", maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Dot.Muted, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Dot.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 차까지 걸어서 길 안내 (Google 지도 앱) */
fun openWalkingNavigation(context: Context, lat: Double, lng: Double) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=w")).setPackage("com.google.android.apps.maps")
    val ok = runCatching { context.startActivity(nav) }.isSuccess
    if (!ok) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(내 차)"))) }
}

/** 앱 안에 저장한 주차 사진 (작게 줄여 읽기) */
@Composable
fun rememberParkingPhoto(name: String, maxPx: Int): ImageBitmap? {
    val context = LocalContext.current
    val v by produceState<ImageBitmap?>(null, name, maxPx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(parkingPhotoDir(context), name)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx * 2) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return v
}

/** 사진 크게 보기 */
@Composable
fun ParkingPhotoDialog(name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Dot.Ink).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            rememberParkingPhoto(name, 1600)?.let { Image(it, "주차 사진", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** 층·구역 고치기. 층을 고르면 이 건물의 층 학습에 쓰인다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloorZoneDialog(p: ParkingEntity, onDismiss: () -> Unit, onSave: (floor: Int?, zone: String) -> Unit) {
    var floor by remember { mutableStateOf(p.shownFloor) }
    var zone by remember { mutableStateOf(p.zone ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("주차한 층·구역") },
        text = {
            Column {
                Text(
                    "고른 층과 그때의 기압·와이파이를 이 건물 값으로 기억해서, 다음에 같은 건물에 주차하면 더 정확하게 맞춰요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dot.Muted,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (-6..4).forEach { f ->
                        val on = f == floor
                        Text(
                            FloorEstimator.label(f),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (on) Dot.Ink else Dot.Text,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Dot.Lime else Dot.Surface2)
                                .clickable { floor = f }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = zone,
                    onValueChange = { zone = it.take(20) },
                    label = { Text("구역·기둥 번호 (예: C-14)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Dot.Lime, focusedLabelColor = Dot.Lime, cursorColor = Dot.Lime),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(floor, zone.trim()) }) { Text("저장", color = Dot.Lime) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        containerColor = Dot.Surface,
    )
}

/** B안: 타임라인 속 승차·하차 한 줄 */
@Composable
fun ParkingEventRow(p: ParkingEntity, alight: Boolean, time: Long, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    val address = rememberAddress(p.lat, p.lng)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            if (alight) ParkingBadge(26)
            else Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Dot.Surface).border(2.dp, Dot.Lime, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("↑", color = Dot.Lime, fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (alight) Dot.Lime.copy(alpha = 0.08f) else Dot.Surface)
                .border(1.dp, if (alight) Dot.Lime.copy(alpha = 0.35f) else Dot.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${fmt.format(Date(time))} 내 차 ${if (alight) "하차" else "승차"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (alight) Dot.Lime else Dot.Text,
                )
                Text(address ?: "주차 위치", style = MaterialTheme.typography.labelSmall, color = Dot.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FloorChip(p.shownFloor, p.confirmed)
            if (alight && !p.zone.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(p.zone, style = MaterialTheme.typography.labelSmall, color = Dot.Muted)
            }
            if (alight && p.photo != null) {
                Spacer(Modifier.width(6.dp))
                Text("📷", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** '내 차' 버튼 (지금 주차 중일 때 지도 오른쪽) */
@Composable
fun MyCarButton(alpha: Float, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(16.dp),
        color = Dot.Surface.copy(alpha = alpha),
        border = BorderStroke(1.5.dp, Dot.Lime.copy(alpha = 0.55f)),
        shadowElevation = if (alpha > 0.9f) 6.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("P", color = Dot.Lime, fontWeight = FontWeight.Black, fontSize = 22.sp)
        }
    }
}
