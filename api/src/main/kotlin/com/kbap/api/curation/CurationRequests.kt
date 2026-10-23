package com.kbap.api.curation

import com.kbap.common.domain.curation.model.CurationDocument
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import io.swagger.v3.oas.annotations.media.Schema

data class CurationLangRequest(@field:NotBlank @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val lang: String? = null)
data class CurationVersionRequest(@field:NotNull @field:Min(0) val expectedVersion: Long? = null)
data class CurationDraftRequest(@field:NotNull @field:Min(0) val expectedVersion: Long? = null, val document: CurationDocument)
data class CurationUploadRequest(@field:NotNull @field:Min(0) val expectedVersion: Long? = null, @field:NotBlank val contentType: String, @field:Min(1) val contentLength: Long)
data class CurationCompleteRequest(@field:NotBlank val contentType: String, @field:Min(1) val size: Long)
