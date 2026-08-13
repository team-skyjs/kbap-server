package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "앱 버전 정보 갱신 요청 — 전체 값 치환(PUT)")
data class AdminAppVersionUpdateRequest(
    @field:NotBlank(message = "minSupportedVersion 은 필수입니다")
    @field:Pattern(regexp = SEMVER_PATTERN, message = "minSupportedVersion 은 major.minor.patch 형식이어야 합니다")
    @field:Schema(description = "최소 지원 버전(semver)", example = "1.0.0", requiredMode = Schema.RequiredMode.REQUIRED)
    val minSupportedVersion: String,
    @field:NotBlank(message = "latestVersion 은 필수입니다")
    @field:Pattern(regexp = SEMVER_PATTERN, message = "latestVersion 은 major.minor.patch 형식이어야 합니다")
    @field:Schema(description = "최신 버전(semver)", example = "1.0.1", requiredMode = Schema.RequiredMode.REQUIRED)
    val latestVersion: String,
    @field:Size(max = 512, message = "iosStoreUrl 은 512자 이내여야 합니다")
    @field:Schema(description = "앱스토어 링크(비우려면 null)", example = "https://apps.apple.com/kr/app/id0000000000")
    val iosStoreUrl: String? = null,
    @field:Size(max = 512, message = "aosStoreUrl 은 512자 이내여야 합니다")
    @field:Schema(description = "플레이스토어 링크(미배포면 null)", example = "null")
    val aosStoreUrl: String? = null,
) {
    companion object {
        const val SEMVER_PATTERN = "^\\d+\\.\\d+\\.\\d+$"
    }
}
