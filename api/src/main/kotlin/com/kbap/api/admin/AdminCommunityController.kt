package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/community", version = "1.0+")
class AdminCommunityController(
    private val adminCommunityService: AdminCommunityService,
) : AdminCommunityApi {
    @GetMapping("/posts")
    override fun getPosts(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminPostPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminCommunityService.getPostPage(q, memberId, AdminPaging.page(page), AdminPaging.size(size))))

    @GetMapping("/posts/{postId}/comments")
    override fun getComments(@PathVariable postId: Long): ResponseEntity<BaseResponse<AdminCommentTreeResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminCommunityService.getComments(postId)))

    @DeleteMapping("/posts/{postId}")
    override fun deletePost(@PathVariable postId: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminContentDeleteResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminCommunityService.deletePost(adminId, postId)))

    @DeleteMapping("/comments/{commentId}")
    override fun deleteComment(@PathVariable commentId: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminContentDeleteResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminCommunityService.deleteComment(adminId, commentId)))
}
