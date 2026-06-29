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
            스캔으로 인식한 메뉴 항목(itemId·메뉴명·boundingBox)들을 제출하면, 각 항목에 위험도(SAFE/CAUTION/DANGER/UNKNOWN)를 판정해 돌려준다.

            응답 results 는 요청 items 와 itemId 로 1:1 매칭된다. itemId 는 '순서'가 아니라 클라이언트가 각 메뉴에 부여하는 매칭용 식별자이며, 한 요청 안에서 유일해야 한다(중복 시 400). 따라서 메뉴명이 같아도 itemId 가 다르면 별개 항목으로 구분되고, 클라이언트는 응답의 itemId 로 자기 화면의 메뉴와 결과를 연결한다.

            ## boundingBox 정규화 방법
            boundingBox 는 스캔한 메뉴판 이미지를 기준으로, 픽셀 좌표를 이미지 크기로 나눠 0.0~1.0 으로 정규화한 값이다(해상도와 무관하게 비율로 표현). 원점(0,0)은 이미지의 **좌상단**이며, x 는 오른쪽, y 는 아래로 증가한다.

            픽셀 단위 박스가 (left, top, boxWidth, boxHeight)이고 이미지 크기가 (imageWidth, imageHeight)일 때:
            - `x = left / imageWidth`
            - `y = top / imageHeight`
            - `width = boxWidth / imageWidth`
            - `height = boxHeight / imageHeight`

            예) 1000×800 이미지에서 박스가 left=100, top=80, 너비=500, 높이=64 픽셀이면 → `x=0.1, y=0.08, width=0.5, height=0.08`.

            네 값 모두 0.0~1.0 범위여야 하고(x·y 는 0 이상, width·height 는 0 초과), 박스가 이미지 밖으로 나가지 않도록 `x+width ≤ 1.0`·`y+height ≤ 1.0` 이어야 한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "판정 성공 — scanId 와 itemId 로 매칭되는 항목별 판정 결과 반환"),
            ApiResponse(
                responseCode = "400",
                description = "요청 검증 실패 — 필수값 누락, itemId 중복, rawMenuName blank, boundingBox 누락/좌표 범위 위반 등",
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
                                    {"itemId": 0, "rawMenuName": "된장찌개", "boundingBox": {"x": 0.1, "y": 0.1, "width": 0.5, "height": 0.08}},
                                    {"itemId": 1, "rawMenuName": "김치찌개", "boundingBox": {"x": 0.1, "y": 0.2, "width": 0.5, "height": 0.08}},
                                    {"itemId": 2, "rawMenuName": "공기밥",   "boundingBox": {"x": 0.1, "y": 0.3, "width": 0.5, "height": 0.08}},
                                    {"itemId": 3, "rawMenuName": "콜라",     "boundingBox": {"x": 0.1, "y": 0.4, "width": 0.5, "height": 0.08}}
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
                                    {"itemId": 0, "rawMenuName": "공기밥", "boundingBox": {"x": 0.1, "y": 0.2, "width": 0.3, "height": 0.08}},
                                    {"itemId": 1, "rawMenuName": "공기밥", "boundingBox": {"x": 0.6, "y": 0.2, "width": 0.3, "height": 0.08}}
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
