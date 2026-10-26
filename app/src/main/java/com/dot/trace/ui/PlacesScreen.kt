package com.dot.trace.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.trace.data.PlaceEntity
import com.dot.trace.data.rememberAddress
import com.dot.trace.ui.theme.Dot
import org.maplibre.android.geometry.LatLng

/** 설정 → 내 장소: 집, 회사, 자주 가는 곳(최대 10개) */
@Composable
fun PlacesScreen(vm: MainViewModel, onBack: () -> Unit, onEdit: (kind: String, id: Long?) -> Unit) {
    val places by vm.places.collectAsStateWithLifecycle()
    val home = places.firstOrNull { it.kind == PlaceEntity.HOME }
    val work = places.firstOrNull { it.kind == PlaceEntity.WORK }
    val favs = places.filter { it.kind == PlaceEntity.FAV }
    val labels = places.filter { it.kind == PlaceEntity.LABEL }
    val rules by vm.rules.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(Dot.Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = Dot.Text) }
            Text("내 장소", style = MaterialTheme.typography.headlineSmall, color = Dot.Text)
        }
        Text(
            "등록한 장소는 지도에 이름표로 보이고, 지점 목록과 통계에서 주소 대신 이름으로 나와요.",
            style = MaterialTheme.typography.bodySmall,
            color = Dot.Muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        PlaceSection("집 · 회사")
        PlaceRow(Icons.Rounded.Home, "집", home) { onEdit(PlaceEntity.HOME, home?.id) }
        PlaceRow(Icons.Rounded.Work, "회사", work) { onEdit(PlaceEntity.WORK, work?.id) }

        PlaceSection("자주 가는 곳 ${favs.size}/${PlaceEntity.MAX_FAVORITES}")
        favs.forEach { f -> PlaceRow(Icons.Rounded.Star, f.name, f) { onEdit(PlaceEntity.FAV, f.id) } }
        if (favs.size < PlaceEntity.MAX_FAVORITES) {
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(PlaceEntity.FAV, null) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Dot.Lime), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = Dot.OnLime)
                }
                Spacer(Modifier.width(14.dp))
                Text("자주 가는 곳 추가", style = MaterialTheme.typography.titleMedium, color = Dot.Text)
            }
        }
        if (labels.isNotEmpty()) {
            PlaceSection("타임라인에서 기억한 이름 ${labels.size}")
            labels.forEach { l -> RemovableRow(l.name, rememberAddress(l.lat, l.lng) ?: "") { vm.deletePlace(l.id) } }
        }
        if (rules.isNotEmpty()) {
            PlaceSection("기억한 이동 수단 ${rules.size}")
            rules.forEach { r ->
                val mode = runCatching { com.dot.trace.trips.TransportMode.valueOf(r.mode) }.getOrNull()
                val from = rememberAddress(r.startLat, r.startLng) ?: "출발지"
                val to = rememberAddress(r.endLat, r.endLng) ?: "도착지"
                RemovableRow(mode?.label ?: r.mode, "$from → $to") { vm.deleteRule(r.id) }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun RemovableRow(title: String, body: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Dot.Text)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall, color = Dot.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "지우기", tint = Dot.Faint) }
    }
}

@Composable
private fun PlaceSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Dot.Faint,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp),
    )
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, place: PlaceEntity?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (place != null) Dot.Surface2 else Dot.Surface),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (place != null) Dot.Lime else Dot.Faint) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Dot.Text)
            Text(
                if (place == null) "눌러서 위치 정하기" else rememberAddress(place.lat, place.lng) ?: "%.5f, %.5f".format(place.lat, place.lng),
                style = MaterialTheme.typography.bodySmall,
                color = Dot.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Dot.Faint)
    }
}

/** 장소 정하기: 지도를 움직여 가운데 핀을 원하는 곳에 맞추고 저장 */
@Composable
fun PlaceEditScreen(vm: MainViewModel, kind: String, id: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { MapController() }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()

    var existing by remember { mutableStateOf<PlaceEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(defaultName(kind)) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<android.location.Address>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        focus.clearFocus()
        searching = true
        scope.launch {
            val found = com.dot.trace.data.Geo.search(context, q)
            searching = false
            when {
                found.isEmpty() -> Toast.makeText(context, "'$q'을(를) 찾지 못했어요. 시·구·도로명까지 넣어 보세요", Toast.LENGTH_LONG).show()
                found.size == 1 -> {
                    controller.moveTo(found[0].latitude, found[0].longitude, 17.0)
                    results = emptyList()
                }
                else -> results = found
            }
        }
    }

    LaunchedEffect(kind, id) {
        existing = id?.let { vm.place(it) } ?: if (kind != PlaceEntity.FAV) vm.placeByKind(kind) else null
        existing?.let { name = it.name }
        loaded = true
    }

    fun gotoCurrent() {
        if (locating) return
        locating = true
        runCatching {
            currentFix(context).addOnSuccessListener { loc ->
                if (loc != null) controller.moveTo(loc.latitude, loc.longitude, 17.0)
                else Toast.makeText(context, "현재 위치를 찾지 못했어요", Toast.LENGTH_SHORT).show()
            }.addOnCompleteListener { locating = false }
        }.onFailure { locating = false }
    }

    // 처음 위치: 이미 정한 장소 → 마지막 기록 → 현재 위치
    LaunchedEffect(mapReady, loaded) {
        if (!mapReady || !loaded) return@LaunchedEffect
        val e = existing
        when {
            e != null -> controller.moveTo(e.lat, e.lng, 17.0, animate = false)
            latest != null -> controller.moveTo(latest!!.lat, latest!!.lng, 16.0, animate = false)
            else -> gotoCurrent()
        }
    }

    Box(Modifier.fillMaxSize().background(Dot.Ink)) {
        TraceMap(
            controller = controller,
            points = emptyList(),
            dots = emptyList(),
            moves = emptyList(),
            modeOfPoint = emptyMap(),
            selectedTrip = null,
            selected = null,
            styleName = settings.mapStyle,
            me = null,
            onPointClick = {},
            onReady = { mapReady = true },
            modifier = Modifier.fillMaxSize(),
            places = places.filter { it.id != existing?.id },
            onCameraIdle = { center = it },
        )

        // 가운데 핀 (핀 끝이 지도 가운데를 가리킴)
        Icon(
            Icons.Rounded.Place,
            contentDescription = null,
            tint = Dot.Coral,
            modifier = Modifier.align(Alignment.Center).size(48.dp).offset(y = (-22).dp),
        )

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(onClick = onBack, shape = CircleShape, color = Dot.Surface.copy(alpha = 0.94f), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = Dot.Text) }
            }
            Spacer(Modifier.width(10.dp))
            // 주소로 찾기: 정확한 주소나 건물 이름을 입력하면 그 위치로 이동
            Surface(shape = RoundedCornerShape(50), color = Dot.Surface.copy(alpha = 0.97f), modifier = Modifier.weight(1f).height(48.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("주소·건물 이름으로 찾기", style = MaterialTheme.typography.bodyMedium, color = Dot.Faint)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Dot.Text),
                            cursorBrush = SolidColor(Dot.Lime),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(onClick = ::search) {
                        if (searching) CircularProgressIndicator(Modifier.size(20.dp), color = Dot.Lime, strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Search, "찾기", tint = Dot.Text)
                    }
                }
            }
        }

        // 주소 찾기 결과
        if (results.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Dot.Surface.copy(alpha = 0.98f),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 72.dp),
            ) {
                Column {
                    results.forEachIndexed { i, a ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Dot.Line))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                controller.moveTo(a.latitude, a.longitude, 17.0)
                                if (kind == PlaceEntity.FAV && name.isBlank()) name = a.featureName?.takeIf { f -> !f.all { it.isDigit() || it == '-' } } ?: ""
                                results = emptyList()
                                focus.clearFocus()
                            }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Place, null, tint = Dot.Muted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                com.dot.trace.data.Geo.line(a) ?: query,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Dot.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(onClick = ::gotoCurrent, shape = CircleShape, color = Dot.Surface.copy(alpha = 0.94f), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (locating) CircularProgressIndicator(Modifier.size(22.dp), color = Dot.Lime, strokeWidth = 2.5.dp)
                    else Icon(Icons.Rounded.MyLocation, "현재 위치로", tint = Dot.Text)
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = Dot.Surface.copy(alpha = 0.97f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "지도를 움직여 빨간 핀을 맞추거나, 위에서 주소·건물 이름으로 찾으세요",
                        style = MaterialTheme.typography.bodySmall,
                        color = Dot.Muted,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(20) },
                        label = { Text("이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Dot.Lime,
                            unfocusedBorderColor = Dot.Line,
                            focusedLabelColor = Dot.Lime,
                            cursorColor = Dot.Lime,
                        ),
                    )
                    val c = center
                    Text(
                        c?.let { rememberAddress(it.latitude, it.longitude) } ?: "위치를 고르는 중…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Dot.Muted,
                        maxLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (existing != null) {
                            OutlinedButton(
                                onClick = { existing?.let { vm.deletePlace(it.id) }; onBack() },
                                modifier = Modifier.height(52.dp),
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Dot.Coral),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Dot.Coral),
                            ) { Text("삭제") }
                        }
                        Button(
                            onClick = {
                                val target = controller.center() ?: return@Button
                                vm.savePlace(
                                    PlaceEntity(
                                        id = existing?.id ?: 0,
                                        kind = kind,
                                        name = name.trim().ifBlank { defaultName(kind) },
                                        lat = target.latitude,
                                        lng = target.longitude,
                                        radius = existing?.radius ?: 150f,
                                    )
                                )
                                Toast.makeText(context, "저장했어요", Toast.LENGTH_SHORT).show()
                                onBack()
                            },
                            enabled = mapReady,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = Dot.Lime, contentColor = Dot.OnLime),
                        ) { Text("이 위치로 저장", style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
private fun currentFix(context: android.content.Context) =
    com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)

private fun defaultName(kind: String) = when (kind) {
    PlaceEntity.HOME -> "집"
    PlaceEntity.WORK -> "회사"
    else -> ""
}
