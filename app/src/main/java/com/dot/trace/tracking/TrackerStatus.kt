package com.dot.trace.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase {
    /** 기록 꺼짐 */
    Idle,
    /** 움직이는 중: 설정한 주기로 위치 기록 */
    Moving,
    /** 걷는 중: 20초마다 기록해 길을 따라 그린다 */
    Walking,
    /** 탈것으로 이동 중이거나 빠르게 이동 중(시속 30km 이상): 10초마다 기록해 경로를 부드럽게 */
    MovingFast,
    /** 가만히 있음: 위치 업데이트를 끄고 움직임 센서만 대기 (배터리 절약) */
    Still,
    /** 제때 위치가 오지 않음 (터널·엘리베이터 등): 10초부터 최대 15분 간격으로 다시 시도 */
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
    /** 재시도 중일 때 지금의 재시도 간격 (ms) */
    val retryIntervalMs: Long? = null,
    /** 등록한 내 차와 블루투스로 연결되어 있음 */
    val inCar: Boolean = false,
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
