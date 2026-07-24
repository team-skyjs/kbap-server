# Implementation Plan: 프로필 사진 필수화 — null·빈 문자열 불가, 미설정은 기본 이미지 경로 저장

**Branch**: `kb-188-profile-image-required` | **Date**: 2026-07-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-188-profile-image-required/spec.md`

## Summary

회원 프로필 사진 계약을 "선택(null 허용, 빈 문자열=제거)"에서 "필수(온보딩 필수 전송, 빈 문자열 불가)"로 바꾼다. 기본 이미지 선택은 클라이언트 책임 — 미설정 회원도 기본 이미지 경로 `/images/default/profile/profile-default-512.png` 를 명시 전송한다. 서버 변경 4지점: (1) `OnboardingRequest.profileImageUrl` non-null 타입 강제(미전송/null → Jackson 역직렬화 실패 → 기존 `GlobalExceptionHandler` 가 400 COMMON-002 — KB-158 nickname·spiciness 선례 그대로), (2) `MemberProfile.validatedImagePath` 의 빈 문자열 제거 센티널 폐기 → 빈 문자열이면 400 MEMBER-008(기존 전체 URL 거부·길이 512 검증 유지), (3) 프로필 수정은 null=유지 규약(KB-124) 불변 — 빈 문자열만 거부로 바뀜(검증 지점 (2) 하나로 온보딩·수정 동시 커버), (4) Flyway 마이그레이션으로 기존 null 행(`member.profile` JSON 키 부재·JSON null 포함, 소프트 삭제 행 포함)에 기본 경로 백필. 응답 조립(`ImageUrls.resolve`)·DB 스키마(DDL)·API 필드명 무변경.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Jackson(kotlin-module — non-null 파라미터 강제), Flyway(+flyway-mysql), springdoc-openapi

**Storage**: MySQL — `member.profile` JSON 컬럼의 `profileImageUrl` 키(스키마 DDL 변경 없음, 백필 UPDATE 1건)

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + MockMvc 통합(`@SpringBootTest`, MySQL Testcontainers). 도메인 단위: `MemberProfileTest`. 통합: `MemberControllerTest`(테스트 yml 은 Flyway off — 백필 SQL 은 마이그레이션 리소스를 읽어 직접 실행해 검증)

**Target Platform**: Linux 서버 (기존 배포 파이프라인 — KB-172)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스)

**Performance Goals**: 해당 없음 — 검증 로직 변경 + 1회성 UPDATE. 백필은 배포 시 Flyway 가 1회 실행(회원 행 규모상 수 초 내)

**Constraints**: 외부 API 필드명·응답 조립 무변경. 프로필 수정의 부분 수정 규약(null=유지) 불변. 공유/프로덕션 DB 적용 전 마이그레이션은 독립적·멱등하게 작성(out-of-order 전제)

**Scale/Scope**: 프로덕션 수정 5파일(`MemberProfile`·`MemberProfileInput`·`Member`·`OnboardingRequest`·`MemberApi`) + Flyway SQL 1건 신규. 테스트 수정 3파일(`MemberProfileTest`·`MemberControllerTest`·`ScenarioApiDriver`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ | 계약 변경 3지점(온보딩 필수화·빈 문자열 거부·백필)을 실패 테스트로 먼저 고정 — `MemberProfileTest` 빈 문자열 `shouldThrow`, `MemberControllerTest` 온보딩 미전송 400·빈 문자열 400·백필 SQL 적용 후 조회. Red 확인 후 구현 |
| II. Bounded Contexts | ✅ | 변경은 `:domain:member` 와 `:app:api` 에 국한. 타 도메인 참조 없음 |
| III. Layered Dependency Direction | ✅ | 의존 방향 변화 0 — 컨트롤러→도메인 서비스 기존 경로 그대로. 모듈 그래프 무변경 |
| IV. Persistence Encapsulation | ✅ | 엔티티·리포지토리 노출 변화 없음. 백필은 Flyway(스키마 owner=api)가 수행 — 코드가 아닌 스키마 계층 소관 |
| V. Domain Content Language Policy | ✅ | 음식 콘텐츠·번역 무관 |

**Post-design re-check**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-188-profile-image-required/
├── spec.md              # /speckit-specify 산출물
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 4건
├── data-model.md        # Phase 1 — MemberProfile 계약·JSON 키 백필
├── quickstart.md        # Phase 1 — 검증 런북
├── contracts/
│   └── member-api.md    # 온보딩·프로필 수정 API 계약 변경
└── tasks.md             # /speckit-tasks 산출물 (이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
domain/member/src/main/kotlin/com/kbap/domain/member/
├── model/MemberProfile.kt        # [수정] validatedImagePath: 빈 문자열 → throw MEMBER-008 (제거 센티널 폐기), 반환 String non-null 화
├── model/Member.kt               # [수정] completeOnboarding(profileImageUrl: String) non-null 화
└── dto/MemberProfileInput.kt     # [수정] profileImageUrl: String non-null 화 (기본값 제거)

app/api/src/main/kotlin/com/kbap/app/api/member/
├── OnboardingRequest.kt          # [수정] profileImageUrl: String non-null 화 → 미전송/null = COMMON-002
└── MemberApi.kt                  # [수정] Swagger — 온보딩 필수 명시·기본 경로 계약, 수정 API 3분법→2분법 문구·예시

app/api/src/main/resources/db/migration/
└── V2026.07.20.HH.mm.ss__backfill_default_profile_image.sql   # [신규] null/키 부재 행 백필 (생성 시각으로 명명)

domain/member/src/test/kotlin/com/kbap/domain/member/model/
└── MemberProfileTest.kt          # [수정] 빈 문자열 제거 → shouldThrow MEMBER-008 교체

app/api/src/test/kotlin/com/kbap/app/api/
├── member/MemberControllerTest.kt    # [수정] validBody 에 profileImageUrl 추가, 미전송 400·빈 문자열 400·백필 검증
└── scenario/ScenarioApiDriver.kt     # [수정] 온보딩한다() 에 profileImageUrl 기본 파라미터 추가
```

**Structure Decision**: 기존 멀티모듈 구조 그대로 — 신규 모듈·패키지 없음. 검증 로직의 유일 지점은 `MemberProfile.validatedImagePath`(온보딩 `completeOnboarding` 과 수정 `updatedWith` 가 공유)라 빈 문자열 거부는 한 곳 수정으로 두 경로를 동시 커버한다. 변경 없는 것: `ProfileUpdateRequest`/`ProfileUpdateInput`(null=유지 규약 유지 — nullable 그대로), `MemberProfileJson`(역직렬화 방어용 nullable 유지 — 온보딩 전 행은 구조적으로 null), `MemberService`·`MyProfileResponse`·`ImageUrls`(응답 조립 무변경), DB DDL·`:app:batch`.

## Complexity Tracking

> 위반 없음 — 해당 없음.
