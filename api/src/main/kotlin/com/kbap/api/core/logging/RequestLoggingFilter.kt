package com.kbap.api.core.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URLDecoder
import java.util.UUID

// 로그에 남길 때 값을 가릴 쿼리 파라미터명. 민감 파라미터가 생기면 여기에 등록한다.
internal val MASKED_QUERY_PARAMS: Set<String> = setOf("latitude", "longitude")

internal fun maskQuery(query: String?, maskedParams: Set<String>): String? {
    if (query.isNullOrBlank()) return null
    return query.split("&").joinToString("&") { param ->
        val name = param.substringBefore("=")
        if (name in maskedParams && param.contains("=")) "$name=***" else param
    }
}

class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // 클라이언트가 보낸 동명 헤더는 무시한다 — 상관 키의 단일 출처는 서버다.
        val requestId = UUID.randomUUID().toString()
        MDC.put(REQUEST_ID_KEY, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        putClientHeader(request, OS_VERSION_HEADER, OS_VERSION_KEY)
        putClientHeader(request, APP_VERSION_HEADER, APP_VERSION_KEY)

        val path = requestPath(request)
        val startedAt = System.nanoTime()
        log.info("--> {} {}", request.method, path)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // 예외로 빠져나가도(에러 응답 포함) 응답 로그는 요청당 정확히 1회 남는다.
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            log.atInfo()
                .addKeyValue("status", response.status)
                .addKeyValue("elapsedMs", elapsedMs)
                .log("<-- {} {} {} ({}ms)", response.status, request.method, path, elapsedMs)
            // 서블릿 스레드는 재사용되므로 정리하지 않으면 다음 요청 로그가 이전 요청 값으로 오염된다.
            MDC.clear()
        }
    }

    private fun putClientHeader(request: HttpServletRequest, header: String, mdcKey: String) {
        request.getHeader(header)?.takeIf { it.isNotBlank() }?.let { MDC.put(mdcKey, it.take(64)) }
    }

    private fun requestPath(request: HttpServletRequest): String {
        // 한글 검색어 등 퍼센트 인코딩을 풀어 사람이 읽게 남긴다. 깨진 인코딩이면
        // 로깅 실패가 요청 처리로 번지지 않게 인코딩된 원문 그대로 둔다.
        val query = maskQuery(request.queryString, MASKED_QUERY_PARAMS)
            ?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrDefault(it) }
        return if (query == null) request.requestURI else "${request.requestURI}?$query"
    }

    companion object {
        const val REQUEST_ID_KEY: String = "requestId"
        const val MEMBER_ID_KEY: String = "memberId"
        const val OS_VERSION_KEY: String = "osVersion"
        const val APP_VERSION_KEY: String = "appVersion"
        const val REQUEST_ID_HEADER: String = "X-Request-Id"
        const val OS_VERSION_HEADER: String = "X-OS-Version"
        const val APP_VERSION_HEADER: String = "X-App-Version"
    }
}
