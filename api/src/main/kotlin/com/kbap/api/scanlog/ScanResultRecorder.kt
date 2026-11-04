package com.kbap.api.scanlog

import com.kbap.api.core.ApiHeaders
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Component
class ScanResultRecorder(private val writer: ScanResultLogWriter) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> record(memberId: Long, mode: ScanMode, imagePath: String?, summarize: (T) -> ScanResultLogEntry, scan: () -> T): T {
        val versions = requestVersions()
        val result = try {
            scan()
        } catch (e: Exception) {
            submit(memberId, mode, imagePath, versions) { ScanResultSummaries.failure(e) }
            throw e
        }
        submit(memberId, mode, imagePath, versions) { summarize(result) }
        return result
    }

    private fun submit(memberId: Long, mode: ScanMode, imagePath: String?, versions: Pair<String?, String?>, entry: () -> ScanResultLogEntry) {
        try {
            val scannedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)
            writer.write(ScanResultLogRow(memberId, mode, imagePath?.trim()?.trimStart('/')?.take(MAX_PATH_CHARS), versions.first, versions.second, entry(), scannedAt))
        } catch (e: Exception) {
            log.warn("스캔 결과 기록 실패 — 응답은 그대로 둔다 memberId={}, mode={}", memberId, mode, e)
        }
    }

    private fun requestVersions(): Pair<String?, String?> = runCatching {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        request?.getHeader(ApiHeaders.APP_VERSION)?.take(MAX_APP_VERSION_CHARS) to request?.getHeader(ApiHeaders.API_VERSION)?.take(MAX_API_VERSION_CHARS)
    }.getOrDefault(null to null)

    private companion object {
        const val MAX_PATH_CHARS = 512
        const val MAX_APP_VERSION_CHARS = 32
        const val MAX_API_VERSION_CHARS = 16
    }
}
