package com.kbap.api.member

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.AppVersion
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.domain.member.MemberService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/members")
class MemberController(
    private val memberService: MemberService,
) : MemberApi {
    private val profileAutoAssignSince = AppVersion(1, 1, 0)

    @PostMapping("/me/onboarding")
    override fun completeOnboarding(
        @AuthMemberId memberId: Long,
        @RequestHeader(value = "X-App-Version", required = false) appVersion: String?,
        @RequestBody request: OnboardingRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        val serverAssignsProfile =
            AppVersion.parseOrNull(appVersion)?.let { it >= profileAutoAssignSince } == true
        memberService.completeOnboarding(request.toInput(memberId, serverAssignsProfile))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @GetMapping("/me/profile")
    override fun getMyProfile(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<MyProfileResponse>> {
        val result = memberService.getMyProfile(memberId)
        return ResponseEntity.ok(BaseResponse.ok(MyProfileResponse.from(result)))
    }

    @GetMapping("/me/ranking")
    override fun getMyRanking(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<MemberRankingResponse>> {
        val result = memberService.getRanking(memberId)
        return ResponseEntity.ok(BaseResponse.ok(MemberRankingResponse.from(result)))
    }

    @PatchMapping("/me/profile")
    override fun updateProfile(
        @AuthMemberId memberId: Long,
        @RequestBody request: ProfileUpdateRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberService.updateProfile(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
