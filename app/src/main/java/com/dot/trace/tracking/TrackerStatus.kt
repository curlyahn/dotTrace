package com.dot.trace.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase {
    /** 기록 꺼짐 */
    Idle,
    /** GPS로 위치를 찾는 중 (터널·엘리베이터 등에서는 10초마다 다시 시도) */
    Searching,
    /** 위치를 저장했고 다음 기록 시각을 기다리는 중 */
    Waiting,
    /** 휴대폰 위치(GPS) 설정이 꺼져 있음 */
    GpsOff,
}

data class TrackerStatus(
    val phase: Phase = Phase.Idle,
    /** 이번 기록에서 GPS를 시도한 횟수 */
    val attempts: Int = 0,
    /** 마지막으로 저장한 위치의 시각 (epoch ms) */
    val lastFixAt: Long? = null,
    /** 다음 기록 예정 시각 (epoch ms) */
    val nextAt: Long? = null,
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
