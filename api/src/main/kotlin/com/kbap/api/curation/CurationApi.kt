package com.kbap.api.curation

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(name = "서울 큐레이션", description = "인증 없는 독립 조회. 10언어, no-store, 최초 3모음 동시 공개")
interface CurationApi {
    @Operation(summary = "서울 모음 카드", description = "lang 필수. 미지원은 en. 최초 준비 또는 하나라도 내리면 UNPUBLISHED와 빈 collections, 정상 공개는 정확히 3카드. 저장 무결성 장애는503이며 정상 미게시와 구별한다. 요청 번역 결손은 카드 그룹 전체en, en 결손503. 개인정보·초안·사진 증빙을 노출하지 않는다.")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.CURATION_UNAVAILABLE)
    fun getIndex(@ParameterObject request: CurationLangRequest): ResponseEntity<BaseResponse<CurationIndex>>
    @Operation(summary = "서울 모음 상세", description = "sights/stations/restaurants. 최초 공개 전 또는 내린 모음404. 최초 공개 후 나머지 게시 상세는 접근 가능. 항목10개·position1부터·외부HTTPS1개·역nearby2~3개. 번역 결손은 전체모음en, en 결손503.")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_UNAVAILABLE)
    fun getDetail(collectionKey: String, @ParameterObject request: CurationLangRequest): ResponseEntity<BaseResponse<CurationDetail>>
}
