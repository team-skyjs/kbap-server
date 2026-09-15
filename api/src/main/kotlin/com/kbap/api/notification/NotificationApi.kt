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

@Tag(name = "알림", description = "회원 알림 설정(기기 단위)·알림함 API")
@SecurityRequirement(name = "bearerAuth")
interface NotificationApi {
    @Operation(
        summary = "이 기기의 알림 설정 조회",
        description = """
            `X-Installation-Id`(앱 설치 UUID, 필수)가 가리키는 **이 기기**의 알림 설정을 서버 정본으로 돌려준다. 회원이 기기를
            여러 대 쓰면 기기마다 값이 다르다. 설정을 만진 적 없는 기기는 모든 토글이 `false` 이고 조회는 기록을 만들지 않는다.

            **활동 푸시** — `activity`: 활동 알림(리뷰 도움됨·리뷰 작성 리마인더) 토글. iOS/Android 알림 정책대로 기본 `false`.

            **K-Bap에서 보내는 소식** — `news.enabled` 는 **이 기기의 소식 토글 저장값**이다(동의와 결합하지 않는다). `news.mealTime`(식사 시간
            알림, 점심·저녁 넛지)은 이 기기 소식이 꺼져 있으면 저장값과 무관하게 `false`. **회원의 마케팅 수신 동의 상태는
            `privacyConsent`·`receiveConsent` 로 읽는다** — 종류별 열린 최신 동의의 `{version, grantedAt}`, 없으면 null. 둘 다 있으면 동의 ON 이다.

            발송은 "이 기기 소식 토글 ON **AND** 회원 동의 유효(두 종류 열림·문구 버전이 요구치 이상)" 을 검사하므로
            동의 없이 토글만 켜져 있어도 광고성 알림은 나가지 않는다.

            헤더 누락·공백·36자 초과는 400 `COMMON-002`. 게스트는 401. `X-API-Version: 1.0` 부터 동작한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "이 기기의 현재 설정"),
            ApiResponse(responseCode = "400", description = "COMMON-002: X-Installation-Id 누락·공백·36자 초과"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
        ],
    )
    fun getSettings(
        memberId: Long,
        @Parameter(
            name = ApiHeaders.INSTALLATION_ID,
            `in` = ParameterIn.HEADER,
            description = "앱 설치 UUID(필수) — 이 기기의 설정을 조회한다",
            required = true,
        )
        installationId: String,
    ): ResponseEntity<BaseResponse<NotificationSettingsResponse>>

    @Operation(
        summary = "이 기기의 알림 설정 부분 수정",
        description = """
            보낸 필드만 **이 기기**(`X-Installation-Id`, 필수)에 반영하고 나머지는 유지한다. 응답은 조회와 같은 전체 설정이며
            빈 본문은 무변화 200 이다. 이 기기 행이 없으면 첫 수정 때 기본값(전부 false)으로 만들어 반영한다.

            소식 그룹은 **기기 수신**과 **회원 동의**를 별개 항목으로 받는다. 처리 순서는 `activity` → `news.consent` → `news.enabled` → `news.mealTime`.

            **`news.consent: true`(마케팅 수신 동의 켜기, 회원 단위)** — `privacyConsentVersion`·`receiveConsentVersion` 이 **모두 필수**(400 COMMON-002).
            종류별로 같은 버전의 열린 동의가 있으면 무변화, 다른 버전이면 그 동의를 철회한 뒤 새 동의를 남긴다(재동의·문구 개정).
            동의 시각은 서버가 찍고 클라이언트 값은 무시한다. 동의 받은 기기로 이 기기 id 가 기록된다. **기기 토글값은 바꾸지 않는다.**

            **`news.consent: false`(동의 철회, 회원 단위)** — 회원의 열린 동의 두 종류를 전부 철회한다(행 보존, 철회 시각 기록). 어느 기기에서
            보내든 같다. **기기 토글값은 바꾸지 않는다** — 소식 토글은 켜진 채 남고 발송만 멈춘다. 동의가 없으면 무변화 200.

            **`news.enabled`(이 기기 소식 수신)** — 이 기기의 소식 토글만 켜고 끈다. **동의 원장을 건드리지 않는다.** 버전 불필요.
            동의 없이 켜도 저장된다(발송은 동의를 검사).

            **`news.mealTime: true`** — (이 요청의 `enabled` 반영 후) 이 기기 소식이 꺼져 있으면 400 `NOTIFICATION-001`. 동의 유무는 조건이 아니다. `false` 는 항상 허용.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 후 이 기기의 전체 설정"),
            ApiResponse(responseCode = "400", description = "COMMON-002: X-Installation-Id 누락·공백·36자 초과 / consent true 인데 두 버전 중 누락·양의 정수 아님 / NOTIFICATION-001: 이 기기 소식이 꺼진 채 식사 시간 알림 켜기"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
        ],
    )
    fun updateSettings(
        memberId: Long,
        @Parameter(
            name = ApiHeaders.INSTALLATION_ID,
            `in` = ParameterIn.HEADER,
            description = "앱 설치 UUID(필수) — 이 기기의 설정을 수정한다. 동의 켜기 시 동의 받은 기기로 기록된다",
            required = true,
        )
        installationId: String,
        request: NotificationSettingsUpdateRequest,
    ): ResponseEntity<BaseResponse<NotificationSettingsResponse>>

    @Operation(
        summary = "최근 7일 알림 목록",
        description = """
            회원 본인의 **요청 기기(`X-Installation-Id`, 필수)** 에 온 알림 중 조회 시각 기준 최근 7일(168시간) 이내 것을
            **전부**, 최신순으로 돌려준다. 페이징·종류 필터가 없다. 7일이 지난 알림은 목록에서 사라진다.

            알림함은 기기 단위다 — 알림 행은 발송 시점에 기기마다 그 기기 언어로 하나씩 저장되므로, 같은 회원이라도
            다른 기기의 알림·읽음 상태는 보이지 않는다. 헤더가 없으면 400.

            항목은 `id`·`title`·`body`·`receivedAt`(수신 시각, epoch 밀리초)·`read`(false = 새 알림)다.
            제목·본문은 발송 시점에 저장된 문자열 그대로다. 게스트는 401.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "최근 7일 알림 목록(없으면 빈 배열)"),
            ApiResponse(responseCode = "400", description = "X-Installation-Id 누락·형식 오류"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
        ],
    )
    fun getRecentNotifications(
        memberId: Long,
        @Parameter(
            name = ApiHeaders.INSTALLATION_ID,
            `in` = ParameterIn.HEADER,
            description = "앱 설치 UUID(필수) — 이 기기의 알림함을 조회한다",
            required = true,
        )
        installationId: String,
    ): ResponseEntity<BaseResponse<List<NotificationResponse>>>

    @Operation(
        summary = "알림 읽음 처리",
        description = """
            요청 기기(`X-Installation-Id`, 필수)의 본인 알림 1건을 읽음으로 바꾼다. 멱등이다 — 이미 읽은 알림은
            최초 읽은 시각을 유지한 채 200 이다. 읽음 취소는 없고, 7일이 지난 알림도 처리된다(목록에 안 보일 뿐).

            다른 회원의 알림·같은 회원의 다른 기기 알림·존재하지 않는 알림·삭제된 알림은 구분 없이 404 `NOTIFICATION-002` 다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "갱신된 알림(read = true)"),
            ApiResponse(responseCode = "400", description = "X-Installation-Id 누락·형식 오류"),
            ApiResponse(responseCode = "401", description = "인증 없음·위조·만료"),
            ApiResponse(responseCode = "404", description = "NOTIFICATION-002: 이 기기의 본인 알림이 아니거나 없음"),
        ],
    )
    fun markRead(
        memberId: Long,
        @Parameter(
            name = ApiHeaders.INSTALLATION_ID,
            `in` = ParameterIn.HEADER,
            description = "앱 설치 UUID(필수) — 이 기기의 알림만 읽음 처리한다",
            required = true,
        )
        installationId: String,
        notificationId: Long,
    ): ResponseEntity<BaseResponse<NotificationResponse>>
}
