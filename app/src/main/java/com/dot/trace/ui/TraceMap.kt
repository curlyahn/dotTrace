package com.dot.trace.ui

import android.graphics.RectF
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dot.trace.data.PointEntity
import com.dot.trace.trips.Segment
import com.dot.trace.trips.TransportMode
import com.dot.trace.ui.theme.Dot
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * 지도: MapLibre + OpenFreeMap.
 * API 키·회원가입·결제 계정이 필요 없는 무료 지도. 지도 배경(타일)만 인터넷에서 받고, 위치 기록은 보내지 않는다.
 */
private fun styleUrl(name: String) = "https://tiles.openfreemap.org/styles/$name"
private const val STYLE_FALLBACK = "liberty"

/** 화면(버튼 등)에서 지도 카메라를 움직이기 위한 손잡이 */
class MapController {
    internal var map: MapLibreMap? = null
    var paddingTop = 0
    var paddingBottom = 0
    var paddingSide = 0

    val ready: Boolean get() = map != null

    /** 주어진 위치들이 모두 보이게 맞춘다. 위·아래 카드에 가리지 않도록 여백을 둔다. */
    fun fit(points: List<PointEntity>, animate: Boolean = true) {
        val m = map ?: return
        if (points.isEmpty()) return
        var minLat = points.minOf { it.lat }; var maxLat = points.maxOf { it.lat }
        var minLng = points.minOf { it.lng }; var maxLng = points.maxOf { it.lng }
        // 한 점이거나 아주 좁으면 동네 정도가 보이게 넓힌다
        if (maxLat - minLat < 0.003) { val c = (maxLat + minLat) / 2; minLat = c - 0.0015; maxLat = c + 0.0015 }
        if (maxLng - minLng < 0.003) { val c = (maxLng + minLng) / 2; minLng = c - 0.0015; maxLng = c + 0.0015 }
        val bounds = LatLngBounds.Builder().include(LatLng(minLat, minLng)).include(LatLng(maxLat, maxLng)).build()
        val update = CameraUpdateFactory.newLatLngBounds(bounds, paddingSide, paddingTop, paddingSide, paddingBottom)
        if (animate) m.animateCamera(update, 600) else m.moveCamera(update)
    }

    fun focus(p: PointEntity) = fit(listOf(p))

    /** 지금 지도 가운데 좌표 (장소 고르기용) */
    fun center(): LatLng? = map?.cameraPosition?.target

    /** 확대 정도를 정해 한 점으로 이동 */
    fun moveTo(lat: Double, lng: Double, zoom: Double = 16.0, animate: Boolean = true) {
        val m = map ?: return
        val u = CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom)
        if (animate) m.animateCamera(u, 500) else m.moveCamera(u)
    }

    fun focusAt(lat: Double, lng: Double) =
        fit(listOf(PointEntity(time = 0, lat = lat, lng = lng, accuracy = null, altitude = null, speed = null)))
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
    /** 지도 스타일 이름 (liberty, bright, positron, fiord, dark) */
    styleName: String,
    /** '내 위치' 버튼으로 방금 잡은 현재 위치 (기록과 별개로 표시만) */
    me: android.location.Location?,
    onPointClick: (PointEntity?) -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
    /** 내 장소 (집·회사·자주 가는 곳): 이름표와 함께 표시 */
    places: List<com.dot.trace.data.PlaceEntity> = emptyList(),
    /** 지도 이동이 멈췄을 때 가운데 좌표 */
    onCameraIdle: ((LatLng) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentPoints by rememberUpdatedState(points)
    val currentOnClick by rememberUpdatedState(onPointClick)
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnIdle by rememberUpdatedState(onCameraIdle)
    var style by remember { mutableStateOf<Style?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    // 화면 생명주기에 맞춰 지도를 켜고 끈다
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.map = null
            mapView.onDestroy()
        }
    }

    // 지도 준비: 기본 설정과 탭 처리
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                // OpenStreetMap 출처 표시는 남겨 두되, 위 날짜 막대 아래로 옮긴다
                attributionGravity = Gravity.TOP or Gravity.END
                setAttributionMargins(0, (controller.paddingTop - controller.paddingSide).coerceAtLeast(0), controller.paddingSide / 2, 0)
            }
            map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(LatLng(37.5665, 126.9780)).zoom(11.0).build()

            map.addOnMapClickListener { ll ->
                val sp = map.projection.toScreenLocation(ll)
                val r = 18f * context.resources.displayMetrics.density
                val hits = map.queryRenderedFeatures(RectF(sp.x - r, sp.y - r, sp.x + r, sp.y + r), "trace-head", "trace-dots")
                val id = hits.firstOrNull()?.getNumberProperty("id")?.toLong()
                currentOnClick(id?.let { pid -> currentPoints.firstOrNull { it.id == pid } })
                id != null
            }
            map.addOnCameraIdleListener { map.cameraPosition.target?.let { t -> currentOnIdle?.invoke(t) } }
            mapRef = map
        }
    }

    // 지도 스타일: 처음과 설정에서 바꿀 때마다 불러오고, 우리 레이어를 다시 얹는다
    DisposableEffect(mapRef, styleName) {
        val map = mapRef ?: return@DisposableEffect onDispose {}
        var triedFallback = false
        fun load(name: String) {
            style = null
            map.setStyle(Style.Builder().fromUri(styleUrl(name))) { s ->
                koreanLabels(s)
                addTraceLayers(s)
                controller.map = map
                style = s
                currentOnReady()
            }
        }
        val failListener = MapView.OnDidFailLoadingMapListener {
            if (!triedFallback && styleName != STYLE_FALLBACK) { triedFallback = true; load(STYLE_FALLBACK) }
        }
        mapView.addOnDidFailLoadingMapListener(failListener)
        load(styleName)
        onDispose { mapView.removeOnDidFailLoadingMapListener(failListener) }
    }

    // 데이터가 바뀌면 지도 위 선·점만 다시 그린다
    LaunchedEffect(style, points, dots, moves, modeOfPoint, selectedTrip, selected, me, places) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_PLACES)?.setGeoJson(
            FeatureCollection.fromFeatures(places.map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply { addStringProperty("name", p.name) }
            })
        )
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }))
        )
        s.getSourceAs<GeoJsonSource>(SRC_BASE)?.setGeoJson(baseLine(points))
        s.getSourceAs<GeoJsonSource>(SRC_SEG)?.setGeoJson(segmentLines(moves, selectedTrip))
        s.getSourceAs<GeoJsonSource>(SRC_DOTS)?.setGeoJson(dotFeatures(dots, modeOfPoint, selectedTrip))
        s.getSourceAs<GeoJsonSource>(SRC_HEAD)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(points.lastOrNull()?.let { pointFeature(it) }))
        )
        s.getSourceAs<GeoJsonSource>(SRC_SEL)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(selected?.let { pointFeature(it) }))
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val SRC_BASE = "trace-base-src"
private const val SRC_SEG = "trace-seg-src"
private const val SRC_DOTS = "trace-dots-src"
private const val SRC_HEAD = "trace-head-src"
private const val SRC_SEL = "trace-sel-src"
private const val SRC_ME = "trace-me-src"
private const val SRC_PLACES = "trace-places-src"

private fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

private fun addTraceLayers(s: Style) {
    listOf(SRC_PLACES, SRC_BASE, SRC_SEG, SRC_DOTS, SRC_HEAD, SRC_SEL, SRC_ME).forEach { s.addSource(GeoJsonSource(it)) }

    // 내 장소: 경로 아래에 이름표와 함께
    s.addLayer(CircleLayer("trace-places", SRC_PLACES).withProperties(
        PropertyFactory.circleColor(hex(Dot.Ink)),
        PropertyFactory.circleRadius(9f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(2.5f),
    ))
    s.addLayer(SymbolLayer("trace-places-label", SRC_PLACES).withProperties(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf("Noto Sans Bold", "Noto Sans Regular")),
        PropertyFactory.textSize(13f),
        PropertyFactory.textColor(hex(Dot.Ink)),
        PropertyFactory.textHaloColor("#FFFFFF"),
        PropertyFactory.textHaloWidth(2f),
        PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textAllowOverlap(true),
    ))

    // 하루 전체 경로 (옅은 선)
    s.addLayer(LineLayer("trace-base", SRC_BASE).withProperties(
        PropertyFactory.lineColor(hex(Dot.Muted)),
        PropertyFactory.lineOpacity(0.35f),
        PropertyFactory.lineWidth(2f),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    // 이동 수단별 색 구간: 어두운 테두리 선을 먼저 깔아 밝은 지도에서도 또렷하게
    s.addLayer(LineLayer("trace-seg-casing", SRC_SEG).withProperties(
        PropertyFactory.lineColor(hex(Dot.Ink)),
        PropertyFactory.lineOpacity(Expression.product(Expression.get("opacity"), Expression.literal(0.55f))),
        PropertyFactory.lineWidth(Expression.sum(Expression.get("width"), Expression.literal(3f))),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
    ))
    s.addLayer(LineLayer("trace-seg", SRC_SEG).withProperties(
        PropertyFactory.lineColor(Expression.get("color")),
        PropertyFactory.lineOpacity(Expression.get("opacity")),
        PropertyFactory.lineWidth(Expression.get("width")),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
    ))
    // 기록 지점
    s.addLayer(CircleLayer("trace-dots", SRC_DOTS).withProperties(
        PropertyFactory.circleColor(Expression.get("color")),
        PropertyFactory.circleRadius(Expression.get("r")),
        PropertyFactory.circleStrokeColor(hex(Dot.Ink)),
        PropertyFactory.circleStrokeWidth(1.2f),
    ))
    // 마지막 위치
    s.addLayer(CircleLayer("trace-head", SRC_HEAD).withProperties(
        PropertyFactory.circleColor(hex(Dot.Lime)),
        PropertyFactory.circleRadius(8f),
        PropertyFactory.circleStrokeColor(hex(Dot.Ink)),
        PropertyFactory.circleStrokeWidth(3f),
    ))
    // 탭해서 고른 지점
    s.addLayer(CircleLayer("trace-sel", SRC_SEL).withProperties(
        PropertyFactory.circleColor("#FFFFFF"),
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor(hex(Dot.Lime)),
        PropertyFactory.circleStrokeWidth(3f),
    ))
    // '내 위치' 버튼으로 잡은 현재 위치: 파란 점 + 옅은 원
    s.addLayer(CircleLayer("trace-me-halo", SRC_ME).withProperties(
        PropertyFactory.circleColor("#4DA3FF"),
        PropertyFactory.circleOpacity(0.22f),
        PropertyFactory.circleRadius(20f),
    ))
    s.addLayer(CircleLayer("trace-me", SRC_ME).withProperties(
        PropertyFactory.circleColor("#4DA3FF"),
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(3f),
    ))
}

/** 지명은 한국어 이름이 있으면 한국어로 */
private fun koreanLabels(s: Style) {
    for (layer in s.layers) {
        if (layer !is SymbolLayer) continue
        if (layer.textField.isNull) continue
        val id = layer.id.lowercase()
        if ("shield" in id || "housenumber" in id || "ref" in id) continue
        runCatching {
            layer.setProperties(
                PropertyFactory.textField(
                    Expression.coalesce(Expression.get("name:ko"), Expression.get("name"), Expression.get("name_en"))
                )
            )
        }
    }
}

private fun baseLine(points: List<PointEntity>): FeatureCollection =
    if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
    else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lng, it.lat) })))

private fun segmentLines(moves: List<Segment>, selectedTrip: Segment?): FeatureCollection =
    FeatureCollection.fromFeatures(moves.filter { it.points.size >= 2 }.map { m ->
        Feature.fromGeometry(LineString.fromLngLats(m.points.map { Point.fromLngLat(it.lng, it.lat) })).apply {
            addStringProperty("color", hex(ModeStyle.color(m.mode)))
            addNumberProperty("opacity", if (selectedTrip != null && selectedTrip != m) 0.2f else 0.85f)
            addNumberProperty("width", if (selectedTrip == m) 5f else 3.5f)
        }
    })

private fun dotFeatures(dots: List<PointEntity>, modeOfPoint: Map<Long, TransportMode>, selectedTrip: Segment?): FeatureCollection {
    val inTrip = selectedTrip?.points?.map { it.id }?.toHashSet()
    return FeatureCollection.fromFeatures(dots.map { p ->
        val mode = modeOfPoint[p.id]
        val active = mode != null && (inTrip == null || p.id in inTrip)
        pointFeature(p).apply {
            addStringProperty("color", if (active) hex(ModeStyle.color(mode!!)) else hex(Dot.Faint))
            addNumberProperty("r", if (active) 4.5f else 3.5f)
        }
    })
}

private fun pointFeature(p: PointEntity): Feature =
    Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply { addNumberProperty("id", p.id) }
