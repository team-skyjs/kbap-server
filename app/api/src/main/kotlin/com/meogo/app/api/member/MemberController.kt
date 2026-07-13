package com.meogo.app.api.member

import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.auth.AuthMemberId
import com.meogo.application.member.MemberProfileUseCase
import com.meogo.application.member.MemberRankingUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/members")
class MemberController(
    private val memberProfileUseCase: MemberProfileUseCase,
    private val memberRankingUseCase: MemberRankingUseCase,
) : MemberApi {
    override fun completeOnboarding(
        @AuthMemberId memberId: Long,
        @RequestBody request: OnboardingRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberProfileUseCase.completeOnboarding(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    override fun getMyProfile(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<MyProfileResponse>> {
        val result = memberProfileUseCase.getMyProfile(memberId)
        return ResponseEntity.ok(BaseResponse.ok(MyProfileResponse.from(result)))
    }

    override fun getMyRanking(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<MemberRankingResponse>> {
        val result = memberRankingUseCase.getRanking(memberId)
        return ResponseEntity.ok(BaseResponse.ok(MemberRankingResponse.from(result)))
    }

    override fun updateProfile(
        @AuthMemberId memberId: Long,
        @RequestBody request: ProfileUpdateRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberProfileUseCase.update(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
