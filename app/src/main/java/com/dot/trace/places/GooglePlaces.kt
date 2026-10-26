package com.dot.trace.places

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dot.trace.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Google 장소 정보에서 찾은 이름 */
data class FoundPlace(val id: String, val name: String, val lat: Double, val lng: Double)

/**
 * Google Places API (New) — 머문 곳 근처의 상호·건물 이름 찾기 (Nearby Search).
 * 보내는 것: 머문 곳 한 곳의 좌표뿐. 이동 경로나 다른 기록은 보내지 않는다.
 * API 키는 Android 앱(패키지 + 서명 SHA-1)으로 제한되어 있으므로 두 값을 헤더로 함께 보낸다.
 */
object GooglePlaces {

    val available: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

    /** 이름으로 쓰기에 알맞지 않은 종류 (도로, 행정구역 등) */
    private val SKIP_TYPES = setOf(
        "route", "street_address", "locality", "sublocality", "sublocality_level_1", "sublocality_level_2",
        "political", "administrative_area_level_1", "administrative_area_level_2", "country", "postal_code",
        "neighborhood", "plus_code", "geocode",
    )

    @Volatile private var certCache: String? = null

    /** 가장 가까운 장소 하나. 못 찾거나 실패하면 null (실패 이유는 [lastError]) */
    @Volatile var lastError: String? = null
        private set

    suspend fun nearest(context: Context, lat: Double, lng: Double, radiusM: Double = 70.0): FoundPlace? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        runCatching {
            val body = JSONObject()
                .put("maxResultCount", 5)
                .put("rankPreference", "DISTANCE")
                .put("languageCode", "ko")
                .put(
                    "locationRestriction",
                    JSONObject().put(
                        "circle",
                        JSONObject()
                            .put("center", JSONObject().put("latitude", lat).put("longitude", lng))
                            .put("radius", radiusM),
                    ),
                )
            val conn = (URL("https://places.googleapis.com/v1/places:searchNearby").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
                // 필요한 값만 요청 (요금이 낮은 항목)
                setRequestProperty("X-Goog-FieldMask", "places.id,places.displayName,places.location,places.types")
                setRequestProperty("X-Android-Package", context.packageName)
                signingSha1(context)?.let { setRequestProperty("X-Android-Cert", it) }
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                lastError = "HTTP $code ${text.take(200)}"
                return@runCatching null
            }
            lastError = null
            val arr = JSONObject(text).optJSONArray("places") ?: JSONArray()
            (0 until arr.length()).asSequence()
                .map { arr.getJSONObject(it) }
                .filter { p ->
                    val types = p.optJSONArray("types")
                    val list = if (types == null) emptyList() else (0 until types.length()).map { types.getString(it) }
                    list.none { it in SKIP_TYPES }
                }
                .mapNotNull { p ->
                    val name = p.optJSONObject("displayName")?.optString("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val loc = p.optJSONObject("location") ?: return@mapNotNull null
                    FoundPlace(p.optString("id"), name, loc.optDouble("latitude"), loc.optDouble("longitude"))
                }
                .firstOrNull()
        }.getOrElse { e ->
            lastError = e.message
            null
        }
    }

    /** 앱 서명 인증서 SHA-1 (콜론 없이 대문자 16진수) */
    @Suppress("DEPRECATION")
    private fun signingSha1(context: Context): String? {
        certCache?.let { return it }
        return runCatching {
            val pm = context.packageManager
            val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
            } ?: return null
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                .joinToString("") { "%02X".format(it) }
                .also { certCache = it }
        }.getOrNull()
    }
}
