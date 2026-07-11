package com.meogo.app.api.member

import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.auth.AuthMemberId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

@Tag(name = "회원", description = "온보딩·프로필 API")
interface MemberApi {
    @Operation(
        summary = "온보딩 정보 제출",
        description = """
            로그인한 회원이 닉네임·기피 성분 코드 목록·국가·앱 언어를 제출하면, 각 값을 검증한 뒤
            프로필로 저장하고 온보딩을 완료 상태로 전이한다. 이미 완료한 회원의 재제출은 거절된다
            (프로필 재설정은 후속 기능). `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "온보딩 완료 — 프로필 저장·상태 전이"),
            ApiResponse(responseCode = "400", description = "입력 검증 실패(기피 성분·국가·언어·닉네임) 또는 이미 온보딩 완료"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
            ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음"),
        ],
    )
    @PostMapping("/me/onboarding")
    fun submitOnboarding(
        @AuthMemberId memberId: Long,
        @RequestBody request: OnboardingRequest,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "내 온보딩 프로필·상태 조회",
        description = """
            홈화면 진입 시 현재 회원의 온보딩 프로필(닉네임·기피 성분·국가·앱 언어)과 온보딩 완료 여부를
            조회한다. 클라이언트는 `onboardingCompleted` 로 온보딩 화면 분기를 판단한다.
            `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 프로필·온보딩 상태"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
            ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음"),
        ],
    )
    @GetMapping("/me")
    fun getMyProfile(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<MyProfileResponse>>
}
