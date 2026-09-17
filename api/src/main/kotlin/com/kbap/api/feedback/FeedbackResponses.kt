package com.kbap.api.feedback

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "문의 접수 결과")
data class FeedbackCreateResponse(
    @field:Schema(description = "문의 id", example = "12")
    val id: Long,
    @field:Schema(description = "접수 직후 상태 — 항상 OPEN", example = "OPEN")
    val status: String,
    @field:Schema(description = "접수 시각")
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(result: CreateFeedbackResult) =
            FeedbackCreateResponse(id = result.id, status = result.status, createdAt = result.createdAt)
    }
}

@Schema(description = "내 문의 한 건 — 목록과 상세가 같은 모양을 쓴다")
data class MyFeedbackItemResponse(
    val id: Long,
    val content: String,
    @field:Schema(description = "첨부 사진 URL. 없으면 빈 배열")
    val imageUrls: List<String>,
    @field:Schema(description = "처리 상태", example = "ANSWERED", allowableValues = ["OPEN", "ANSWERED", "CLOSED"])
    val status: String,
    val createdAt: LocalDateTime,
    @field:Schema(description = "답변 목록. 오래된 순이며 답변자 정보는 내려주지 않는다")
    val replies: List<Reply>,
) {
    @Schema(description = "문의 답변")
    data class Reply(
        val id: Long,
        val content: String,
        val createdAt: LocalDateTime,
    )

    companion object {
        fun from(item: MyFeedbackPage.Item) = MyFeedbackItemResponse(
            id = item.id,
            content = item.content,
            imageUrls = item.imageUrls,
            status = item.status,
            createdAt = item.createdAt,
            replies = item.replies.map { Reply(id = it.id, content = it.content, createdAt = it.createdAt) },
        )
    }
}

@Schema(description = "내 문의 목록 — 이 기기(설치 ID) 또는 로그인한 회원의 문의")
data class MyFeedbackPageResponse(
    @field:Schema(description = "문의 목록. 최신순")
    val items: List<MyFeedbackItemResponse>,
    @field:Schema(description = "다음 페이지 커서. 없으면 null", nullable = true)
    val nextCursor: Long?,
) {
    companion object {
        fun from(page: MyFeedbackPage) = MyFeedbackPageResponse(
            items = page.items.map(MyFeedbackItemResponse::from),
            nextCursor = page.nextCursor,
        )
    }
}
