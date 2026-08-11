package com.kbap.api.member

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
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
        summary = "온보딩 정보 제출 (X-API-Version 1.0 — 종전 계약)",
        description = """
            로그인한 회원이 닉네임·기피 성분 코드 목록·국가·맵기 선호를 제출하면, 각 값을 검증한 뒤
            프로필로 저장하고 온보딩을 완료 상태로 전이한다. 이미 완료한 회원의 재제출은 거절된다
            (프로필 재설정은 후속 기능). `Authorization: Bearer {accessToken}` 로 인증한다.

            맵기 `spicinessPreference` 는 **필수** — `SKIP`·`NONE`·`MILD`·`MEDIUM`·`HOT`·`EXTREME` 6단계 문자열.
            맵기 화면을 건너뛰면 클라이언트가 **`SKIP` 을 명시 전송**한다. 미전송이면 필수 누락으로 400 COMMON-002, 6단계 외 값이면
            400 MEMBER-009 로 거절한다.

            **버전은 `X-API-Version` 요청 헤더로 전달한다** — 미전송·`1.0` 은 이 종전 계약, `1.1` 이상은
            닉네임·사진 서버 자동 지정 계약(아래 별도 오퍼레이션), 지원 목록에 없는 버전은 400.

            종전 계약에서 `nickname`·`profileImageUrl` 은 **필수**이며
            미전송·null 이면 400 COMMON-002 로 거절한다. `profileImageUrl` 은 CDN 도메인 없는 이미지 경로
            (presigned 발급 응답의 `objectKey`)를 보내고, 사진 미설정 회원은 기본 이미지 경로
            `images/default/profile/profile-default-512.png` 를 명시 전송한다. 빈 문자열·전체 URL
            (`http(s)://` 시작)·512자 초과는 400 MEMBER-008 로 거절한다.
            조회 응답에서는 설정된 CDN 도메인이 조합된 완전한 URL 로 내려간다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "온보딩 완료 — 프로필 저장·상태 전이"),
            ApiResponse(responseCode = "400", description = "입력 검증 실패(기피 성분·국가·닉네임·사진 URL·맵기), 이미 온보딩 완료, 미지원 X-API-Version, 또는 회원을 찾을 수 없음"),
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
                            name = "한국 · 계란/우유/땅콩 기피 · 사진·맵기 포함",
                            value = """
                                {
                                  "nickname": "길동이",
                                  "avoidanceSubstanceCodes": ["EGG", "MILK", "PEANUT"],
                                  "countryCode": "KR",
                                  "profileImageUrl": "profile-image/2026/07/18/1/abc.jpg",
                                  "spicinessPreference": "HOT"
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
                                  "profileImageUrl": "images/default/profile/profile-default-512.png",
                                  "spicinessPreference": "MEDIUM"
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
                                  "profileImageUrl": "images/default/profile/profile-default-512.png",
                                  "spicinessPreference": "EXTREME"
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
        summary = "온보딩 정보 제출 (X-API-Version 1.1 — 닉네임·사진 서버 자동 지정)",
        description = """
            `X-API-Version: 1.1` 이상으로 제출하는 온보딩 계약. 닉네임은 서버가 영숫자 6자 코드로,
            프로필 사진은 기본 아바타 중 하나로 **랜덤 지정**한다 — 요청에 `nickname`·`profileImageUrl` 을
            담아도 무시된다. 지정된 값은 프로필 수정 API 로 언제든 변경할 수 있다.
            그 외 필드 검증·완료 전이 규칙은 1.0 계약과 동일하다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "온보딩 완료 — 서버 지정 프로필 저장·상태 전이"),
            ApiResponse(responseCode = "400", description = "입력 검증 실패(기피 성분·국가·맵기), 이미 온보딩 완료, 미지원 X-API-Version, 또는 회원을 찾을 수 없음"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun completeOnboardingWithServerProfile(
        memberId: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    mediaType = "application/json",
                    examples = [
                        ExampleObject(
                            name = "미국 · 기피 음식 없음 · 맵기 스킵(SKIP) — 닉네임·사진 서버 자동 지정",
                            value = """
                                {
                                  "avoidanceSubstanceCodes": [],
                                  "countryCode": "US",
                                  "spicinessPreference": "SKIP"
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
            현재 회원의 프로필 정보(연동 소셜 제공자 `provider`(GOOGLE/APPLE)·닉네임·기피 성분·국가·통화·프로필 사진 URL·맵기 선호 — 미설정이면 `SKIP`)와 랭킹 요약(등급 키·레벨·점수·다음 등급·
            다음 등급까지 남은 점수)을 함께 조회한다. 프로필 탭이 이 응답 하나로 그려지도록 랭킹 요약을 싣되,
            점수 내역(breakdown)은 담지 않는다 — 내역이 필요하면 랭킹 상세 조회를 쓴다.
            등급명 번역은 클라이언트가 하며 서버는 안정 키(newcomer·taster·explorer …)만 내려준다.
            통화 `currency` 는 온보딩에서 국가 기준으로 자동 지정되며, 온보딩 전 회원은 `null` 이다.
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
            온보딩을 마친 회원이 프로필(닉네임·기피 성분·국가·통화·프로필 사진·맵기 선호)을 다시 설정한다.
            **바꾸고 싶은 필드만** 담아 보내면 된다 — 모든 필드가 선택이며, **보내지 않은 필드는 기존 값이 유지된다.**

            기피 성분은 **빈 배열 `[]` 이면 전부 해제**, **미전송이면 유지**로 서로 다르게 동작한다. 그래서
            닉네임 화면은 `nickname`·`countryCode` 만, 기피 성분 화면은 `avoidanceSubstanceCodes`
            만 보내면 된다. 필드에 `null` 을 명시하는 것은 미전송과 같다(유지).

            프로필 사진 `profileImageUrl` 은 2분법 — **미전송이면 유지**, **CDN 도메인 없는 경로를 보내면 검증 후 교체**
            (빈 문자열·전체 URL·512자 초과는 MEMBER-008 거절). 사진을 없애는 개념은 없다 — 기본 이미지로
            되돌리려면 기본 이미지 경로 `images/default/profile/profile-default-512.png` 를 명시 전송한다.
            조회 응답에서는 CDN 도메인이 조합된 완전한 URL 로 내려간다. 맵기 `spicinessPreference` 는 `SKIP`·`NONE`·`MILD`·`MEDIUM`·`HOT`·`EXTREME` 6단계 문자열로 교체하며, `SKIP` 을 명시 전송하면 미설정으로 복귀한다.
            6단계 외 값은 MEMBER-009 로 거절한다.

            통화 `currency` 는 **국가와 독립적으로** 동작한다 — `countryCode` 를 바꿔도 통화는 따라 바뀌지 않는다.
            국가 기준 자동 지정은 온보딩에서 한 번만 일어나며, 이후에는 사용자가 직접 지정한 값을 덮어쓰지 않는다.
            지원 통화 목록 밖 값(대소문자·앞뒤 공백이 다른 값 포함 — 정확 일치만 허용)은 MEMBER-010 으로 거절한다.

            검증은 **값이 전달된 필드에만** 적용한다 — 보내지 않은 필드 때문에 400 이 나지 않는다. 전달된 값이
            무효하면 요청 전체를 거절하고 프로필은 하나도 바뀌지 않는다(부분 저장 없음). 온보딩 완료 상태는
            그대로 유지된다(재설정 전용, 상태 전이 없음). `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공 — 전달한 필드만 갱신(빈 본문이면 변경 없음)"),
            ApiResponse(responseCode = "400", description = "전달된 값의 검증 실패(기피 성분·국가·닉네임·사진 URL·맵기) 또는 회원을 찾을 수 없음"),
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
                            name = "닉네임·국가 화면 — 기피 성분은 유지된다",
                            value = """
                                {
                                  "nickname": "길동이",
                                  "countryCode": "KR"
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
                            name = "사진 교체 — 나머지는 유지된다",
                            value = """
                                {
                                  "profileImageUrl": "profile-image/2026/07/18/1/new.jpg"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "기본 이미지로 복귀 — 빈 문자열은 400, 기본 경로를 명시 전송한다",
                            value = """
                                {
                                  "profileImageUrl": "images/default/profile/profile-default-512.png"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "맵기 변경 — 6단계 문자열",
                            value = """
                                {
                                  "spicinessPreference": "EXTREME"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "맵기 설정 안 함 — SKIP 명시는 미전송(유지)과 다르다",
                            value = """
                                {
                                  "spicinessPreference": "SKIP"
                                }
                            """,
                        ),
                        ExampleObject(
                            name = "전체 교체 — 모든 필드를 보낸다",
                            value = """
                                {
                                  "nickname": "길동이",
                                  "avoidanceSubstanceCodes": ["PEANUT"],
                                  "countryCode": "JP",
                                  "profileImageUrl": "profile-image/2026/07/18/1/new.jpg",
                                  "spicinessPreference": "MILD"
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: ProfileUpdateRequest,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "프로필 수정 (부분 수정) — X-API-Version 2.0 이상",
        description = """
            같은 경로 `PATCH /api/v1/members/me/profile` 을 `X-API-Version: 2.0` 이상으로 호출하면 이 버전으로 라우팅된다.
            클라이언트는 URL 을 바꾸지 않고 헤더만 올리면 된다(헤더가 없거나 2.0 미만이면 위의 기본 버전이 응답한다).

            기본 버전과의 유일한 차이는 **국적(countryCode)을 수정할 수 없다**는 점이다 — 국적은 최초 온보딩에서
            확정되며, 요청에 countryCode 를 포함해 보내도 알 수 없는 필드로 무시된다(오류 아님).
            통화(currency)는 국적과 무관하게 바꿀 수 있다. 나머지 필드의 의미·검증은 기본 버전과 동일하다.
            `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 완료"),
            ApiResponse(responseCode = "400", description = "입력 검증 실패(기피 성분·닉네임·사진 URL·맵기·통화) 또는 회원을 찾을 수 없음"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun updateProfileV2(
        memberId: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    mediaType = "application/json",
                    examples = [
                        ExampleObject(
                            name = "닉네임·기피 성분·통화 수정 — 국적 필드 없음",
                            value = """
                                {
                                  "nickname": "새닉네임",
                                  "avoidanceSubstanceCodes": ["PEANUT"],
                                  "profileImageUrl": "images/default/profile/profile-default-512.png",
                                  "spicinessPreference": "MILD",
                                  "currency": "USD"
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: ProfileUpdateV2Request,
    ): ResponseEntity<BaseResponse<Unit>>
}
