package com.kbap.api.appversion

import com.kbap.common.domain.appversion.model.AppVersion
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "앱 버전 정보 응답")
data class AppVersionResponse(
    @field:Schema(description = "최소 지원 버전(semver). 이보다 낮은 앱은 강제 업데이트 대상", example = "1.0.0")
    val minSupportedVersion: String,
    @field:Schema(description = "최신 버전(semver)", example = "1.0.1")
    val latestVersion: String,
    @field:Schema(description = "플랫폼별 스토어 링크")
    val storeUrls: StoreUrlsResponse,
) {
    companion object {
        fun from(appVersion: AppVersion): AppVersionResponse =
            AppVersionResponse(
                minSupportedVersion = appVersion.minSupportedVersion,
                latestVersion = appVersion.latestVersion,
                storeUrls = StoreUrlsResponse(
                    ios = appVersion.iosStoreUrl,
                    aos = appVersion.aosStoreUrl,
                ),
            )
    }
}

@Schema(description = "플랫폼별 스토어 링크. 미배포·미설정 플랫폼은 null")
data class StoreUrlsResponse(
    @field:Schema(description = "앱스토어 링크(미설정 시 null)", example = "https://apps.apple.com/kr/app/id0000000000")
    val ios: String?,
    @field:Schema(description = "플레이스토어 링크(현재 미배포 — null)", example = "null")
    val aos: String?,
)
