package com.kbap.core.error

// payload: 클라이언트가 후속 동작에 쓸 구조화 데이터(예: 정제된 검색어) — 응답 payload 필드로 그대로 내려간다.
open class BusinessException(
    val errorCode: ErrorCode,
    val payload: Any? = null,
) : RuntimeException(errorCode.message)
