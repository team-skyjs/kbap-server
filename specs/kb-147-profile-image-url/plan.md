# Implementation Plan: 프로필 사진 URL·맵기 선호 필드 추가

**Branch**: `kb-147-profile-image-url` | **Date**: 2026-07-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-147-profile-image-url/spec.md` (Jira KB-147 + 맵기 추가 요청)

## Summary

회원 프로필에 프로필 사진 URL(선택값)을 도입하고, **기존에 잠겨 있던 맵기 선호(`spicinessPreference`, 0~10·기본 5)의 입출력 경로를 함께 개방**한다 — 온보딩 요청·프로필 부분 수정 요청에 `profileImageUrl`·`spicinessPreference` 를 받고, 내 프로필 조회 응답에 포함한다. 이미지 바이트는 서버 미통과(클라이언트가 S3 업로드 → CloudFront CDN URL 만 전달, presigned 발급은 별도 태스크). 맵기는 도메인 모델·JSON 저장에 이미 존재하므로(현재 온보딩·수정이 항상 기존값을 유지하고 조회 응답에 없음) DTO·서비스 병합·검증만 손댄다.

핵심 설계 결정 (근거: [research.md](research.md)):

1. **저장은 기존 `member.profile` JSON 컬럼의 필드 추가 — Flyway 마이그레이션 0건.** 프로필 속성(회피 성분·맵기·국가·앱 언어)은 이미 `MemberProfileJson` 으로 JSON 컬럼에 저장되고 있고, 사진 URL 은 조회 필터·인덱스 대상이 아닌 표시용 값이므로 같은 곳에 둔다. 기존 row 는 JSON 에 키가 없음 → Jackson 기본값 `null` = 미설정(Jira DoD 의 "nullable, 기존 회원 null" 의도 충족 — DoD 의 "컬럼 + 마이그레이션" 문구는 JSON 저장 구조 확인 전 작성으로 판단).
2. **검증은 `MemberService` 의 기존 `validated~` 관례로**: https URL 형식 + 길이 한도 + **허용 호스트 목록(환경 설정 `kbap.member.profile-image-allowed-hosts`, 빈 목록이면 형식 검증만)**. 불합격은 신규 `ErrorCode.INVALID_PROFILE_IMAGE_URL`(MEMBER-008). 부분 수정 시맨틱: 미전송(null)=유지 · 값=검증 후 교체 · 빈 문자열=제거(null).
3. **맵기는 선택 입력 + 서비스 검증**: 온보딩·수정 모두 `spicinessPreference: Int? = null` — 미전송이면 기존 값 유지(신규 회원 기본 5). 범위(0~10) 밖은 신규 `ErrorCode.INVALID_SPICINESS_PREFERENCE`(MEMBER-009) — `MemberProfile` init 의 `require`(IllegalArgumentException → 500)에 도달하기 전에 서비스가 400 으로 거절한다. 맵기는 non-null 속성이라 제거 개념 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Hibernate `@JdbcTypeCode(SqlTypes.JSON)` + Jackson (기존 프로필 JSON 매핑 그대로)

**Storage**: MySQL — 기존 `member.profile` JSON 컬럼 (신규 컬럼·마이그레이션 없음)

**Testing**: Kotest BehaviorSpec (한국어 given/when/then) — 도메인 통합(MySQL Testcontainers, `MemberServiceTest`) + `app:api` MockMvc 통합

**Target Platform**: `:app:api` web bootJar (batch 무관)

**Project Type**: 백엔드 모듈러 모놀리스 — 변경 범위는 `:core`(ErrorCode)·`:domain:member`·`:app:api` 3개 모듈

**Performance Goals**: 해당 없음 — 기존 조회/수정 경로에 필드 1개 추가, 추가 쿼리 0건

**Constraints**: 하위 호환 — 사진 필드 미전송 기존 클라이언트의 온보딩·조회·수정 동작 무변화. URL 길이 한도 512자(코드 검증)

**Scale/Scope**: API 3종에 필드 2개(사진 URL·맵기) 추가(온보딩·조회·수정), 신규 엔드포인트 0건, DB 스키마 변경 0건 (맵기는 JSON 저장에 기존재 — DTO·서비스 경로만 개방)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 모든 변경을 실패 테스트 선행(Red→Green) — MemberServiceTest(도메인 통합)·MemberController MockMvc 시나리오를 tasks 에서 테스트 우선 순서로 배치 |
| II. Bounded Contexts | ✅ PASS | member 컨텍스트 내부 변경만 — 타 도메인 의존 추가 없음, member 는 리프 유지 |
| III. Layered Dependency Direction | ✅ PASS | 의존 그래프 무변경. `:app:api` → `:domain:member` → `:core` 기존 방향 그대로 |
| IV. Persistence Encapsulation | ✅ PASS | JSON 매핑 상세는 `Member`/`MemberProfileJson`(도메인 모듈 내부)에 갇힘 — 외부 노출은 도메인 서비스·`MyProfileResult` DTO 만. JPA 연관관계 추가 없음 |
| V. Domain Content Language Policy | ✅ PASS(해당 없음) | 사진 URL 은 음식 콘텐츠가 아님 — 번역 대상 아님 |

Post-Phase 1 재평가: 위반 없음 (Complexity Tracking 불필요).

## Project Structure

### Documentation (this feature)

```text
specs/kb-147-profile-image-url/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 저장 방식·검증 정책 결정 기록
├── data-model.md        # Phase 1 — MemberProfile/JSON 필드 정의
├── quickstart.md        # Phase 1 — 검증 시나리오 실행법
├── contracts/
│   └── profile-image-api.md  # 온보딩·조회·수정 API 계약 delta
└── tasks.md             # /speckit-tasks 산출물 (이 커맨드는 만들지 않음)
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/error/
└── ErrorCode.kt                          # [수정] INVALID_PROFILE_IMAGE_URL("MEMBER-008", 400)·
                                          #        INVALID_SPICINESS_PREFERENCE("MEMBER-009", 400) 추가

domain/member/src/main/kotlin/com/kbap/domain/member/
├── MemberService.kt                      # [수정] validatedImageUrl(형식·길이·허용 호스트)·validatedSpiciness(0~10)
│                                         #        + 온보딩/수정 병합 반영, @Value 로 허용 호스트 목록 주입(기본 빈 목록)
├── model/MemberProfile.kt                # [수정] profileImageUrl: String? 추가 (of/empty) — 맵기는 기존재
├── model/MemberProfileJson.kt            # [수정] profileImageUrl 필드 추가 (기본 null — 기존 row 호환)
├── dto/MemberProfileInput.kt             # [수정] profileImageUrl: String?·spicinessPreference: Int? (온보딩 선택 입력)
├── dto/ProfileUpdateInput.kt             # [수정] profileImageUrl: String?·spicinessPreference: Int? (미전송=유지)
└── dto/MyProfileResult.kt                # [수정] profileImageUrl·spicinessPreference 포함

domain/member/src/test/kotlin/com/kbap/domain/member/
└── MemberServiceTest.kt                  # [수정] 온보딩 등록/미등록·수정 교체/유지/제거·검증 거절 시나리오 (사진·맵기)

app/api/src/main/kotlin/com/kbap/app/api/member/
├── OnboardingRequest.kt                  # [수정] profileImageUrl·spicinessPreference 선택 필드
├── ProfileUpdateRequest.kt               # [수정] profileImageUrl·spicinessPreference 선택 필드
├── MyProfileResponse.kt                  # [수정] profileImageUrl·spicinessPreference 포함
└── MemberApi.kt                          # [수정] swagger 문서 갱신(필드 설명)

app/api/src/main/resources/
└── application-*.yml                     # [수정] prod(·staging) 에 kbap.member.profile-image-allowed-hosts: CDN 도메인

app/api/src/test/kotlin/com/kbap/app/api/member/
└── MemberControllerTest.kt               # [수정] MockMvc — 온보딩/조회/수정(유지·교체·제거)·불합격 URL 400
```

**Structure Decision**: 기존 member 수직 슬라이스에 필드를 얹는 순수 확장 — 신규 파일 0건(테스트 포함 전부 기존 파일 수정), 신규 모듈·엔드포인트·마이그레이션 없음.

## Complexity Tracking

> 위반 없음 — 해당 없음.
