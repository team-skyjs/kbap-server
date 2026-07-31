package com.kbap.api.block

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.domain.block.MemberBlockService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/members/me/blocks")
class MemberBlockController(
    private val memberBlockService: MemberBlockService,
) : MemberBlockApi {
    @PostMapping
    override fun block(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: MemberBlockRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberBlockService.block(memberId, request.memberId!!)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @DeleteMapping("/{targetMemberId}")
    override fun unblock(
        @AuthMemberId memberId: Long,
        @PathVariable targetMemberId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberBlockService.unblock(memberId, targetMemberId)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
