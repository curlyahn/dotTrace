package com.dot.trace.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 블루투스로 짝지은 기기 (주소, 이름) */
data class BtDevice(val address: String, val name: String)

/**
 * 내 차 블루투스: 등록한 차와 연결되어 있는 동안은 자동차로 이동 중으로 본다.
 * 블루투스를 새로 검색하지 않고, 이미 짝지은 기기 목록과 연결·해제 알림만 쓴다 (전력 소모 없음).
 */
object CarBluetooth {

    /** 짝지은 기기 이름을 읽고 연결 알림을 받을 권한 (Android 12부터 필요) */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val permission: String? get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    /** 이 휴대폰과 짝지은 블루투스 기기 목록 */
    @SuppressLint("MissingPermission")
    fun bonded(context: Context): List<BtDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .map { BtDevice(it.address, it.name ?: it.address) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * 지금 등록한 차 중 하나와 연결되어 있는지 (오디오·통화 연결 기준).
     * 결과는 비동기로 [onResult]에 한 번 이상 전달된다.
     */
    @SuppressLint("MissingPermission")
    fun checkConnected(context: Context, cars: Set<String>, onResult: (Boolean) -> Unit) {
        if (cars.isEmpty() || !hasPermission(context)) return onResult(false)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return onResult(false)
        if (!adapter.isEnabled) return onResult(false)
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val on = runCatching { proxy.connectedDevices.any { it.address in cars } }.getOrDefault(false)
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        if (on) onResult(true)
                    }

                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            }
        }
    }
}
