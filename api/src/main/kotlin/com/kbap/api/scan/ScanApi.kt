package com.kbap.api.scan

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "메뉴 스캔", description = "메뉴판 사진 스캔·판정 API")
@SecurityRequirement(name = "bearerAuth")
interface ScanApi {
    @Operation(
        summary = "메뉴판 사진 스캔",
        description = """
            업로드 완료 검증을 마친 메뉴판 사진의 **경로**와 **클라이언트 자체 OCR 항목(idx+원문 텍스트)** 을 함께 제출하면,
            서버가 비전 인식으로 메뉴명·가격을 추출하고 각 메뉴를 클라이언트 OCR 항목의 idx 에 매칭해 돌려준다.
            저장된 음식과 매칭해 항목별 위험도도 함께 준다. 로그인 필수 API 로, 매칭된 완성(READY) 음식은 회원별 스캔 이력으로 기록한다.
            이미지는 전체 URL 이 아니라 오브젝트 경로만 넘긴다(CDN 도메인은 서버가 관리).

            ## 추출·매칭 흐름
            1. 비전 인식이 사진에서 메뉴별 표기 이름·표준 한국어 이름·가격을 추출한다(가격 축약 표기는 원 단위 정수로 복원).
            2. 각 추출 메뉴를 클라이언트 OCR 항목(idx)에 매칭한다 — 사진 속 위치·텍스트로 판단하며, 클라이언트 OCR 이 깨져도 매칭한다. 응답 결과는 **비전 추출 메뉴**가 기준이다.
            3. 메뉴가 아닌 텍스트(상호·전화번호·원산지 등)는 비전 인식 단계에서 제외되므로, 그런 OCR 항목은 결과에 매칭되지 않는다.
            4. 표준 한국어명으로 저장된 음식을 조회한다. 처음 보는 음식이면 조사 대기 상태로 등록하고, 레시피·설명·번역이 채워지기 전까지 일반 조회에 노출되지 않는다.

            ## 응답
            - `idx` — 이 추출 메뉴에 매칭된 **클라이언트 OCR 항목의 idx**. 클라이언트는 이 값으로 해당 메뉴 위에 박스를 그린다. 추출됐지만 대응 OCR 이 없으면 `null`.
            - `matched` — `true` 면 조회 가능한 음식과 매칭됨(`riskLevel` 은 회피 성분 기준 판정). `false` 면 조사 대기라 `riskLevel=UNKNOWN`.
            - `name` — 표시용 메뉴명. `matched=true` 면 요청 `lang` 의 번역명(번역 부재 시 한국어 표시명), `matched=false` 면 아직 번역본이 없으므로 비전이 정제한 한국어명. `koreanName` — 표시용 한국어명(띄어쓰기 등 원본 표기 보존, 매칭 음식이 있으면 그 음식의 표시명).
            - `price` — 원 단위 정수(미표기 시 null). 응답으로만 제공되며 가격은 스캔 이력에만 저장된다.

            메뉴판이 아닌 사진 등 추출 항목이 0개면 `results` 가 빈 배열인 정상 응답이다(실패 아님).

            ## 언어
            표시 언어는 **요청 파라미터 `lang` 만으로** 정해진다. 회원 프로필의 앱 언어는 참조하지 않는다.
            지원 언어: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. `lang` 은 **필수**이며
            누락·빈/공백은 400(COMMON-002), 지원 목록에 없는 코드는 en 으로 응답한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "판정 성공 — 매칭 idx·위험도·가격 반환"),
            ApiResponse(
                responseCode = "400",
                description = "요청 검증 실패(COMMON-002)·검증되지 않았거나 접근할 수 없는 이미지(SCAN-001)",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "503", description = "메뉴판 인식 실패(SCAN-002 — 잠시 후 재시도)·스캔 서버 일시 장애(SCAN-006 — 서버측 문제, 재시도 유도 모달 분기)"),
        ],
    )
    fun scan(
        memberId: Long,
        @ParameterObject langRequest: ScanLangRequest,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = ScanRequest::class),
                    examples = [
                        ExampleObject(
                            name = "한식마당 메뉴판 (노이즈 포함)",
                            description = "클라이언트 OCR 항목(idx+텍스트). 메뉴 사이에 상호·원산지·가격파편·영업안내 노이즈가 섞여 있다 " +
                                "— vision 추출 메뉴에 매칭되지 않는 노이즈는 결과에서 빠진다. '피개'는 사진 표기 그대로(찌개 깨짐).",
                            value = """
                                {
                                  "imagePath": "dev/images/scans/2026/07/1_550e8400-e29b-41d4-a716-446655440000.jpg",
                                  "items": [
                                    {"idx": 0, "rawMenuName": "김치피개"},
                                    {"idx": 1, "rawMenuName": "된장피개"},
                                    {"idx": 2, "rawMenuName": "부대피개"},
                                    {"idx": 3, "rawMenuName": "한식마당"},
                                    {"idx": 4, "rawMenuName": "동태피개"},
                                    {"idx": 5, "rawMenuName": "순두부피개"},
                                    {"idx": 6, "rawMenuName": "고추장피개"},
                                    {"idx": 7, "rawMenuName": "원산지 국내산"},
                                    {"idx": 8, "rawMenuName": "청국장"},
                                    {"idx": 9, "rawMenuName": "뚝배기불고기"},
                                    {"idx": 10, "rawMenuName": "오늘백반"},
                                    {"idx": 11, "rawMenuName": "오징어백반"},
                                    {"idx": 12, "rawMenuName": "7,000원"},
                                    {"idx": 13, "rawMenuName": "제육백반"},
                                    {"idx": 14, "rawMenuName": "낙지백반"},
                                    {"idx": 15, "rawMenuName": "우렁쌈밥"},
                                    {"idx": 16, "rawMenuName": "영업시간 11:00~21:00"}
                                  ]
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: ScanRequest,
    ): ResponseEntity<BaseResponse<ScanResponse>>
}
