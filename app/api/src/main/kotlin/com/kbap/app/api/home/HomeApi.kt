package com.kbap.app.api.home

import com.kbap.app.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(name = "홈", description = "홈 화면 조회 API")
@SecurityRequirement(name = "bearerAuth")
interface HomeApi {
    @Operation(
        summary = "홈 화면 조회",
        description = """
            홈 진입 시 필요한 세 섹션을 한 번에 내려준다 — 회원이 설정한 기피 성분, 인기 음식 5개, 최근 스캔한 메뉴 10개.

            ## 인증 (선택)
            `Authorization: Bearer {accessToken}` 는 **선택**이다.
            - 헤더가 없으면 **비회원**으로 처리한다 — `authenticated=false` 이고 개인화 섹션(`avoidedSubstances`·`recentScans`)은
              빈 배열, 인기 음식만 내려간다. 클라이언트는 `authenticated` 로 개인화 영역을 가입 유도(블러·모자이크) 처리한다.
            - 헤더가 있는데 토큰이 위조·만료면 비회원으로 폴백하지 않고 **401** 로 거절한다.

            모든 배열 필드는 **null 을 내려주지 않는다** — 값이 없으면 빈 배열이다(클라이언트 null 분기 불필요).

            ## 언어
            표시 언어는 **요청 파라미터 `lang` 만으로** 정해진다. 회원 프로필의 앱 언어는 참조하지 않으므로,
            같은 `lang` 이면 회원과 비회원의 응답 언어가 같다.

            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. `lang` 은 **필수**이며
            누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다.

            ## 각 섹션
            - **기피 성분**: 회원 프로필에 저장된 회피 성분(코드 + 지역화 성분명). 설정한 성분이 없으면 빈 배열.
            - **인기 음식**: 인기도 지표가 쌓이기 전까지 완성(READY) 음식 카탈로그에서 무작위 5개를 내려준다.
              응답 형태는 메뉴 목록·검색과 동일하므로, 추후 실제 인기도 정렬로 바뀌어도 클라이언트는 수정할 필요가 없다.
            - **최근 스캔**: 스캔 시각 내림차순, 같은 메뉴는 가장 최근 1건만 남겨 중복 없이 최대 10개.
              음식 정보가 모두 채워진(READY) 메뉴만 포함된다. 이력이 없으면 빈 배열.

            위험도(`overallRiskStatus`)는 회원의 기피 성분 기준으로 계산된다(비회원은 기피 성분이 없어 강조되지 않는다).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 회원은 세 섹션, 비회원은 인기 음식만(개인화 섹션은 빈 배열)"),
            ApiResponse(responseCode = "400", description = "lang 누락·빈/공백"),
            ApiResponse(responseCode = "401", description = "인증 헤더는 있으나 토큰이 위조·만료됨"),
        ],
    )
    fun home(
        @ParameterObject request: HomeRequest,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<HomeResponse>>
}
