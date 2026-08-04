package com.kbap.api.community

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.Page
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.domain.LanguageCode
import com.kbap.common.util.CursorParser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1)
class CommunityController(
    private val communityService: CommunityService,
) : CommunityApi {
    @PostMapping("/community/posts")
    override fun create(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: CommunityCreateRequest,
    ): ResponseEntity<BaseResponse<CommunityPostingResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                communityService.createPosting(
                    memberId = memberId,
                    content = request.content!!,
                    imagePaths = request.imagePaths,
                    foodIds = request.foodIds,
                ),
            ),
        )

    @PutMapping("/community/posts/{postId}")
    override fun update(
        @AuthMemberId memberId: Long,
        @PathVariable postId: Long,
        @Valid @RequestBody request: CommunityUpdateRequest,
    ): ResponseEntity<BaseResponse<CommunityPostingResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                communityService.updatePosting(
                    memberId = memberId,
                    postId = postId,
                    content = request.content!!,
                    imagePaths = request.imagePaths,
                    foodIds = request.foodIds,
                ),
            ),
        )

    @DeleteMapping("/community/posts/{postId}")
    override fun remove(
        @AuthMemberId memberId: Long,
        @PathVariable postId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        communityService.deletePosting(memberId, postId)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @GetMapping("/community/posts")
    override fun getPostingPage(
        @AuthMemberIdOrNull memberId: Long?,
        @Valid @ModelAttribute request: CommunityPostingListRequest,
    ): ResponseEntity<BaseResponse<Page<CommunityPostingItemResponse>>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                communityService.getPostingPage(
                    viewerMemberId = memberId,
                    cursor = CursorParser.parse(request.cursor),
                    lang = LanguageCode.from(request.lang),
                ),
            ),
        )

    @GetMapping("/community/posts/{postId}")
    override fun getPosting(
        @AuthMemberIdOrNull memberId: Long?,
        @PathVariable postId: Long,
        @Valid @ModelAttribute request: CommunityPostingDetailRequest,
    ): ResponseEntity<BaseResponse<CommunityPostingItemResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(communityService.getPosting(postId, LanguageCode.from(request.lang))),
        )
}
