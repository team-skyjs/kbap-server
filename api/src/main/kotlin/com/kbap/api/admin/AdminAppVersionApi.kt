package com.kbap.api.admin

import com.kbap.api.appversion.AppVersionResponse
import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 앱 버전", description = "관리자 전용 — 앱 버전 정보(최소 지원·최신 버전·스토어 링크) 조회·갱신 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminAppVersionApi {
    @Operation(
        summary = "앱 버전 정보 조회",
        description = "현재 설정된 최소 지원 버전·최신 버전·플랫폼별 스토어 링크를 조회한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "403", description = "관리자 아님(AUTH-008)"),
        ],
    )
    fun getAppVersion(): ResponseEntity<BaseResponse<AppVersionResponse>>

    @Operation(
        summary = "앱 버전 정보 갱신",
        description = """
            최소 지원 버전·최신 버전·스토어 링크를 전체 값 치환(PUT)으로 갱신한다. 변경 즉시 공개 조회에 반영된다.

            - 버전은 `major.minor.patch` 형식(semver)만 허용한다 — 위반 시 400(COMMON-002).
            - 스토어 링크는 null 허용(미배포 플랫폼), 최대 512자.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "갱신 성공 — 갱신된 값 반환"),
            ApiResponse(responseCode = "400", description = "버전 형식 위반(COMMON-002)"),
            ApiResponse(responseCode = "403", description = "관리자 아님(AUTH-008)"),
        ],
    )
    fun updateAppVersion(request: AdminAppVersionUpdateRequest): ResponseEntity<BaseResponse<AppVersionResponse>>
}
