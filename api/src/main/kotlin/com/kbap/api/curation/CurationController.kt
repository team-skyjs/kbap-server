package com.kbap.api.curation

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping(ApiPaths.API + "/curations/seoul", version = "1.0+")
class CurationController(private val service: CurationService) : CurationApi {
    @GetMapping
    override fun getIndex(@Valid @ModelAttribute request: CurationLangRequest): ResponseEntity<BaseResponse<CurationIndex>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.getIndex(request.lang!!)))
    @GetMapping("/{collectionKey}")
    override fun getDetail(@PathVariable collectionKey: String, @Valid @ModelAttribute request: CurationLangRequest): ResponseEntity<BaseResponse<CurationDetail>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.getDetail(collectionKey, request.lang!!)))
}
