package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "어드민 문의 목록")
data class AdminFeedbackPageResponse(
    val items: List<Item>,
    val page: Int,
    val size: Int,
    @field:Schema(description = "필터 조건에 맞는 전체 건수", example = "37")
    val totalCount: Long,
    @field:Schema(description = "전체 페이지 수", example = "2")
    val totalPages: Int,
) {
    @Schema(description = "문의 목록 행")
    data class Item(
        val id: Long,
        @field:Schema(description = "본문 앞 300자")
        val contentPreview: String,
        val hasImages: Boolean,
        @field:Schema(description = "신고 계약과 같은 규칙의 작성자 키 — 설치 ID 가 있으면 inst:{id}", example = "inst:6d3f")
        val reporterKey: String,
        @field:Schema(description = "회원 문의면 회원 id, 게스트면 null — 닉네임이 비어 있어도 회원 여부를 이 값으로 가른다", example = "18", nullable = true)
        val memberId: Long?,
        @field:Schema(description = "회원 문의면 닉네임, 게스트거나 닉네임 미설정이면 null", nullable = true)
        val memberNickname: String?,
        val status: String,
        val replyCount: Int,
        val createdAt: LocalDateTime,
        @field:Schema(description = "목록용 기기 요약 2키")
        val app: App,
    )

    @Schema(description = "기기 요약")
    data class App(
        val os: String?,
        val appVersion: String?,
    )

    companion object {
        fun from(result: AdminFeedbackPageResult) = AdminFeedbackPageResponse(
            items = result.items.map {
                Item(
                    id = it.id,
                    contentPreview = it.contentPreview,
                    hasImages = it.hasImages,
                    reporterKey = it.reporterKey,
                    memberId = it.memberId,
                    memberNickname = it.memberNickname,
                    status = it.status,
                    replyCount = it.replyCount,
                    createdAt = it.createdAt,
                    app = App(os = it.os, appVersion = it.appVersion),
                )
            },
            page = result.page,
            size = result.size,
            totalCount = result.totalCount,
            totalPages = result.totalPages,
        )
    }
}

@Schema(description = "어드민 문의 상세")
data class AdminFeedbackDetailResponse(
    val id: Long,
    val content: String,
    val imageUrls: List<String>,
    val status: String,
    val createdAt: LocalDateTime,
    val reporterKey: String,
    val memberId: Long?,
    val memberNickname: String?,
    val installationId: String,
    @field:Schema(description = "클라이언트가 보낸 기기 정보 전체. 보내지 않은 키는 없다")
    val deviceInfo: Map<String, String>,
    val serverMeta: ServerMeta,
    val replies: List<Reply>,
) {
    @Schema(description = "서버가 채운 메타")
    data class ServerMeta(
        val userAgent: String?,
        val receivedAt: LocalDateTime,
    )

    @Schema(description = "답변")
    data class Reply(
        val id: Long,
        val adminAccountId: Long,
        val content: String,
        val createdAt: LocalDateTime,
    )

    companion object {
        fun from(result: AdminFeedbackDetailResult) = AdminFeedbackDetailResponse(
            id = result.id,
            content = result.content,
            imageUrls = result.imageUrls,
            status = result.status,
            createdAt = result.createdAt,
            reporterKey = result.reporterKey,
            memberId = result.memberId,
            memberNickname = result.memberNickname,
            installationId = result.installationId,
            deviceInfo = result.deviceInfo,
            serverMeta = ServerMeta(userAgent = result.userAgent, receivedAt = result.receivedAt),
            replies = result.replies.map {
                Reply(id = it.id, adminAccountId = it.adminAccountId, content = it.content, createdAt = it.createdAt)
            },
        )
    }
}

@Schema(description = "답변 작성 결과")
data class AdminFeedbackReplyResponse(
    @field:Schema(description = "작성된 답변")
    val reply: Reply,
    @field:Schema(description = "답변 후 문의 상태 — OPEN 이었다면 ANSWERED 로 바뀐다", example = "ANSWERED")
    val status: String,
) {
    @Schema(description = "답변")
    data class Reply(
        val id: Long,
        val adminAccountId: Long,
        val content: String,
        val createdAt: LocalDateTime,
    )

    companion object {
        fun from(result: AdminFeedbackReplyResult) = AdminFeedbackReplyResponse(
            reply = Reply(
                id = result.id,
                adminAccountId = result.adminAccountId,
                content = result.content,
                createdAt = result.createdAt,
            ),
            status = result.feedbackStatus,
        )
    }
}

@Schema(description = "상태 변경 결과")
data class AdminFeedbackStatusResponse(
    val id: Long,
    val status: String,
) {
    companion object {
        fun from(result: AdminFeedbackStatusResult) =
            AdminFeedbackStatusResponse(id = result.id, status = result.status)
    }
}
