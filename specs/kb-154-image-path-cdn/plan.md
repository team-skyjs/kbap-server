# Implementation Plan: 이미지 참조는 CDN 도메인 없이 경로만 저장하고 응답 조립 시 조합

**Branch**: `kb-154-image-path-cdn` | **Date**: 2026-07-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-154-image-path-cdn/spec.md`

## Summary

프로필 사진·음식 이미지 참조를 DB 에 CDN 도메인 없는 경로(키)만 저장하고, 응답 조립 시 서비스 레이어가 설정된 CDN 베이스(`kbap.storage.public-base-url` — KB-145 재사용)를 조합해 완전한 URL 로 내려준다. 조합 로직은 `:core` 의 Spring-free 헬퍼 `ImageUrls.resolve(base, ref)` 하나로 모으고, 각 조립 지점(MemberService·FoodService·HomeApplicationService)이 `@Value` 로 베이스를 주입받아 호출한다. 프로필 사진 입력 검증은 "https URL + 허용 호스트" 에서 "경로(전체 URL 거부)" 로 대체하고, 허용 호스트 인프라(`profile-image-allowed-hosts`)는 폐기한다. DB 스키마·Flyway·모듈 그래프 무변경.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1 — 신규 의존성 0

**Storage**: MySQL — 스키마 무변경 (member.profile JSON 의 `profileImageUrl` 키, food.image_ref 컬럼 재사용; 값 의미만 경로로)

**Testing**: Kotest BehaviorSpec — :core 단위(`ImageUrlsTest`) + :domain:member 단위(`MemberProfileTest` 보강) + :app:api 통합(MockMvc, 기존 test yml `public-base-url: https://cdn.test` 재사용)

**Target Platform**: `:app:api` (배치는 MemberService/FoodService 미탑재 — 영향 없음)

**Project Type**: 기존 멀티모듈 모놀리스 내 수정 — 신규 모듈 0

**Performance Goals**: 해당 없음(문자열 접합 — 순수 로컬 연산)

**Constraints**: 클라이언트 API 필드명 불변(`profileImageUrl`·`imageRef`) — 값 의미만 변경. 레거시 절대 URL 행은 조립 시 그대로 통과(데이터 마이그레이션 0)

**Scale/Scope**: 프로덕션 파일 ~10개 수정 + :core 헬퍼 1개 신규, Flyway 0, 신규 API 0

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First (NON-NEGOTIABLE) | ✅ | Red 진입점: `ImageUrlsTest`(신규)·`MemberProfileTest` 경로 검증 케이스(수정)·`MemberControllerTest`/food 통합 케이스(수정) 를 먼저 작성해 실패 확인 후 구현 |
| II. Bounded Contexts | ✅ | 조합 헬퍼는 여러 컨텍스트가 공유하는 유틸 → `:core` 배치(원칙 II 의 공유 vocabulary 규정). member·food 는 각자 자기 조립 지점에서 호출 — 도메인 간 신규 의존 0 |
| III. Layered Dependency Direction | ✅ | 의존 방향 변화 없음. `@Value` 프로퍼티 주입은 MemberService 의 기존 선례(`profile-image-allowed-hosts`) 답습 |
| IV. Persistence Encapsulation | ✅ | 리포지토리·엔티티 접근 구조 무변경 |

**Post-design re-check**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-154-image-path-cdn/
├── spec.md
├── plan.md              # this file
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/api-changes.md
└── tasks.md             # /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/image/ImageUrls.kt                # 신규 — resolve(base, ref) Spring-free
core/src/test/kotlin/com/kbap/core/image/ImageUrlsTest.kt            # 신규 Red

domain/member/src/main/kotlin/com/kbap/domain/member/
├── model/MemberProfile.kt          # validatedImageUrl → validatedImagePath (전체 URL 거부, allowedImageHosts 폐기)
├── model/Member.kt                 # completeOnboarding/updateProfile 시그니처에서 allowedImageHosts 제거
├── MemberService.kt                # profile-image-allowed-hosts @Value 제거, public-base-url @Value 추가, getMyProfile 조립
└── dto/MyProfileResult.kt          # of(...) 에 resolved URL 전달
domain/member/src/test/.../model/MemberProfileTest.kt               # 경로 검증 케이스로 교체

domain/food/src/main/kotlin/com/kbap/domain/food/
├── FoodService.kt                  # public-base-url @Value 추가, getDetail·foodPage 조립
└── dto/FoodSummaryView.kt          # from(...) 에 resolved imageRef 전달(파라미터 추가)

application/src/main/kotlin/com/kbap/application/home/HomeApplicationService.kt  # FoodSummaryView.from 호출부 조합

app/api/src/main/kotlin/com/kbap/app/api/member/MemberApi.kt        # Swagger 문구 — 경로 입력으로
core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt               # MEMBER-008 메시지 문구
app/api/src/main/resources/application-{prod,staging}.yml           # profile-image-allowed-hosts 제거

app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt          # 저장=경로·응답=조합 URL·전체URL 400
app/api/src/test/kotlin/com/kbap/app/api/member/ProfileImageHostRestrictionTest.kt  # 삭제(허용 호스트 폐기)
app/api/src/test/kotlin/com/kbap/app/api/food/…                     # 음식 응답 조합 URL 통합 검증
```

**Structure Decision**: 기존 모듈 구조 그대로. 유일한 신규 파일은 `:core` 의 `ImageUrls`(+테스트) — 여러 컨텍스트(member·food·application)가 공유하는 순수 함수라 `:core` 가 정위치(헌법 II).

## 핵심 설계 결정 (요약 — 상세는 research.md)

1. **CDN 베이스 프로퍼티 = `kbap.storage.public-base-url` 재사용** (KB-145 presigned 발급의 publicUrl 과 동일 출처 — 신규 프로퍼티 0). spec 의 `kbap.image-upload.*` 표기는 조사 후 정정.
2. **조합 지점 = 서비스 레이어 3곳**: `MemberService.getMyProfile`, `FoodService.getDetail`·`foodPage`, `HomeApplicationService.loadHome`. 각자 `@Value("\${kbap.storage.public-base-url:}")` 주입(기본 빈 문자열 — 미설정 환경·배치 안전).
3. **`ImageUrls.resolve(base, ref)`** (`:core`, Spring-free): `ref == null → null` / `ref` 가 `http(s)://` 시작 → 그대로(레거시 통과) / `base` 빈 값 → `ref` 그대로 / 그 외 → `base`·`ref` 사이 슬래시 정규화 접합.
4. **입력 검증 교체**: `MemberProfile.validatedImagePath` — trim, 빈 문자열→null(제거 센티널 유지), 512 초과·`http(s)://` 시작 → `MEMBER-008`. `allowedImageHosts` 파라미터 체인(Member·MemberProfile·MemberService)과 `kbap.member.profile-image-allowed-hosts` yml·`ProfileImageHostRestrictionTest` 폐기.
5. **이름 불변**: JSON 키 `profileImageUrl`(DB 저장 키 — 리네임=데이터 마이그레이션이라 유지), API 요청/응답 필드 `profileImageUrl`·`imageRef` 유지 — 값 의미만 변경.
