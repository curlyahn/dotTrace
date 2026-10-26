package com.dot.trace.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase {
    /** 기록 꺼짐 */
    Idle,
    /** 움직이는 중: 설정한 주기로 위치 기록 */
    Moving,
    /** 가만히 있음: GPS를 쉬고 15분에 한 번만 확인 (배터리 절약) */
    Still,
    /** 제때 위치가 오지 않음 (터널·엘리베이터 등): 10초마다 다시 시도 */
    Searching,
    /** 휴대폰 위치 설정이 꺼져 있음 */
    LocationOff,
}

data class TrackerStatus(
    val phase: Phase = Phase.Idle,
    /** 마지막으로 저장한 위치의 시각 (epoch ms) */
    val lastFixAt: Long? = null,
    /** 마지막으로 저장한 위치의 정확도 (m) */
    val lastAccuracy: Float? = null,
) {
    val running: Boolean get() = phase != Phase.Idle
}

/** 서비스와 화면이 함께 보는 현재 상태 */
object Tracker {
    private val _status = MutableStateFlow(TrackerStatus())
    val status: StateFlow<TrackerStatus> = _status.asStateFlow()

    internal fun update(transform: (TrackerStatus) -> TrackerStatus) {
        _status.value = transform(_status.value)
    }

    internal fun reset() {
        _status.value = TrackerStatus()
    }
}
