package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.scan.ScanUsageJpaRepository
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class AdminScanCountryService(private val usages: ScanUsageJpaRepository) {
    @Transactional(readOnly = true)
    fun getScanCountries(days: Int?, excludeMemberIds: List<Long>): AdminScanCountryReport {
        if (days != null && days !in ALLOWED_DAYS) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val today = LocalDate.now(SEOUL)
        val from = days?.let { today.minusDays(it - 1L) }
        val since = from?.atStartOfDay(SEOUL)?.withZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime() ?: LocalDateTime.of(1970, 1, 1, 0, 0)
        val rows = usages.countScansByCountrySince(since, UploadPurpose.MENU_SCAN.segment, excludeMemberIds.ifEmpty { listOf(NO_MEMBER_ID) })
            .map { AdminScanCountryRow(it.countryCode, it.menuCount, it.packagedCount, it.menuCount + it.packagedCount) }
            .sortedWith(compareByDescending<AdminScanCountryRow> { it.total }.thenBy(nullsLast()) { it.countryCode })
        return AdminScanCountryReport(
            from = from,
            to = today,
            rows = rows,
            totals = AdminScanCountTotals(rows.sumOf { it.menuCount }, rows.sumOf { it.packagedCount }, rows.sumOf { it.total }),
        )
    }

    companion object {
        private val ALLOWED_DAYS = setOf(7, 30)
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private const val NO_MEMBER_ID = 0L
    }
}

@Schema(description = "국가별 스캔 횟수 — 기간은 KST 자정 기준(from 일 00:00 KST 부터 지금까지). from=null 이면 전체 기간")
data class AdminScanCountryReport(
    @field:Schema(nullable = true, example = "2026-10-04")
    val from: LocalDate?,
    @field:Schema(example = "2026-11-02")
    val to: LocalDate,
    @field:Schema(description = "total 내림차순(같으면 국가 코드순, 미설정은 맨 뒤) — 국가 묶음 없이 전부")
    val rows: List<AdminScanCountryRow>,
    val totals: AdminScanCountTotals,
)

@Schema(description = "한 국가(회원 country_code)의 스캔 횟수")
data class AdminScanCountryRow(
    @field:Schema(description = "회원 국가 코드 — 미설정이면 null", nullable = true, example = "US")
    val countryCode: String?,
    @field:Schema(description = "메뉴판 스캔 횟수 — 묶음 스캔 성공 1건 + 묶음에 안 묶인 단일 메뉴판 스캔 사진 1장")
    val menuCount: Long,
    @field:Schema(description = "가공식품 스캔 횟수 — 이름 스캔 확정 + 성분표 분석 성공 각 1회")
    val packagedCount: Long,
    val total: Long,
)

data class AdminScanCountTotals(val menuCount: Long, val packagedCount: Long, val total: Long)
