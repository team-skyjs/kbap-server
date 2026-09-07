package com.kbap.api.notification

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "알림", description = "회원 알림 설정 API")
@SecurityRequirement(name = "bearerAuth")
interface NotificationSettingApi {
    @Operation(
        summary = "알림 설정 조회",
        description = """
            회원의 알림 설정을 서버 정본으로 돌려준다. 두 그룹이다.

            **활동 푸시** — `activity`: 활동/소식(리뷰 도움됨·리뷰 작성 리마인더) 토글 하나. iOS/Android 알림 정책대로 기본 `false`.

            **K-Bap에서 보내는 소식(광고성 푸시)** — `news.enabled` 는 저장값이 아니라 「마케팅 목적 개인정보 수집·이용 동의」와
            「광고성 정보 수신 동의」가 **모두 유효한지**로 계산한다. `news.mealTime`(식사 시간 알림, 점심·저녁 넛지)은
            `enabled` 가 false 면 저장값과 무관하게 false 다. `privacyConsent`·`receiveConsent` 는 종류별 열린 최신 동의의
            `{version, grantedAt}` 이고 없으면 null.

            조회는 설정 기록을 만들지 않는다. 게스트는 401. 신규 API 라 `X-API-Version: 1.0` 부터 동작한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "현재 설정"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
        ],
    )
    fun getSettings(memberId: Long): ResponseEntity<BaseResponse<NotificationSettingsResponse>>

    @Operation(
        summary = "알림 설정 부분 수정",
        description = """
            보낸 필드만 반영하고 나머지는 유지한다. 응답은 조회와 같은 전체 설정이며 빈 본문은 무변화 200 이다.

            처리 순서는 `activity` → `news.enabled` → `news.mealTime` 이다.

            **`news.enabled: true`(켜기)** — `privacyConsentVersion`·`receiveConsentVersion` 이 **모두 필수**(400 COMMON-002).
            종류별로 같은 버전의 열린 동의가 있으면 무변화, 다른 버전이면 그 동의를 철회한 뒤 새 동의를 남긴다(재동의·문구 개정).
            동의 시각은 서버가 찍고 클라이언트 값은 무시한다. 식사 시간 알림은 저장값(처음이면 꺼짐)이 그대로 복원된다 — 켜기는 사용자가 직접 한다.

            **`news.enabled: false`(끄기)** — 두 종류의 열린 동의를 전부 철회한다(행 보존). 식사 시간 알림 저장값은 남는다.

            **`news.mealTime: true`** — 소식이 꺼져 있으면(동의 미완) 400 `NOTIFICATION-001`. `false` 는 항상 허용.

            `X-Installation-Id` 를 실으면 동의 기록에 동의 받은 기기로 남는다(선택).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 후 전체 설정"),
            ApiResponse(responseCode = "400", description = "COMMON-002: 켜기인데 두 버전 중 누락·양의 정수 아님 / NOTIFICATION-001: 동의 없이 식사 시간 알림 켜기"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
        ],
    )
    fun updateSettings(
        memberId: Long,
        @Parameter(
            name = ApiHeaders.INSTALLATION_ID,
            `in` = ParameterIn.HEADER,
            description = "앱 설치 UUID(선택). 켜기 시 동의 기록에 동의 받은 기기로 남는다",
            required = false,
        )
        installationId: String?,
        request: NotificationSettingsUpdateRequest,
    ): ResponseEntity<BaseResponse<NotificationSettingsResponse>>
}
