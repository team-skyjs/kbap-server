package com.meogo.domain.scan

/**
 * 스캔 상태. 동기 mock 흐름은 즉시 [COMPLETED].
 * PROCESSING/PARTIAL/FAILED 는 실제 LLM(비동기·부분 실패) 도입 시를 위한 예약값(미사용).
 */
enum class ScanStatus {
    COMPLETED,
}
