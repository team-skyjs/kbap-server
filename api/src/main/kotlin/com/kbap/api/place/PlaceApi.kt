package com.kbap.api.place

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(name = "장소", description = "식당(장소) 검색 API")
@SecurityRequirement(name = "bearerAuth")
interface PlaceApi {
    @Operation(
        summary = "주변 식당 검색",
        description = """
            리뷰 작성 화면에서 사용자 위치(위도·경도) 기준으로 가까운 식당을 검색한다. 검색 키워드는 "음식점" 으로
            서버가 고정하며, 외부 지도 검색을 대신 호출하므로 클라이언트는 검색 자격증명을 갖지 않는다.
            결과 항목(items[])을 그대로 리뷰 작성 요청의 place 로 보내면 된다. 결과가 없으면 빈 배열을 반환한다(오류 아님).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "검색 성공(결과 없음 포함)"),
            ApiResponse(responseCode = "400", description = "latitude·longitude 누락·범위 밖, page 범위 밖"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "502", description = "외부 장소 검색 실패(PLACE-001) — 리뷰 작성은 식당 없이 계속 가능"),
        ],
    )
    fun search(
        memberId: Long,
        @ParameterObject request: PlaceSearchRequest,
    ): ResponseEntity<BaseResponse<PlaceSearchResponse>>
}
