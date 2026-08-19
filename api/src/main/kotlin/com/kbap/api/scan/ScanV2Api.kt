package com.kbap.api.scan

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "메뉴 스캔 v2", description = "메뉴판 사진 스캔·판정 API — 서버 OCR. 경로 /api/scans + X-API-Version 2.0 이상")
@SecurityRequirement(name = "bearerAuth")
interface ScanV2Api {
    @Operation(
        summary = "메뉴판 사진 스캔 v2",
        description = """
            업로드 완료 검증을 마친 메뉴판 사진의 **경로만** 제출하면, 서버가 사진에서 직접 메뉴명·가격을 추출하고
            저장된 음식과 매칭해 항목별 위험도를 돌려준다. 클라이언트 OCR 을 보내지 않는다 — 기기 OCR 품질이
            결과에 영향을 주지 않는 것이 v1 과의 핵심 차이다.

            ## v1 과의 차이
            | | v1 (X-API-Version 미전송·1.x) | v2 (X-API-Version 2.0 이상) |
            |---|---|---|
            | 요청 | `imagePath` + `items`(클라이언트 OCR, 필수) | `imagePath` 만 |
            | 판독 근거 | 사진 + 클라이언트 OCR 병용 | **사진 단독** |
            | 응답 `idx` | 있음(OCR 항목 매칭 키) | **없음** — 클라이언트가 그릴 박스를 서버가 알지 못한다 |

            v1 은 구버전 앱 계약으로 동결한다. 신규 클라이언트는 이 API 를 쓴다.

            ## 흐름
            1. 서버 비전이 사진에서 메뉴별 표기 이름·표준 한국어 이름·가격을 추출한다(가격 축약 표기는 원 단위 정수로 복원).
            2. 표준 한국어명으로 저장된 음식을 조회한다. 처음 보는 음식이면 조사 대기 상태로 등록한다.
            3. 매칭에 실패한(미등록) 메뉴는 대체 없이 추출 결과 그대로(정제 한국어명·가격·riskLevel UNKNOWN) 내려간다 — v1 과 동일 원칙.
            4. 항목마다 음식 사진 URL(`imageRef`)이 함께 내려간다 — 매칭 음식은 대표 이미지, 비매칭·이미지 없는 음식은 디폴트 음식 이미지.

            메뉴판이 아닌 사진 등 추출 항목이 0개면 400(SCAN-003)으로 거절한다 — 재촬영 안내.

            ## 언어
            표시 언어는 **요청 파라미터 `lang` 만으로** 정해진다. 지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es.
            `lang` 은 **필수**이며 누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다.

            ## 통화
            응답의 `currency`(통화 환산 정보)는 **요청 파라미터 `currency` 만으로** 정해진다 — 회원 프로필 통화 설정을 읽지 않는다.
            `currency` 는 **필수**이며 누락은 400(COMMON-002), 지원 목록에 없는 값은 400(MEMBER-010)으로 거절하고
            스캔은 실행되지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "판정 성공 — 항목별 위험도·가격 반환"),
            ApiResponse(
                responseCode = "400",
                description = "요청 검증 실패(COMMON-002)·검증되지 않았거나 접근할 수 없는 이미지(SCAN-001)·메뉴판으로 인식되지 않는 사진(SCAN-003 — 재촬영 안내)·지원하지 않는 통화 코드(MEMBER-010)",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "무료 스캔 3회 소진·리뷰 미작성(SCAN-004) — 리뷰 작성 시 무제한 해제 안내로 분기"),
            ApiResponse(responseCode = "409", description = "같은 requestId 의 스캔이 이미 처리 중(SCAN-005) — 재시도 중복"),
            ApiResponse(responseCode = "503", description = "메뉴판 인식 실패(SCAN-002) — 잠시 후 재시도"),
        ],
    )
    fun scan(
        memberId: Long,
        @ParameterObject langRequest: ScanLangRequest,
        @Parameter(
            description = "환산 통화의 ISO 4217 코드(예: USD·JPY). 필수 — 회원 프로필 통화 설정과 무관하게 " +
                "이 값으로 환산 정보를 응답한다. 누락은 400(COMMON-002), 지원 목록에 없는 값은 400(MEMBER-010).",
            example = "USD",
            required = true,
        )
        currency: String,
        @SwaggerRequestBody(required = true, content = [Content(schema = Schema(implementation = ScanV2Request::class))])
        request: ScanV2Request,
    ): ResponseEntity<BaseResponse<ScanV2Response>>
}
