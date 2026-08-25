package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.AdminMemberFilter
import com.kbap.common.domain.member.AdminMemberSort
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping(ApiPaths.ADMIN + "/members", version = "1.0+")
class AdminMemberController(
    private val queryService: AdminMemberQueryService,
    private val commandService: AdminMemberCommandService,
) : AdminMemberApi {
    @GetMapping
    override fun getMembers(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) provider: SocialProvider?,
        @RequestParam(required = false) memberStatus: MemberStatus?,
        @RequestParam(required = false) onboardingCompleted: Boolean?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) createdFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) createdTo: LocalDate?,
        @RequestParam(defaultValue = "false") includeWithdrawn: Boolean,
        @RequestParam(defaultValue = "id,desc") sort: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<BaseResponse<AdminMemberListResponse>> {
        val (sortField, descending) = parseSort(sort)
        val filter = AdminMemberFilter(q, email, provider, memberStatus, onboardingCompleted, createdFrom, createdTo, includeWithdrawn, sortField, descending)
        return ResponseEntity.ok(BaseResponse.ok(queryService.getMemberPage(filter, AdminPaging.page(page), AdminPaging.size(size))))
    }

    @GetMapping("/{id}")
    override fun getMember(@PathVariable id: Long): ResponseEntity<BaseResponse<AdminMemberDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(queryService.getMemberDetail(id)))

    @GetMapping("/{id}/ranking-events")
    override fun getRankingEvents(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminRankingEventPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(queryService.getRankingEventPage(id, AdminPaging.page(page), AdminPaging.size(size))))

    @PatchMapping("/{id}/status")
    override fun changeStatus(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminMemberStatusRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminMemberActionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(commandService.changeStatus(adminId, id, request.memberStatus!!, request.reason)))

    @PatchMapping("/{id}/profile")
    override fun resetProfile(
        @PathVariable id: Long,
        @RequestBody request: AdminMemberProfileResetRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminMemberActionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(commandService.resetProfile(adminId, id, request.resetNickname, request.resetProfileImage)))

    @PostMapping("/{id}/scan-unlock")
    override fun unlockScan(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminMemberActionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(commandService.unlockScan(adminId, id)))

    @DeleteMapping("/{id}")
    override fun withdraw(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminMemberActionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(commandService.withdraw(adminId, id)))

    private fun parseSort(sort: String): Pair<AdminMemberSort, Boolean> {
        val parts = sort.split(",")
        val field = when (parts[0].trim()) {
            "id" -> AdminMemberSort.ID
            "createdAt" -> AdminMemberSort.CREATED_AT
            "nickname" -> AdminMemberSort.NICKNAME
            else -> throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        val descending = when (parts.getOrNull(1)?.trim()?.lowercase() ?: "desc") {
            "desc" -> true
            "asc" -> false
            else -> throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        return field to descending
    }
}
