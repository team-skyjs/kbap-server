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
    @GetMapping("/places/nearby")
    override fun getNearbyPlaces(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute request: PlaceNearbyRequest,
    ): ResponseEntity<BaseResponse<PlaceNearbyResponse>> =
        ResponseEntity.ok(BaseResponse.ok(placeSearchService.getNearbyPlaces(request.latitude!!, request.longitude!!)))

    @GetMapping("/places/search")
    override fun searchPlaces(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute request: PlaceSearchRequest,
    ): ResponseEntity<BaseResponse<PlaceSearchPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                placeSearchService.searchPlacePage(request.query!!, request.latitude!!, request.longitude!!, request.page),
            ),
        )
}
