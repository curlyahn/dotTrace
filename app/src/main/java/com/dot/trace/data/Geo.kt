package com.dot.trace.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** 좌표 ↔ 주소, 내 장소 찾기 등 위치 도우미 */
object Geo {
    private val cache = ConcurrentHashMap<String, Address>()

    private fun key(lat: Double, lng: Double) = "%.4f,%.4f".format(Locale.US, lat, lng)

    fun cached(lat: Double, lng: Double): Address? = cache[key(lat, lng)]

    /** 좌표 → 주소 (휴대폰의 주소 변환 기능 사용). 한 번 찾은 주소는 기억해 둔다. */
    suspend fun address(context: Context, lat: Double, lng: Double): Address? {
        cache[key(lat, lng)]?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            val geocoder = Geocoder(context, Locale.KOREA)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine<Address?> { cont ->
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) { cont.resume(addresses.firstOrNull()) }
                            override fun onError(errorMessage: String?) { cont.resume(null) }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                }
            }.getOrNull()?.also { cache[key(lat, lng)] = it }
        }
    }

    /** 주소·건물 이름으로 찾기 (휴대폰의 주소 변환 기능 사용). 최대 5개 */
    suspend fun search(context: Context, query: String): List<Address> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent() || query.isBlank()) return@withContext emptyList()
        val geocoder = Geocoder(context, Locale.KOREA)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { cont ->
                    geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { cont.resume(addresses.toList()) }
                        override fun onError(errorMessage: String?) { cont.resume(emptyList()) }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 5) ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** 한 줄 주소 ('대한민국' 빼고) */
    fun line(a: Address): String? = a.getAddressLine(0)?.removePrefix("대한민국")?.trim()?.takeIf { it.isNotBlank() }

    /** 시·구 단위 지역 이름. 예) 서울 강남구, 경기 수원시 영통구 */
    fun region(a: Address): String? {
        val admin = a.adminArea?.let { shortAdmin(it) }
        val parts = listOfNotNull(admin, a.locality?.takeIf { it != a.adminArea }, a.subLocality).distinct()
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun shortAdmin(s: String): String = when {
        s.endsWith("특별자치도") -> s.removeSuffix("특별자치도")
        s.endsWith("특별자치시") -> s.removeSuffix("특별자치시")
        s.endsWith("특별시") -> s.removeSuffix("특별시")
        s.endsWith("광역시") -> s.removeSuffix("광역시")
        s == "경기도" || s == "강원도" || s == "충청북도" || s == "충청남도" || s == "전라북도" || s == "전라남도" ||
            s == "경상북도" || s == "경상남도" -> mapOf(
            "경기도" to "경기", "강원도" to "강원", "충청북도" to "충북", "충청남도" to "충남",
            "전라북도" to "전북", "전라남도" to "전남", "경상북도" to "경북", "경상남도" to "경남",
        )[s]!!
        else -> s
    }

    fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, r)
        return r[0]
    }
}

/** 이 좌표가 등록한 장소 안이면 그 장소 (가장 가까운 것) */
fun List<PlaceEntity>.match(lat: Double, lng: Double): PlaceEntity? =
    map { it to Geo.distance(lat, lng, it.lat, it.lng) }
        .filter { (p, d) -> d <= p.radius }
        .minByOrNull { it.second }?.first

/** 화면에서 쓰는 주소 한 줄 */
@Composable
fun rememberAddress(lat: Double, lng: Double): String? {
    val context = LocalContext.current
    val state by produceState(initialValue = Geo.cached(lat, lng)?.let { Geo.line(it) }, lat, lng) {
        if (value == null) value = Geo.address(context, lat, lng)?.let { Geo.line(it) }
    }
    return state
}
