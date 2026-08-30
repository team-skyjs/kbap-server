package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "관리자 음식 카탈로그", description = "관리자 전용 — 어드민 SPA 의 음식 목록/검색·상세·수정·재수집·소프트삭제 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodCatalogApi {
    @Operation(
        summary = "음식 목록/검색 조회",
        description = """
            전체 음식을 id 내림차순으로 페이지 조회한다. 어드민 SPA 의 음식 카탈로그 목록 화면 전용이다.

            - `q` 를 주면 표시 이름(displayName) 부분 일치로 검색한다.
            - `status` 를 주면 해당 콘텐츠 상태만 필터링한다.
            - 페이지 크기는 서버 고정(200)이며, 응답에 전체 건수(`totalCount`)와 전체 페이지 수를 포함한다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 대상이 없으면 빈 items 와 totalCount=0"),
            ApiResponse(responseCode = "400", description = "status 가 유효한 콘텐츠 상태가 아님(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getFoodPage(
        @Parameter(description = "1부터 시작하는 페이지 번호(1 미만이면 1로 보정)", example = "1")
        page: Int,
        @Parameter(description = "표시 이름 부분 일치 검색어", example = "김치")
        q: String?,
        @Parameter(description = "콘텐츠 상태 필터", example = "READY")
        status: FoodContentStatus?,
    ): ResponseEntity<BaseResponse<AdminFoodListResponse>>

    @Operation(
        summary = "음식 상세 조회",
        description = """
            음식 1건의 원본 필드·언어별 번역 맵·성분 매핑·이미지·검수 이력(반려 횟수·반려 사유·실패 종류)을 반환한다.
            어드민 SPA 상세 화면 전용이다.

            - `ingredients` 는 미조사(null)와 조사 완료·해당 없음(빈 배열)을 구분해 내려간다 — 위험도 계산이 갈리는 도메인 구분이다.
            - `imageUrl` 은 공개 이미지 URL 이며, 이미지가 없으면 null 이다.
            - 소프트삭제된 음식은 404 가 아니라 400(FOOD-001) 이다 — 조회는 ACTIVE 만 본다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "음식 없음 또는 삭제됨(FOOD-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getFoodDetail(
        @Parameter(description = "조회할 음식 id", example = "1")
        id: Long,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>>

    @Operation(
        summary = "음식 수정",
        description = """
            음식 1건의 이름·설명·맵기·콘텐츠 상태·이미지 참조·번역 맵·성분 매핑을 전체 교체 방식으로 수정하고,
            반영된 상세를 그대로 반환한다(SPA 낙관 업데이트 확정용).

            - `koreanName` 은 서버가 스캔 입구와 동일하게 정규화해 중복을 검사한다 — 다른 음식과 겹치면 409(FOOD-005).
            - 부분 수정이 아니라 전체 교체 계약이다. 번역 필드를 생략(null)하면 빈 맵으로 교체된다.
            - `ingredients` 는 상세 응답과 같은 구분을 따른다 — 생략(null)=미조사, 빈 배열=조사 완료·해당 없음.
            - `ingredients` 의 `code` 는 성분 카탈로그 코드만 허용한다 — 미지 코드는 400(COMMON-002). `spiciness` 는 -1(미조사)..10.
            - `version`(선택)을 실으면 낙관 잠금으로 동작한다 — 상세 조회의 `version` 을 그대로 보내고,
              그 사이 다른 관리자가 수정했으면 409(FOOD-006) 로 거절된다. 생략하면 무조건 덮어쓴다.
            - READY 전이·이탈 시 벡터 동기화 아웃박스 enqueue 는 서버가 수행한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공 — 반영된 상세 반환"),
            ApiResponse(
                responseCode = "400",
                description = "검증 실패(COMMON-002 — 필수 필드 누락·빈 이름·미지 성분 코드) 또는 음식 없음(FOOD-001)",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
            ApiResponse(
                responseCode = "409",
                description = "다른 음식과 이름 중복(FOOD-005) 또는 version 불일치 — 동시 수정 감지(FOOD-006)",
            ),
        ],
    )
    fun updateFood(
        @Parameter(description = "수정할 음식 id", example = "1")
        id: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = AdminFoodUpdateRequest::class),
                    examples = [
                        ExampleObject(
                            name = "수정",
                            value = """
                                {
                                  "koreanName": "된장찌개",
                                  "description": "구수한 된장찌개",
                                  "spiciness": 3,
                                  "contentStatus": "READY",
                                  "imageRef": "images/food/doenjang.webp",
                                  "nameTranslations": {"en": "Soybean paste stew"},
                                  "descriptionTranslations": {"en": "savory stew"},
                                  "ingredients": [{"code": "SOY", "inclusion_percent": 100}],
                                  "version": 3
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: AdminFoodUpdateRequest,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>>

    @Operation(
        summary = "음식 단건 재수집",
        description = """
            음식 1건의 콘텐츠 재수집 요청(아웃박스 PENDING)을 만든다. 이미 수집 대기 중이면 생성 없이 건너뛴다
            (`created=0`, `skipped=1` — 멱등).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "요청 성공 — requested/created/skipped 카운트 반환"),
            ApiResponse(responseCode = "400", description = "음식 없음(FOOD-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun recollectFood(
        @Parameter(description = "재수집할 음식 id", example = "1")
        id: Long,
    ): ResponseEntity<BaseResponse<AdminFoodRecollectResponse>>

    @Operation(
        summary = "음식 필터 일괄 재수집",
        description = """
            목록 화면의 현재 필터(`q`·`status`) 기준으로 일치하는 전 음식의 콘텐츠 재수집 요청을 일괄 생성한다.

            - 이미 수집 대기 중인 음식은 건너뛴다(`skipped` 에 집계 — 멱등).
            - 대상이 최대치(500)를 넘으면 아무것도 만들지 않고 `exceeded=true` 로 응답한다 — 필터를 좁혀 재요청한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "요청 성공 — 대상 0건이면 requested=0, 초과면 exceeded=true"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun recollectFoods(
        @Parameter(description = "표시 이름 부분 일치 검색어", example = "김치")
        q: String?,
        @Parameter(description = "콘텐츠 상태 필터", example = "FAILED")
        status: FoodContentStatus?,
    ): ResponseEntity<BaseResponse<AdminFoodRecollectResponse>>

    @Operation(
        summary = "음식 소프트삭제",
        description = """
            음식 1건을 소프트삭제한다(row 제거 아님 — 이후 모든 조회에서 제외). 벡터 삭제 동기화 enqueue 는 서버가 수행한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 성공"),
            ApiResponse(responseCode = "400", description = "음식 없음 또는 이미 삭제됨(FOOD-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun deleteFood(
        @Parameter(description = "삭제할 음식 id", example = "1")
        id: Long,
    ): ResponseEntity<BaseResponse<Unit>>
}
