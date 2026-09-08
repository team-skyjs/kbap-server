package com.kbap.api.notification

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "알림", description = "회원 알림함 API")
@SecurityRequirement(name = "bearerAuth")
interface NotificationInboxApi {
    @Operation(
        summary = "최근 7일 알림 목록",
        description = """
            회원 본인에게 온 알림 중 조회 시각 기준 최근 7일(168시간) 이내 것을 **전부**, 최신순으로 돌려준다.
            페이징·종류 필터가 없다. 7일이 지난 알림은 목록에서 사라진다.

            항목은 `id`·`title`·`body`·`receivedAt`(수신 시각, epoch 밀리초)·`read`(false = 새 알림)다.
            제목·본문은 발송 시점에 저장된 문자열 그대로다. 게스트는 401.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "최근 7일 알림 목록(없으면 빈 배열)"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
        ],
    )
    fun getRecentNotifications(memberId: Long): ResponseEntity<BaseResponse<List<NotificationResponse>>>

    @Operation(
        summary = "알림 읽음 처리",
        description = """
            본인 알림 1건을 읽음으로 바꾼다. 멱등이다 — 이미 읽은 알림은 최초 읽은 시각을 유지한 채 200 이다.
            읽음 취소는 없고, 7일이 지난 알림도 처리된다(목록에 안 보일 뿐).

            다른 회원의 알림·존재하지 않는 알림·삭제된 알림은 구분 없이 404 `NOTIFICATION-002` 다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "갱신된 알림(read = true)"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
            ApiResponse(responseCode = "404", description = "NOTIFICATION-002: 본인 알림이 아니거나 없음"),
        ],
    )
    fun markRead(memberId: Long, notificationId: Long): ResponseEntity<BaseResponse<NotificationResponse>>
}
