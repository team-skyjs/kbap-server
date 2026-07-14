package com.kbap.app.api.member

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberId
import com.kbap.domain.member.MemberService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/members")
class MemberController(
    private val memberService: MemberService,
) : MemberApi {
    @PostMapping("/me/onboarding")
    override fun completeOnboarding(
        @AuthMemberId memberId: Long,
        @RequestBody request: OnboardingRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberService.completeOnboarding(request.toInput(memberId))
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
