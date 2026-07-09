package com.meogo.app.api.scan

import com.meogo.app.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "메뉴 스캔", description = "메뉴판 스캔 제출·판정 API")
interface MenuScanApi {
    @Operation(
        summary = "메뉴 스캔 제출",
        description = """
            스캔으로 인식한 메뉴 항목(itemId·메뉴명)들을 제출하면, 서버가 메뉴명을 정제해 저장된 음식과 매칭하고 항목별 위험도를 돌려준다.
            스캔 내역은 저장하지 않으며, 요청당 판정 결과만 응답한다.

            ## 정제·매칭 흐름
            1. 수신한 원문 메뉴명에서 한글만 남겨 매칭 키를 만든다(로마자 음역·가격·기호 제거). 한글이 하나도 없으면 비음식으로 처리한다.
            2. 정제 서비스로 표준 한국어 메뉴명을 추출하거나 "음식 아님"을 판정한다.
            3. 표준명으로 저장된 음식을 조회한다. 처음 보는 음식이면 조사 대기 상태로 등록하고, 레시피·설명·번역이 채워지기 전까지는 일반 조회에 노출되지 않는다.

            ## 응답 matchStatus
            - `MATCHED` — 조회 가능한 음식과 매칭됨. `foodId` 가 있고 `riskLevel` 은 사용자 회피 성분 기준 판정값이다.
            - `PENDING` — 조사 대기 중인 메뉴. `riskLevel` 은 `UNKNOWN` 이다.
            - `NOT_FOOD` — 원산지·가격·UI 문구 등 메뉴가 아닌 텍스트. `foodId` 는 없고 `riskLevel` 은 `UNKNOWN` 이다.

            응답 results 는 요청 items 와 itemId 로 1:1 매칭된다. itemId 는 '순서'가 아니라 클라이언트가 각 메뉴에 부여하는 매칭용 식별자이며, 한 요청 안에서 유일해야 한다(중복 시 400).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "판정 성공 — itemId 로 매칭되는 항목별 정제·매칭·위험도 반환"),
            ApiResponse(
                responseCode = "400",
                description = "요청 검증 실패 — 필수값 누락, itemId 중복, rawMenuName blank, 항목 수 초과 등",
            ),
        ],
    )
    @PostMapping
    fun submit(
        @Valid
        @RequestBody
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = SubmitMenuScanRequest::class),
                    examples = [
                        ExampleObject(
                            name = "서로 다른 메뉴 4개",
                            description = "itemId 0~3 을 각 메뉴에 부여",
                            value = """
                                {
                                  "items": [
                                    {"itemId": 0, "rawMenuName": "된장찌개"},
                                    {"itemId": 1, "rawMenuName": "김치찌개 kimchi jjigae"},
                                    {"itemId": 2, "rawMenuName": "공기밥"},
                                    {"itemId": 3, "rawMenuName": "원산지 중국"}
                                  ]
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "같은 메뉴명, 다른 itemId",
                            description = "메뉴판에 '공기밥'이 두 번 등장 — itemId 로 구분되어 각각 별개 결과를 받는다",
                            value = """
                                {
                                  "items": [
                                    {"itemId": 0, "rawMenuName": "공기밥"},
                                    {"itemId": 1, "rawMenuName": "공기밥"}
                                  ]
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: SubmitMenuScanRequest,
    ): ResponseEntity<BaseResponse<SubmitMenuScanResponse>>
}
