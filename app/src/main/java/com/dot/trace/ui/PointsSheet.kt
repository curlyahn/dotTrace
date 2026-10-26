package com.dot.trace.ui

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dot.trace.data.PointEntity
import com.dot.trace.trips.TransportMode
import com.dot.trace.ui.theme.Dot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** 같은 곳(반경 50m)에 이어서 찍힌 지점들을 한 줄로 묶는다 */
data class PlaceGroup(val points: List<PointEntity>) {
    val first get() = points.first()
    val last get() = points.last()
}

fun groupPlaces(points: List<PointEntity>, radiusM: Float = 50f): List<PlaceGroup> {
    val out = ArrayList<PlaceGroup>()
    var cur = ArrayList<PointEntity>()
    val d = FloatArray(1)
    for (p in points) {
        val anchor = cur.firstOrNull()
        if (anchor != null) {
            Location.distanceBetween(anchor.lat, anchor.lng, p.lat, p.lng, d)
            if (d[0] > radiusM) {
                out += PlaceGroup(cur)
                cur = ArrayList()
            }
        }
        cur += p
    }
    if (cur.isNotEmpty()) out += PlaceGroup(cur)
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsSheet(
    points: List<PointEntity>,
    modeOfPoint: Map<Long, TransportMode>,
    onDismiss: () -> Unit,
    onPick: (PointEntity) -> Unit,
) {
    val groups = remember(points) { groupPlaces(points) }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Dot.Surface,
        contentColor = Dot.Text,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "기록한 지점 ${points.size}개",
                style = MaterialTheme.typography.titleLarge,
                color = Dot.Text,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
            Text(
                "같은 곳에 이어서 찍힌 지점은 한 줄로 묶었어요. 누르면 지도에서 보여 줘요.",
                style = MaterialTheme.typography.bodySmall,
                color = Dot.Muted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(groups, key = { _, g -> g.first.id }) { i, g ->
                    if (i > 0) HorizontalDivider(color = Dot.Line, modifier = Modifier.padding(start = 56.dp))
                    PlaceRow(g, fmt, modeOfPoint[g.first.id]) { onPick(g.last) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PlaceRow(g: PlaceGroup, fmt: SimpleDateFormat, mode: TransportMode?, onClick: () -> Unit) {
    val address = rememberAddress(g.first.lat, g.first.lng)
    val time = if (g.points.size == 1) fmt.format(Date(g.first.time))
    else "${fmt.format(Date(g.first.time))}–${fmt.format(Date(g.last.time))}"
    val color = mode?.let { ModeStyle.color(it) } ?: Dot.Lime
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Dot.Text)
                if (g.points.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text("${g.points.size}회", style = MaterialTheme.typography.labelSmall, color = Dot.Muted)
                }
            }
            Text(
                address ?: "%.5f, %.5f".format(Locale.US, g.first.lat, g.first.lng),
                style = MaterialTheme.typography.bodySmall,
                color = Dot.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Dot.Faint)
    }
}

/** 좌표 → 주소 (휴대폰의 주소 변환 기능 사용). 한 번 찾은 주소는 기억해 둔다. */
@Composable
fun rememberAddress(lat: Double, lng: Double): String? {
    val context = LocalContext.current
    val key = "%.4f,%.4f".format(Locale.US, lat, lng)
    val state by produceState(initialValue = AddressCache[key], key) {
        if (value == null) value = lookupAddress(context, lat, lng)?.also { AddressCache[key] = it }
    }
    return state
}

private val AddressCache = ConcurrentHashMap<String, String>()

private suspend fun lookupAddress(context: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    val geocoder = Geocoder(context, Locale.KOREA)
    val address = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) { cont.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { cont.resume(null) }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
        }
    }.getOrNull() ?: return@withContext null
    address.getAddressLine(0)?.removePrefix("대한민국")?.trim()?.takeIf { it.isNotBlank() }
}
