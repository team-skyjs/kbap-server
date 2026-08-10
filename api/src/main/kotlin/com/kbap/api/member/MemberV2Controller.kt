package com.kbap.api.member

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.domain.member.MemberService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V2 + "/members")
class MemberV2Controller(
    private val memberService: MemberService,
) : MemberV2Api {
    @PatchMapping("/me/profile")
    override fun updateProfile(
        @AuthMemberId memberId: Long,
        @RequestBody request: ProfileUpdateV2Request,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberService.updateProfile(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
