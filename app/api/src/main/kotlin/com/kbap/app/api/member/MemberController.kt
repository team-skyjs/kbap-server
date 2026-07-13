package com.kbap.app.api.member

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberId
import com.kbap.application.member.MemberService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/members")
class MemberController(
    private val memberService: MemberService,
) : MemberApi {
    override fun completeOnboarding(
        @AuthMemberId memberId: Long,
        @RequestBody request: OnboardingRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberService.completeOnboarding(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    override fun getMyProfile(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<MyProfileResponse>> {
        val result = memberService.getMyProfile(memberId)
        return ResponseEntity.ok(BaseResponse.ok(MyProfileResponse.from(result)))
    }

    override fun getMyRanking(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<MemberRankingResponse>> {
        val result = memberService.getRanking(memberId)
        return ResponseEntity.ok(BaseResponse.ok(MemberRankingResponse.from(result)))
    }

    override fun updateProfile(
        @AuthMemberId memberId: Long,
        @RequestBody request: ProfileUpdateRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberService.updateProfile(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
