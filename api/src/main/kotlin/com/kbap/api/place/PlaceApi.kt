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
        summary = "주변 식당 조회",
        description = """
            리뷰 작성의 장소 태그 화면 진입 시 사용자 위치(위도·경도) 기준으로 가까운 식당을 최대 20건 반환한다(가까운 순, 페이징 없음).
            데이터는 Google Places 기반이며 식당(restaurant) 타입으로 한정한다. lang 은 필수 — 결과 식당명·주소가 해당 언어로
            내려가고(번역 부재 시 현지어+음역), 지원 목록에 없는 코드는 en 으로 처리한다.
            결과 항목(items[])을 그대로 리뷰 작성 요청의 place 로 보내면 된다(source 는 GOOGLE_PLACE).
            결과가 없으면 빈 배열을 반환한다(오류 아님).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공(결과 없음 포함)"),
            ApiResponse(responseCode = "400", description = "latitude·longitude 누락·범위 밖, lang 누락·빈/공백"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "502", description = "외부 장소 검색 실패(PLACE-001) — 리뷰 작성은 식당 없이 계속 가능"),
        ],
    )
    fun getNearbyPlaces(
        memberId: Long,
        @ParameterObject request: PlaceNearbyRequest,
    ): ResponseEntity<BaseResponse<PlaceNearbyResponse>>

    @Operation(
        summary = "식당 키워드 검색",
        description = """
            원하는 식당을 키워드로 검색한다. 사용자 위치(위도·경도)를 바이어스로 관련도순 최대 20건을 단일 응답으로
            반환한다 — 페이징 없음(page 파라미터·hasNext 삭제, 구 page 는 보내도 무시). 데이터는 Google Places 기반.
            lang 은 필수 — 결과 식당명·주소가 해당 언어로 내려가고(번역 부재 시 현지어+음역), 지원 목록에 없는 코드는 en 으로 처리한다.
            외부 지도 검색을 서버가 대신 호출하므로 클라이언트는 검색 자격증명을 갖지 않는다.
            결과 항목(items[])을 그대로 리뷰 작성 요청의 place 로 보내면 된다(source 는 GOOGLE_PLACE).
            결과가 없으면 빈 배열을 반환한다(오류 아님).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "검색 성공(결과 없음 포함)"),
            ApiResponse(responseCode = "400", description = "query 누락·공백, latitude·longitude 누락·범위 밖, lang 누락·빈/공백"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "502", description = "외부 장소 검색 실패(PLACE-001) — 리뷰 작성은 식당 없이 계속 가능"),
        ],
    )
    fun searchPlaces(
        memberId: Long,
        @ParameterObject request: PlaceSearchRequest,
    ): ResponseEntity<BaseResponse<PlaceSearchListResponse>>
}
