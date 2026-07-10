package com.meogo.app.api.auth

import com.meogo.app.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

@Tag(name = "인증", description = "소셜 로그인·토큰 재발급·로그아웃 API")
interface AuthApi {
    @Operation(
        summary = "소셜 로그인",
        description = """
            클라이언트가 Firebase SDK 로 구글·애플 로그인을 마치고 받은 ID 토큰을 제출하면,
            서버가 서명·발급자·수신자·만료를 검증하고 신원을 꺼내 로그인하거나 신규 가입시킨다.

            성공하면 서비스 자체 인증 토큰 두 개를 응답 본문으로 내려준다. 클라이언트는 안전한
            저장소(iOS Keychain·Android Keystore)에 보관했다가 매 요청에 `Authorization: Bearer {accessToken}`
            헤더로 보낸다. access 토큰이 만료(401)되면 refresh 토큰으로 재발급받는다.

            자체 토큰의 사용자 클레임은 회원 식별자 하나뿐이며 이메일 등 개인정보를 담지 않는다.
            응답의 `newMember` 가 true 면 온보딩 화면으로 분기한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "로그인·가입 성공 — 응답 본문에 access·refresh 토큰"),
            ApiResponse(responseCode = "400", description = "idToken 누락"),
            ApiResponse(responseCode = "401", description = "토큰 검증 실패(서명 불일치·만료·수신자 불일치) 또는 미지원 provider"),
        ],
    )
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): ResponseEntity<BaseResponse<LoginResponse>>

    @Operation(
        summary = "토큰 재발급",
        description = """
            refresh 토큰으로 access·refresh 토큰을 **모두 새로 발급**한다(rotation).
            이전 refresh 토큰은 즉시 폐기되므로 재사용하면 거절되고, 재발급할 때마다 refresh 수명이 연장된다.
            클라이언트는 응답의 두 토큰으로 저장소를 갱신해야 한다.

            refresh 토큰이 만료되었으면 서버 세션도 폐기된다(강제 로그아웃) — 클라이언트는 저장한 토큰을
            지우고 재로그인으로만 복귀한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "재발급 성공 — 응답 본문에 새 access·refresh 토큰"),
            ApiResponse(responseCode = "400", description = "refreshToken 누락"),
            ApiResponse(
                responseCode = "401",
                description = "refresh 조작·폐기(로그아웃·회전된 구 토큰) 또는 만료(강제 로그아웃 — 재로그인 필요)",
            ),
        ],
    )
    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): ResponseEntity<BaseResponse<TokenResponse>>

    @Operation(
        summary = "로그아웃",
        description = """
            서버에 저장된 refresh 세션을 폐기한다. 클라이언트는 저장한 토큰 두 개를 함께 삭제한다.
            이미 발급된 access 토큰은 짧은 수명이 다하면 자연 만료된다.
            refresh 토큰이 없거나 이미 폐기된 경우에도 성공으로 응답한다(멱등).
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "로그아웃 완료")])
    @PostMapping("/logout")
    fun logout(
        @RequestBody(required = false) request: LogoutRequest?,
    ): ResponseEntity<BaseResponse<Unit>>
}
