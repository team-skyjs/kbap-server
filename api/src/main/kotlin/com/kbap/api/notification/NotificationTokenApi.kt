package com.kbap.api.notification

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "알림", description = "기기 푸시 토큰 등록 API")
interface NotificationTokenApi {
    @Operation(
        summary = "푸시 토큰 등록·갱신 — X-API-Version 1.1 이상",
        description = """
            **회원 전용** — 로그인 후 호출한다. 앱이 알림 권한을 얻어 토큰을 발급받을 때와 앱을 켤 때마다 호출한다.
            `X-Installation-Id`(앱 설치 UUID) 기준으로 기기당 기록 하나를 유지한다 — 처음 보는 기기면 만들고, 이미 있으면
            토큰·플랫폼·언어를 덮어쓴다(무효로 표시됐던 토큰도 되살아난다). 기기는 항상 요청 회원에 연결된다.
            같은 요청을 반복해도 결과가 같다(멱등).

            광고성 수신 동의는 이 API 로 받지 않는다 — 회원 동의는 설정 API 가 정본이다.

            `X-API-Version: 1.0` 으로는 존재하지 않는 API 다(404).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "등록 또는 갱신 완료(페이로드 없음)"),
            ApiResponse(responseCode = "400", description = "X-Installation-Id 누락·공백·36자 초과, 본문 검증 실패"),
            ApiResponse(responseCode = "404", description = "X-API-Version 1.0 — 이 버전에는 존재하지 않는 API"),
            ApiResponse(responseCode = "401", description = "Authorization 없음·위조·만료"),
        ],
    )
    fun register(
        @Parameter(
            name = ApiHeaders.INSTALLATION_ID,
            `in` = ParameterIn.HEADER,
            description = "앱 설치 UUID(36자 이하). 재설치 후에도 유지되며 기기 기록의 유일 키다",
            required = true,
        )
        installationId: String,
        memberId: Long,
        request: NotificationTokenRegisterRequest,
    ): ResponseEntity<BaseResponse<Unit>>
}
