package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 재료 소급 판정", description = "관리자 전용 — 새 재료 코드를 기존 READY 음식에 소급해 붙이는 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodIngredientRetroApi {
    @Operation(
        summary = "새 재료 소급 판정 시작",
        description = """
            READY 음식마다 LLM 을 1회 불러 지정 재료 코드가 들어갈 확률(0~1)을 받고, round(p×100) 이 반영 기준(`thresholdPercent`, 설정 `kbap.food.ingredient-retro.min-percent` · env `INGREDIENT_RETRO_MIN_PERCENT`, 기본 30) 이상이면
            `food.ingredients` 에 `{code, inclusion_percent = round(p×100)}` 를 바로 더한다(사람 승인 없음). 202 와 jobId 를 즉시 돌려주고
            API 프로세스 배경에서 동시 5건으로 처리한다.

            - 대상: READY 이고 그 코드가 아직 없으며 지금 재료 목록(음식 version)으로 판정한 적 없는 음식. 같은 코드로 다시 돌리면 이미 판정한 음식은 호출 0이다(멱등). 판정 뒤 음식이 수정돼 version 이 오르면 다시 대상이 된다.
            - 기본은 이미 있는 코드를 바꾸지 않는다. `rejudge=true` 면 판정 기록·이미 있는 코드를 무시하고 대상 전부를 다시 판정해 그 코드 항목을 교체한다 — 기준 이상은 비율 갱신·추가, 기준 미만은 `food.ingredients` 와 재료 관계에서 제거(`removedCodeCount`).
            - 재료가 21개를 넘게 되면 그 음식은 건너뛰고 `skippedCapFoodIds` 로 보고한다. 상한·미평가·도중 READY 이탈로 반영하지 못한 음식은 판정 기록을 남기지 않아 다시 돌리면 다시 부른다.
            - 재료가 아직 평가되지 않은(null) 음식은 부르지 않고 `skippedUnassessedFoodIds` 로 보고한다 — 일부 코드만 붙이면 다른 회피 재료가 UNKNOWN 대신 SAFE 로 바뀐다.
            - 호출이나 반영 쓰기가 실패했거나 판정 중에 음식이 수정된(version 변경 — 옛 판정은 버린다) 음식은 `failedFoodIds` 로 남고 판정 기록이 없어 다시 돌리면 그것만 다시 부른다.
            - `dryRun=true` 면 호출 0회로 대상 수와 예상 비용(대상 × 1회 추정 단가)만 센다. `rejudge` 와 함께여도 호출·교체 0이다.
            - 배포 등으로 API 프로세스가 내려가면 진행 중이던 작업은 FAILED 로 닫히고 잠금이 풀린다 — 같은 요청을 다시 보내면 이어 간다.
            - 같은 코드 집합의 작업이 진행 중이면 409(FOOD-023). 모르는 코드는 400(FOOD-016), 빈 목록은 400(COMMON-002).
            - 콘텐츠 상태는 READY 그대로, 버전은 올라가고 벡터 재동기화는 하지 않는다. 비용은 호출마다 `llm_call_cost` 에 남는다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "접수 — payload.jobId 로 진행을 조회한다"),
            ApiResponse(responseCode = "400", description = "codes 비었음(COMMON-002)·카탈로그에 없는 코드(FOOD-016)·dryRun 누락(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
            ApiResponse(responseCode = "409", description = "같은 코드 집합의 소급 판정이 진행 중(FOOD-023)"),
        ],
    )
    fun startRetro(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "판정할 코드·dryRun·rejudge(선택)·대상 음식(선택)")
        request: FoodIngredientRetroRequest,
    ): ResponseEntity<BaseResponse<FoodIngredientRetroStartResponse>>

    @Operation(
        summary = "소급 판정 작업 조회",
        description = """
            작업 진행·결과를 조회한다. status 가 RUNNING 이면 진행 중 수치다. 기록은 Redis 에 7일 보관한다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "없거나 만료된 작업(FOOD-024)"),
        ],
    )
    fun getRetroJob(
        @Parameter(description = "시작 응답의 jobId", example = "2f1c0f7e-8a3b-4c2d-9e1f-0a1b2c3d4e5f")
        jobId: String,
    ): ResponseEntity<BaseResponse<FoodIngredientRetroJob>>
}
