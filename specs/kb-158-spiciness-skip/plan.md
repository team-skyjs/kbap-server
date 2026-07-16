# Implementation Plan: 맵기 선호 미설정(스킵) 허용 — -1 센티널

**Branch**: `kb-158-spiciness-skip` | **Date**: 2026-07-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-158-spiciness-skip/spec.md`

## Summary

KB-147 이 추가한 맵기 선호(spicinessPreference, 0~10 필수·기본값 5)를 **미설정 가능**하게 바꾼다. 미설정은 -1 센티널로 표현한다. 클라이언트 계약(사용자 확인 2026-07-16·17): 스킵/"설정 안 함"은 **-1 명시 전송**이고, **온보딩에선 맵기 선호가 필수 필드**(-1~10 반드시 전송, 미전송=400 COMMON-002)다. 프로필 수정 API 는 여러 화면이 공용으로 쓰므로 미전송(null)=유지 규약(KB-124) 불변.

**핵심 성질: 코드 변경이 전부 기존 값 흐름 위에서 상수·검증·문구(+온보딩 진입점 한 줄)만 바뀐다** — DB 스키마·Flyway·엔티티 구조·모듈 의존 그래프·DTO 시그니처 전부 무변경. 변경 지점은 네 곳:

1. **`:domain:member` `MemberProfile`** — 허용 집합을 `{-1} ∪ 0..10` 으로 확장(`init` require + `validatedSpiciness`), 기본 상수 `DEFAULT_SPICINESS_PREFERENCE(5)` → `SPICINESS_UNSET(-1)` 로 대체(`empty()`·`MemberProfileJson` 기본값이 이를 따라감).
2. **온보딩 경로 필수화 — `OnboardingRequest`·`MemberProfileInput`·`Member.completeOnboarding` 의 spicinessPreference 를 non-null `Int` 로.** nickname 등 다른 필수 필드와 동일 패턴 — 누락은 역직렬화 단계에서 400 COMMON-002. 항상 명시 값이 흐르므로 배포 전 가입 회원의 저장값 5 잔존 회귀(Codex 리뷰 발견)도 구조적으로 소멸한다. PATCH 경로(`ProfileUpdateInput`, `Int?`)의 null=유지 규약은 불변.
3. **`:core` `ErrorCode`** — MEMBER-009 메시지에 -1(미설정) 허용 반영.
4. **`:app:api` `MemberApi`** — 온보딩·프로필 수정·조회 Swagger 문구에 -1 계약 명시.

값 흐름: 온보딩 미전송 → 400 COMMON-002(역직렬화 거절) / -1 명시 → `validatedSpiciness(-1)` 통과 → 저장 / 프로필 수정 미전송(null) → 기존 값 유지(불변 규약) / 수정 -1 명시 → 미설정 복귀.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), 기존 스택 그대로 — 신규 의존성 0

**Storage**: MySQL — `member.profile` JSON 컬럼 안의 `spicinessPreference` 정수 값에 -1 이 들어갈 뿐, 스키마·Flyway 무변경

**Testing**: Kotest BehaviorSpec (단위) + MockMvc `@SpringBootTest` MySQL Testcontainers (통합)

**Target Platform**: Linux server (기존 `:app:api` bootJar)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스)

**Performance Goals**: 해당 없음 — 검증 로직 상수 비교 한 줄 추가 수준

**Constraints**: 프로필 부분 수정 규약(미전송=유지·부분 저장 없음) 불변, 기존 회원 저장 값 소급 변환 없음

**Scale/Scope**: main 3파일 + Swagger 문구 1파일, 테스트 3파일 보강 — `:app:batch`·타 도메인 범위 밖

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | `MemberProfileTest`(단위)·`MemberControllerTest`(MockMvc)에 -1 허용/저장/조회·경계 거절 테스트를 먼저 추가해 Red 확인 후 구현 |
| II. Bounded Contexts | ✅ PASS | member 컨텍스트 내부 값 정책 변경 — 타 도메인 무관(`food.spiciness` 는 별개 컬럼), 도메인 간 의존 무변경 |
| III. Layered Dependency Direction | ✅ PASS | 의존 그래프 무변경 — `:core`(ErrorCode 메시지)·`:domain:member`·`:app:api`(문구) 기존 방향 그대로 |
| IV. Persistence Encapsulation | ✅ PASS | 엔티티·리포지토리 무변경, `MemberProfileJson` 은 member 모듈 내부에 유지 |
| V. Domain Content Language Policy | ✅ PASS | 음식 콘텐츠 번역과 무관(회원 설정 값) |

**Post-Design 재평가 (Phase 1 후)**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-158-spiciness-skip/
├── spec.md              # /speckit-specify 산출물
├── plan.md              # 이 파일
├── research.md          # Phase 0 산출물
├── data-model.md        # Phase 1 산출물
├── quickstart.md        # Phase 1 산출물
├── contracts/
│   └── member-api.md    # 온보딩·프로필 수정·조회 계약 delta
└── tasks.md             # /speckit-tasks 산출물 (이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/error/
└── ErrorCode.kt                          # [수정] MEMBER-009 메시지 — -1(미설정) 허용 반영

domain/member/src/main/kotlin/com/kbap/domain/member/model/
├── MemberProfile.kt                      # [수정] SPICINESS_UNSET(-1) 상수, init require·validatedSpiciness 허용 집합 확장, empty() 기본 -1
└── MemberProfileJson.kt                  # [수정 없음 목표] 기본값이 MemberProfile 상수를 참조 — 상수 교체 시 이름만 따라 바뀜

app/api/src/main/kotlin/com/kbap/app/api/member/
└── MemberApi.kt                          # [수정] 온보딩·프로필 수정·조회 Swagger 문구에 -1 계약 명시

domain/member/src/test/kotlin/com/kbap/domain/member/model/
├── MemberProfileTest.kt                  # [보강] -1 허용·경계(-2 거절)·updatedWith(-1)=미설정 복귀·null=유지
└── MemberTest.kt                         # [보강] 온보딩 생략→-1, 기존 5→10 검증 갱신

app/api/src/test/kotlin/com/kbap/app/api/member/
└── MemberControllerTest.kt               # [보강] 온보딩 생략/-1 → 조회 -1, 수정 -1 → 미설정 복귀, 수정 생략 → 유지, -2/11 → 400 MEMBER-009
```

**Structure Decision**: 신규 파일 0. 변경은 member 도메인 모델(값 정책 소유자)에 집중하고, DTO 는 이미 `Int? = null` 이라 -1 이 그대로 통과한다. `MemberProfileJson` 의 직렬화 기본값은 `MemberProfile` 상수를 참조하고 있어 상수 하나만 바꾸면 온보딩 기본·레거시 JSON 역직렬화가 함께 정책을 따라간다.

## 설계 결정 (구현 축)

1. **상수 교체 — `DEFAULT_SPICINESS_PREFERENCE(5)` 폐기, `SPICINESS_UNSET(-1)` 도입.** "기본값"과 "미설정"이 같은 값(-1)이 됐으므로 상수는 하나만 남긴다(같은 값의 상수 두 개 금지). `empty()`·`MemberProfileJson` 기본값·테스트 참조를 일괄 리네임.
2. **검증 집합 확장 — `spicinessPreference == SPICINESS_UNSET || spicinessPreference in SPICINESS_RANGE`.** `init` require 와 `validatedSpiciness` 두 곳 동일 규칙(둘 다 이 조건으로). `SPICINESS_RANGE(0..10)` 는 "설정된 값"의 범위로 의미 유지.
3. **생략 의미론 — 온보딩은 타입으로 필수 강제, 수정은 null=유지.** 온보딩 spicinessPreference 는 non-null `Int`(FR-002, 사용자 확인 2026-07-17) — 누락이 컴파일/역직렬화 레벨에서 차단되므로 "생략 시 어떤 값을 넣을까" 분기 자체가 없다. 초기안(생략→-1 저장)은 배포 전 가입 회원의 저장값 5 잔존 회귀(Codex 리뷰 발견) 때문에 진입점 치환이 필요했으나, 필수화로 그 코드도 불필요해졌다. 수정(PATCH): null=유지 규약 그대로, -1 명시=미설정 복귀.
4. **레거시 JSON 역직렬화 — 필드 부재 시 -1 로 해석(방어적).** DB 검토(database-expert) 결과 키 부재 행은 실존하지 않는다 — consolidation 마이그레이션이 전 행에 키를 백필(5)했고 non-null 직렬화라 이후에도 키가 항상 존재한다. 기본값 -1 은 향후 키 없는 JSON 유입에 대한 방어이며, 기존 회원 표시값 변화 0건(FR-006 충족). 상세: research.md D4.
5. **응답 타입 불변 — `spicinessPreference: Int` 유지, 미설정이면 -1.** null 화하지 않는다(스펙: 저장·조회·요청 모두 -1 통일).

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
