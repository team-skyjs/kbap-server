package com.kbap.api.block

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.domain.block.MemberBlockService
import com.kbap.common.domain.member.MemberJpaRepository
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/members/me/blocks")
class MemberBlockController(
    private val memberBlockService: MemberBlockService,
    private val memberRepository: MemberJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
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

    @GetMapping
    override fun listBlockedMembers(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<List<BlockedMemberResponse>>> {
        val blockedIds = memberBlockService.getBlockedMemberIds(memberId)
        val members = memberRepository.findAllById(blockedIds)
        return ResponseEntity.ok(BaseResponse.ok(members.map { BlockedMemberResponse.from(it, imagePublicBaseUrl) }))
    }
}
