package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "음식 콘텐츠 적재", description = "외부 콘텐츠 생성 파이프라인(kbap-langchain)이 완성한 음식 콘텐츠를 적재하는 머신 전용 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodContentIngestApi {
    @Operation(
        summary = "음식 콘텐츠 단건 적재",
        description = """
            콘텐츠 생성 결과를 음식 한 건에 반영한다. `passed` 로 성공/실패를 가른다.

            - `outboxId` 와 `foodId` 는 발행 메시지에서 받은 값을 그대로 돌려보내야 한다. 둘의 조합이 다르거나 아웃박스가 없으면 400(COMMON-002) 이다.
            - 대상은 **`foodId` 로만** 특정한다. 없거나 삭제된 음식이면 400(FOOD-001) 이며, 호출자는 사람 판단 경로(DLQ)로 보낸다.
            - **상태는 서버가 정한다.** 이미 서비스 중(READY)이면 텍스트만 덮고 상태를 바꾸지 않는다(재수집 중 노출 끊김 방지).
              그 외에는 사진이 있으면 `PENDING_REVIEW`, 없으면 `PENDING_IMAGE` 로 간다. **기존 사진은 어떤 경우에도 교체하지 않는다.**
            - `longDescription`(선택, 최대 1000자)은 벡터 DB 메타데이터 용도다 — **사용자에게 노출되지 않으며**, 미전송 시 기존 값을 지운다(적재는 전체 교체).
            - `nameTranslations`·`descriptionTranslations` 는 9개 대상 언어를 모두 채워야 한다(`ko` 는 원문이라 제외).
            - `ingredients` 는 필수이며 빈 배열을 허용한다 — **빈 배열은 "조사했고 해당 없음"(SAFE)** 이고 누락은 미조사(UNKNOWN)라 의미가 다르다.
            - 실패(`passed=false`)는 `failureKind` 3값과 `reason` 을 요구한다. `reason` 은 표시 전용이며 분기에 쓰지 않는다.
            - 같은 `outboxId`·`foodId` 결과가 중복 도착하면 409(FOOD-004) 이다. 이는 이미 적재된 정상 종료 신호이므로 호출자는 재시도·DLQ 대상에서 제외한다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @SwaggerRequestBody(
        content = [
            Content(
                examples = [
                    ExampleObject(
                        name = "성공",
                        value = """
                        {
                          "outboxId": 5678,
                          "foodId": 1234,
                          "displayName": "들깨 칼국수",
                          "passed": true,
                          "description": "들깨를 곱게 갈아 넣어 고소한 칼국수",
                          "longDescription": "들깨 칼국수는 곱게 간 들깨를 육수에 풀어 …(벡터 검색 메타데이터용 긴 설명, 최대 1000자)",
                          "spiciness": 2,
                          "nameTranslations": {"en":"Perilla Kalguksu","ja":"えごまカルグクス","zh-Hans":"紫苏刀削面","zh-Hant":"紫蘇刀削麵","vi":"Mì tía tô","id":"Kalguksu Perilla","th":"คัลกุกซูงาขี้ม้อน","ru":"Кальгуксу с периллой","es":"Kalguksu de perilla"},
                          "descriptionTranslations": {"en":"...","ja":"...","zh-Hans":"...","zh-Hant":"...","vi":"...","id":"...","th":"...","ru":"...","es":"..."},
                          "ingredients": [{"code":"SESAME","inclusion_percent":100}]
                        }
                        """,
                    ),
                    ExampleObject(
                        name = "실패",
                        value = """
                        {
                          "outboxId": 5678,
                          "foodId": 1234,
                          "passed": false,
                          "failureKind": "JUDGE_REJECTED",
                          "reason": "번역 점수 78점으로 임계값 미달"
                        }
                        """,
                    ),
                ],
            ),
        ],
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "최초 적재 성공"),
            ApiResponse(responseCode = "400", description = "계약 위반(COMMON-002) 또는 대상 음식 없음·삭제됨(FOOD-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
            ApiResponse(responseCode = "409", description = "같은 아웃박스 요청이 이미 처리됨(FOOD-004)"),
        ],
    )
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND, ErrorCode.FOOD_CONTENT_REQUEST_ALREADY_COMPLETED)
    fun ingestContent(request: AdminFoodContentIngestRequest): ResponseEntity<BaseResponse<Unit>>
}
