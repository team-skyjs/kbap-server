package com.kbap.api.place

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API)
class PlaceController(
    private val placeSearchService: PlaceSearchService,
) : PlaceApi {
    @GetMapping("/places")
    override fun search(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute request: PlaceSearchRequest,
    ): ResponseEntity<BaseResponse<PlaceSearchResponse>> =
        ResponseEntity.ok(BaseResponse.ok(placeSearchService.searchPlaces(request.query!!, request.page)))
}
