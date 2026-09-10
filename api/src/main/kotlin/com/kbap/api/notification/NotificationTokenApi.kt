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
            앱이 알림 권한을 얻어 토큰을 발급받을 때와 앱을 켤 때마다 호출한다. `X-Installation-Id`(앱 설치 UUID) 기준으로
            기기당 기록 하나를 유지한다 — 처음 보는 기기면 만들고, 이미 있으면 토큰·플랫폼·언어를 덮어쓴다(무효로 표시됐던
            토큰도 되살아난다). 같은 요청을 반복해도 결과가 같다(멱등).

            **게스트는 `Authorization` 없이, 회원은 `Bearer {accessToken}` 으로** 같은 요청을 보낸다. 회원 요청이면 그 기기가
            요청 회원에 연결되고, 게스트 요청은 기존 회원 연결을 건드리지 않는다. 위조·만료 토큰은 401.

            **게스트 K-Bap 소식 동의(`settings`, 선택)** — 게스트는 설정 화면이 없어 여기에 실어 보낸다. `marketing: true` 면
            `privacyConsentVersion`(마케팅 목적 개인정보 수집·이용 동의 문구 버전)과 `receiveConsentVersion`(광고성 정보 수신 동의
            문구 버전)이 모두 필수이며, 종류별로 같은 버전의 열린 동의가 있으면 변화 없고 다른 버전의 열린 동의는 철회한 뒤 새 동의를
            남긴다. `marketing: false` 면 그 기기의 열린 게스트 동의를 종류 불문 전부 철회한다(기록 보존).
            `settings` 를 생략하면 동의 원장을 건드리지 않는다. **회원 요청의 `settings` 는 무시한다**(회원 동의는 설정 API 가 정본).

            `X-API-Version: 1.0` 으로는 존재하지 않는 API 다(404).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "등록 또는 갱신 완료(페이로드 없음)"),
            ApiResponse(responseCode = "400", description = "X-Installation-Id 누락·공백·36자 초과, 본문 검증 실패(marketing=true 인데 버전 없음·양의 정수 아님 포함)"),
            ApiResponse(responseCode = "404", description = "X-API-Version 1.0 — 이 버전에는 존재하지 않는 API"),
            ApiResponse(responseCode = "401", description = "Authorization 이 있으나 위조·만료"),
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
