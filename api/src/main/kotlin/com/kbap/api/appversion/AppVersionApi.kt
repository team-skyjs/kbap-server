package com.kbap.api.appversion

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "앱 버전", description = "앱 버전 정보 조회 API — 경로는 /api/app-version(URI 무버전), 버전은 X-API-Version 헤더(기본 1.0)로 전달한다")
interface AppVersionApi {
    @Operation(
        summary = "앱 버전 정보 조회",
        description = """
            최소 지원 버전·최신 버전·플랫폼별 스토어 링크를 내려준다.

            ## 인증 (불필요)
            **인증 없이 호출하는 공개 API** 다. 앱 최초 실행(로그인 전) 시점에 호출된다.

            ## 강제 업데이트 판단
            버전 비교·강제 업데이트 판단은 클라이언트 책임이다. 앱 버전이 `minSupportedVersion` 보다
            낮으면 강제 업데이트를 안내하고 자기 플랫폼의 스토어 링크로 이동시킨다.
            미배포·미설정 플랫폼의 링크는 null 이다(현재 aos 는 null).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
        ],
    )
    fun getAppVersion(): ResponseEntity<BaseResponse<AppVersionResponse>>
}
