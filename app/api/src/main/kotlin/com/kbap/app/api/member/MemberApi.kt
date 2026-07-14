package com.kbap.app.api.member

import com.kbap.app.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "회원", description = "온보딩·프로필 API")
@SecurityRequirement(name = "bearerAuth")
interface MemberApi {
    @Operation(
        summary = "온보딩 정보 제출",
        description = """
            로그인한 회원이 닉네임·기피 성분 코드 목록·국가·앱 언어를 제출하면, 각 값을 검증한 뒤
            프로필로 저장하고 온보딩을 완료 상태로 전이한다. 이미 완료한 회원의 재제출은 거절된다
            (프로필 재설정은 후속 기능). `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "온보딩 완료 — 프로필 저장·상태 전이"),
            ApiResponse(responseCode = "400", description = "입력 검증 실패(기피 성분·국가·언어·닉네임), 이미 온보딩 완료, 또는 회원을 찾을 수 없음"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun completeOnboarding(
        memberId: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    mediaType = "application/json",
                    examples = [
                        ExampleObject(
                            name = "한국 · 계란/우유/땅콩 기피",
                            value = """
                                {
                                  "nickname": "길동이",
                                  "avoidanceSubstanceCodes": ["EGG", "MILK", "PEANUT"],
                                  "countryCode": "KR",
                                  "appLanguage": "ko"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "미국 · 기피 음식 없음",
                            value = """
                                {
                                  "nickname": "John",
                                  "avoidanceSubstanceCodes": [],
                                  "countryCode": "US",
                                  "appLanguage": "en"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "일본 · 갑각류/생선 기피",
                            value = """
                                {
                                  "nickname": "さくら",
                                  "avoidanceSubstanceCodes": ["SHRIMP", "CRAB", "MACKEREL"],
                                  "countryCode": "JP",
                                  "appLanguage": "ja"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "베트남 · 견과류 다수 기피",
                            value = """
                                {
                                  "nickname": "Linh",
                                  "avoidanceSubstanceCodes": ["WALNUT", "ALMOND", "CASHEW"],
                                  "countryCode": "VN",
                                  "appLanguage": "vi"
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: OnboardingRequest,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "내 프로필 조회",
        description = """
            현재 회원의 프로필 정보(닉네임·기피 성분·국가·앱 언어)와 랭킹 요약(등급 키·레벨·점수·다음 등급·
            다음 등급까지 남은 점수)을 함께 조회한다. 프로필 탭이 이 응답 하나로 그려지도록 랭킹 요약을 싣되,
            점수 내역(breakdown)은 담지 않는다 — 내역이 필요하면 랭킹 상세 조회를 쓴다.
            등급명 번역은 클라이언트가 하며 서버는 안정 키(newcomer·taster·explorer …)만 내려준다.
            `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 프로필 정보 + 랭킹 요약"),
            ApiResponse(responseCode = "400", description = "회원을 찾을 수 없음"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun getMyProfile(
        memberId: Long,
    ): ResponseEntity<BaseResponse<MyProfileResponse>>

    @Operation(
        summary = "내 랭킹 상세 조회",
        description = """
            현재 회원의 랭킹을 점수 내역까지 조회한다. 점수는 `리뷰 수 × 10 + 리뷰한 고유 음식 수 × 5 +
            스캔 횟수 × 2` 로 산정하며, 스캔 횟수는 메뉴판 1장을 1회로 센다. 등급은 누적 점수 구간으로
            7단계(newcomer 0 · taster 30 · explorer 80 · regular 180 · gourmet 350 · kfood_master 600 ·
            korean_at_heart 1000)이며, 최고 등급이면 nextTier·pointsToNext 가 null 이다.
            리뷰 기능 도입 전이라 reviews·diversity 는 현재 항상 0이다.
            `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 랭킹 요약 + 점수 내역"),
            ApiResponse(responseCode = "400", description = "회원을 찾을 수 없음"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun getMyRanking(
        memberId: Long,
    ): ResponseEntity<BaseResponse<MemberRankingResponse>>

    @Operation(
        summary = "프로필 수정 (부분 수정)",
        description = """
            온보딩을 마친 회원이 프로필(닉네임·기피 성분·국가·앱 언어)을 다시 설정한다. **바꾸고 싶은 필드만**
            담아 보내면 된다 — 모든 필드가 선택이며, **보내지 않은 필드는 기존 값이 유지된다.**

            기피 성분은 **빈 배열 `[]` 이면 전부 해제**, **미전송이면 유지**로 서로 다르게 동작한다. 그래서
            닉네임 화면은 `nickname`·`countryCode`·`appLanguage` 만, 기피 성분 화면은 `avoidanceSubstanceCodes`
            만 보내면 된다. 필드에 `null` 을 명시하는 것은 미전송과 같다(유지).

            검증은 **값이 전달된 필드에만** 적용한다 — 보내지 않은 필드 때문에 400 이 나지 않는다. 전달된 값이
            무효하면 요청 전체를 거절하고 프로필은 하나도 바뀌지 않는다(부분 저장 없음). 온보딩 완료 상태는
            그대로 유지된다(재설정 전용, 상태 전이 없음). `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공 — 전달한 필드만 갱신(빈 본문이면 변경 없음)"),
            ApiResponse(responseCode = "400", description = "전달된 값의 검증 실패(기피 성분·국가·언어·닉네임) 또는 회원을 찾을 수 없음"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun updateProfile(
        memberId: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    mediaType = "application/json",
                    examples = [
                        ExampleObject(
                            name = "닉네임·국가·언어 화면 — 기피 성분은 유지된다",
                            value = """
                                {
                                  "nickname": "길동이",
                                  "countryCode": "KR",
                                  "appLanguage": "ko"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "기피 성분 화면 — 나머지는 유지된다",
                            value = """
                                {
                                  "avoidanceSubstanceCodes": ["EGG", "MILK"]
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "기피 성분 전부 해제 — 빈 배열은 미전송과 다르다",
                            value = """
                                {
                                  "avoidanceSubstanceCodes": []
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "전체 교체 — 네 필드를 모두 보낸다",
                            value = """
                                {
                                  "nickname": "길동이",
                                  "avoidanceSubstanceCodes": ["PEANUT"],
                                  "countryCode": "JP",
                                  "appLanguage": "ja"
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: ProfileUpdateRequest,
    ): ResponseEntity<BaseResponse<Unit>>
}
