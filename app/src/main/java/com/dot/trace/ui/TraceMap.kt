package com.dot.trace.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dot.trace.R
import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.PointEntity
import com.dot.trace.trips.Segment
import com.dot.trace.trips.TransportMode
import com.dot.trace.ui.theme.Dot
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 지도: Google 지도.
 * 지도 그림만 Google에서 받고, 위치 기록은 보내지 않는다.
 */

/** 화면(버튼 등)에서 지도 카메라를 움직이기 위한 손잡이 */
class MapController {
    internal var state: CameraPositionState? = null
    internal var scope: CoroutineScope? = null
    internal var loaded = false
    var paddingTop = 0
    var paddingBottom = 0
    var paddingSide = 0

    val ready: Boolean get() = loaded && state != null

    /** 주어진 위치들이 모두 보이게 맞춘다. 위·아래 카드 여백은 지도 여백(contentPadding)으로 이미 빠져 있다. */
    fun fit(points: List<PointEntity>, animate: Boolean = true) {
        if (!ready || points.isEmpty()) return
        var minLat = points.minOf { it.lat }; var maxLat = points.maxOf { it.lat }
        var minLng = points.minOf { it.lng }; var maxLng = points.maxOf { it.lng }
        // 한 점이거나 아주 좁으면 동네 정도가 보이게 넓힌다
        if (maxLat - minLat < 0.003) { val c = (maxLat + minLat) / 2; minLat = c - 0.0015; maxLat = c + 0.0015 }
        if (maxLng - minLng < 0.003) { val c = (maxLng + minLng) / 2; minLng = c - 0.0015; maxLng = c + 0.0015 }
        val bounds = LatLngBounds(LatLng(minLat, minLng), LatLng(maxLat, maxLng))
        go(CameraUpdateFactory.newLatLngBounds(bounds, paddingSide), animate)
    }

    fun focus(p: PointEntity) = fit(listOf(p))

    /** 지금 지도 가운데 좌표 (장소 고르기용) */
    fun center(): LatLng? = state?.position?.target

    /** 확대 정도를 정해 한 점으로 이동 */
    fun moveTo(lat: Double, lng: Double, zoom: Double = 16.0, animate: Boolean = true) {
        if (state == null) return
        go(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom.toFloat()), animate, 500)
    }

    fun focusAt(lat: Double, lng: Double) =
        fit(listOf(PointEntity(time = 0, lat = lat, lng = lng, accuracy = null, altitude = null, speed = null)))

    private fun go(u: CameraUpdate, animate: Boolean, ms: Int = 600) {
        val st = state ?: return
        if (animate && loaded) scope?.launch { runCatching { st.animate(u, ms) } }
        else runCatching { st.move(u) }
    }
}

@Composable
fun TraceMap(
    controller: MapController,
    points: List<PointEntity>,
    dots: List<PointEntity>,
    moves: List<Segment>,
    modeOfPoint: Map<Long, TransportMode>,
    selectedTrip: Segment?,
    selected: PointEntity?,
    /** 지도 스타일 이름 (standard, silver, night, satellite) */
    styleName: String,
    /** '내 위치' 버튼으로 방금 잡은 현재 위치 (기록과 별개로 표시만) */
    me: android.location.Location?,
    onPointClick: (PointEntity?) -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
    /** 내 장소 (집·회사·자주 가는 곳): 이름표와 함께 표시 */
    places: List<PlaceEntity> = emptyList(),
    /** 지도 이동이 멈췄을 때 가운데 좌표 */
    onCameraIdle: ((LatLng) -> Unit)? = null,
    /** 머문 곳: 점 여러 개 대신 원 하나 + 이름·머문 시간 */
    stays: List<Segment> = emptyList(),
    onStayClick: ((Segment) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnIdle by rememberUpdatedState(onCameraIdle)
    val currentOnReady by rememberUpdatedState(onReady)

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(37.5665, 126.9780), 11f)
    }
    controller.state = cameraState
    controller.scope = scope

    val styleOptions = remember(styleName) {
        when (styleName) {
            "silver" -> MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_silver)
            "night" -> MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)
            else -> null
        }
    }

    // 지도 그림(타일)을 못 받아도 onMapLoaded가 오지 않아 경로·카메라가 멈추지 않도록, 지도가 만들어지면 잠시 뒤 준비된 것으로 본다
    LaunchedEffect(cameraState) {
        while (cameraState.projection == null) kotlinx.coroutines.delay(200)
        kotlinx.coroutines.delay(2_500)
        if (!controller.loaded) {
            controller.loaded = true
            currentOnReady()
        }
    }

    // 지도 이동이 멈추면 가운데 좌표를 알려 준다
    LaunchedEffect(cameraState.isMoving) {
        if (!cameraState.isMoving) currentOnIdle?.invoke(cameraState.position.target)
    }

    val padding = with(density) {
        PaddingValues(top = controller.paddingTop.toDp(), bottom = controller.paddingBottom.toDp())
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        properties = MapProperties(
            mapType = if (styleName == "satellite") MapType.HYBRID else MapType.NORMAL,
            mapStyleOptions = styleOptions,
            isBuildingEnabled = false,
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            compassEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            indoorLevelPickerEnabled = false,
        ),
        // 위·아래 떠 있는 카드에 지도 로고와 경로가 가리지 않도록
        contentPadding = padding,
        onMapLoaded = {
            if (!controller.loaded) {
                controller.loaded = true
                currentOnReady()
            }
        },
        onMapClick = { onPointClick(null) },
    ) {
        val d = density.density
        val dotIcons = remember { HashMap<Int, BitmapDescriptor>() }
        fun dotIcon(fill: Color, stroke: Color, sizeDp: Int): BitmapDescriptor {
            val k = fill.toArgb() * 31 + stroke.toArgb() * 7 + sizeDp
            return dotIcons.getOrPut(k) { dotDescriptor(fill, stroke, sizeDp, d) }
        }
        val meIcon = remember { meDescriptor(d) }

        // 내 장소: 이름표와 함께, 경로 아래에
        places.forEach { p ->
            key("place", p.id) {
                val icon = remember(p.name) { labeledDescriptor(Dot.Ink, Color.White, 9, 2.5f, p.name, 13f, d) }
                Marker(
                    state = remember(p.lat, p.lng) { MarkerState(LatLng(p.lat, p.lng)) },
                    icon = icon.first,
                    anchor = Offset(0.5f, icon.second),
                    zIndex = 0.2f,
                    onClick = { true },
                )
            }
        }

        // 하루 전체 경로 (옅은 선)
        if (points.size >= 2) {
            Polyline(
                points = remember(points) { points.map { LatLng(it.lat, it.lng) } },
                color = Dot.Muted.copy(alpha = 0.35f),
                width = 2f * d,
                jointType = JointType.ROUND,
                zIndex = 0.1f,
            )
        }

        // 이동 수단별 색 구간: 어두운 테두리 선을 먼저 깔아 밝은 지도에서도 또렷하게
        moves.filter { it.points.size >= 2 }.forEach { m ->
            key("move", m.start) {
                val ll = remember(m) { m.points.map { LatLng(it.lat, it.lng) } }
                val dim = selectedTrip != null && selectedTrip != m
                val opacity = if (dim) 0.2f else 0.85f
                val w = if (selectedTrip == m) 5f else 3.5f
                val z = if (selectedTrip == m) 0.6f else 0.4f
                Polyline(
                    points = ll,
                    color = Dot.Ink.copy(alpha = opacity * 0.55f),
                    width = (w + 3f) * d,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    zIndex = z,
                )
                Polyline(
                    points = ll,
                    color = ModeStyle.color(m.mode).copy(alpha = opacity),
                    width = w * d,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    zIndex = z + 0.05f,
                )
            }
        }

        // 머문 곳: 원 하나 + 이름·머문 시간
        stays.forEach { st ->
            key("stay", st.start) {
                val label = listOfNotNull(st.name, stayDuration(st.durationMs)).joinToString("\n")
                val fill = if (st.placeKind != null) Dot.Lime else Color.White
                val icon = remember(label, fill) { labeledDescriptor(fill, Dot.Ink, 11, 3f, label, 12f, d) }
                Marker(
                    state = remember(st.lat, st.lng) { MarkerState(LatLng(st.lat, st.lng)) },
                    icon = icon.first,
                    anchor = Offset(0.5f, icon.second),
                    zIndex = 2f,
                    onClick = { onStayClick?.invoke(st); true },
                )
            }
        }

        // 기록 지점
        val inTrip = remember(selectedTrip) { selectedTrip?.points?.map { it.id }?.toHashSet() }
        dots.forEach { p ->
            key("dot", p.id) {
                val mode = modeOfPoint[p.id]
                val active = mode != null && (inTrip == null || p.id in inTrip)
                Marker(
                    state = remember(p.id) { MarkerState(LatLng(p.lat, p.lng)) },
                    icon = if (active) dotIcon(ModeStyle.color(mode!!), Dot.Ink, 10) else dotIcon(Dot.Faint, Dot.Ink, 8),
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 1f,
                    onClick = { onPointClick(p); true },
                )
            }
        }

        // 마지막 위치
        points.lastOrNull()?.let { last ->
            key("head", last.id) {
                Marker(
                    state = remember(last.id) { MarkerState(LatLng(last.lat, last.lng)) },
                    icon = dotIcon(Dot.Lime, Dot.Ink, 18),
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 3f,
                    onClick = { onPointClick(last); true },
                )
            }
        }

        // 탭해서 고른 지점
        selected?.let { s ->
            key("sel", s.id) {
                Marker(
                    state = remember(s.id) { MarkerState(LatLng(s.lat, s.lng)) },
                    icon = dotIcon(Color.White, Dot.Lime, 16),
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 4f,
                    onClick = { true },
                )
            }
        }

        // '내 위치' 버튼으로 잡은 현재 위치: 파란 점 + 옅은 원
        me?.let { loc ->
            key("me", loc.latitude, loc.longitude) {
                Marker(
                    state = remember(loc.latitude, loc.longitude) { MarkerState(LatLng(loc.latitude, loc.longitude)) },
                    icon = meIcon,
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = 5f,
                    onClick = { true },
                )
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

/** 현재 위치: 옅은 파란 원 + 흰 테두리 파란 점 */
private fun meDescriptor(density: Float): BitmapDescriptor {
    val blue = android.graphics.Color.rgb(0x4D, 0xA3, 0xFF)
    val px = (40 * density).toInt()
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = px / 2f
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; alpha = 56 })
    c.drawCircle(r, r, 10 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(r, r, 7 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue })
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

/**
 * 원 + 아래 이름표(흰 테두리 글씨) 아이콘.
 * 돌려주는 값: 아이콘과, 원 가운데의 세로 위치(0~1, 마커 기준점으로 씀)
 */
private fun labeledDescriptor(
    fill: Color,
    stroke: Color,
    radiusDp: Int,
    strokeDp: Float,
    label: String,
    textSp: Float,
    density: Float,
): Pair<BitmapDescriptor, Float> {
    val lines = label.lines().filter { it.isNotBlank() }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSp * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = Dot.Ink.toArgb()
    }
    val halo = Paint(text).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    }
    val r = radiusDp * density
    val lineH = text.fontSpacing
    val textW = lines.maxOfOrNull { text.measureText(it) } ?: 0f
    val w = maxOf(2 * r + 4 * density, textW + 8 * density).toInt()
    val gap = 4 * density
    val h = (2 * r + 2 * density + (if (lines.isEmpty()) 0f else gap + lineH * lines.size + 2 * density)).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = r + density
    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke.toArgb() })
    c.drawCircle(cx, cy, r - strokeDp * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() })
    var y = cy + r + gap - text.ascent()
    for (l in lines) {
        c.drawText(l, cx, y, halo)
        c.drawText(l, cx, y, text)
        y += lineH
    }
    return BitmapDescriptorFactory.fromBitmap(bmp) to (cy / h)
}

private fun stayDuration(ms: Long): String {
    val min = ms / 60_000
    val h = min / 60
    return when {
        h >= 1 && min % 60 == 0L -> "${h}시간"
        h >= 1 -> "${h}시간 ${min % 60}분"
        else -> "${min}분"
    }
}
