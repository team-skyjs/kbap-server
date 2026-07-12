# Implementation Plan: 회원 랭킹 산정 및 조회

**Branch**: `kb-123-member-ranking` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-123-member-ranking/spec.md`

## Summary

회원의 활동량(리뷰 수·리뷰한 고유 음식 수·스캔 수)을 점수로 환산해 7단계 등급으로 매핑하고, 두 곳에서 노출한다 — 기존 프로필 조회 응답에 **랭킹 요약**을 얹어 프로필 탭을 한 번의 호출로 그리게 하고, **랭킹 상세 조회**(`GET /api/v1/members/me/ranking`)를 새로 열어 점수 내역(breakdown)을 준다.

산정 로직은 `:core:member` 의 순수 값 객체(`MemberRanking` + `RankingTier`)로 두고 **평면 카운트 3개만 입력**받는다 — 다른 컨텍스트 타입을 들이지 않아 원칙 II 를 지키고, 등급 경계·공식은 Spring 없이 단위 테스트로 고정된다. 조합은 `:application:client` 에서만 한다.

**스캔 횟수는 메뉴판 1장 = 1회다.** `scan_history` 는 매칭된 음식마다 행이 생기므로 횟수 집계에 쓸 수 없고, 지금 어디서도 "스캔 횟수"를 세고 있지 않다. 그래서 회원당 1행짜리 카운터 테이블 `member_ranking`(신규 — Flyway 마이그레이션 1건)을 두고, `ScanUseCase.assessMenuBoard` 가 스캔 1회마다 `increaseScanCount` 를 호출해 올린다(매칭 결과와 무관하게 1회). 카운트업은 `INSERT ... ON DUPLICATE KEY UPDATE` 로 원자적이라 read-modify-write 경합이 없다.

리뷰 도메인(`:core:review`)은 아직 빈 placeholder 라 리뷰 수·고유 음식 수는 **0으로 고정**된다. 산정 함수의 입력이 평면 카운트이므로, 리뷰 기능이 생기면 유스케이스에서 값을 채워 넣는 것만으로 반영된다(공식·등급 표는 손대지 않는다).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web, data-jpa), springdoc-openapi. 신규 라이브러리 없음.

**Storage**: MySQL. **신규 테이블 1개** — `member_ranking`(회원당 1행 카운터: `member_id` 유니크, `scan_count`). 점수 자체는 저장하지 않고 조회 시점에 카운트로 계산한다(파생 점수 컬럼·캐시 없음).

**Testing**: Kotest `BehaviorSpec`(given/`when`/then 한국어). 도메인 단위 테스트(`:core:member`), 유스케이스 페이크 테스트(`:application:client`), MockMvc + MySQL Testcontainers 통합 테스트(`:app:api`), 영속 어댑터 테스트(`:infra:persistence`).

**Target Platform**: `:app:api` web bootJar (기존 회원 API 그룹에 추가).

**Project Type**: 멀티모듈 모놀리스 백엔드 (ADR-0008).

**Performance Goals**: 랭킹 조회는 유니크 키 단건 SELECT 1회. 스캔 시에는 카운터 upsert 1회가 추가된다.

**Constraints**: 랭킹 값은 조회 시점 계산(SC-006 — 활동 직후 즉시 반영). 등급 안정 키·점수 상수는 FE 번역·정책이 의존하는 계약값이라 변경 금지.

**Scale/Scope**: MVP. 회원당 랭킹 카운터 1행.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ | 모든 task 를 Red(실패 테스트) → Green → Refactor 로 진행한다. 등급 경계값·검증 케이스(128점/explorer/52)를 도메인 단위 테스트로 먼저 고정한다. |
| II. Bounded Contexts | ✅ | 랭킹은 member 컨텍스트가 소유한다. 산정 입력은 **평면 카운트 3개**(Int)뿐이라 member 가 scan·review 타입을 import 하지 않는다. 스캔 카운트 조회 + 랭킹 산출의 **조합은 `:application:client` 에서만** 한다. |
| III. Layered Dependency | ✅ | `app:api` → `application:client` → `core:member`/`core:scan` → `core:kernel`. 유스케이스는 `MemberRankingRepository` **port** 로만 카운터를 읽고 올린다(구현체 미참조). |
| IV. Persistence Encapsulation | ✅ | 카운터 엔티티·upsert 쿼리·어댑터는 모두 `:infra:persistence` 에 둔다(`MemberRankingJpaEntity`·`MemberRankingJpaRepository`·`MemberRankingRepositoryAdapter`). application·app:api 는 JPA 타입을 import 하지 않고 `MemberRankingRepository` port 만 본다. |
| V. Domain Content Language | ✅ | 서버는 등급 **안정 키**(newcomer …)와 레벨만 내려주고 번역된 등급명을 만들지 않는다(9개 언어 번역은 FE i18n). 음식 콘텐츠 번역 정책과 무관하다. |

**위반 없음** → Complexity Tracking 비어 있음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-123-member-ranking/
├── plan.md              # 이 파일
├── spec.md              # /speckit-specify 산출물
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── member-ranking-api.md   # Phase 1 (응답 계약)
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출물
```

### Source Code (repository root)

```text
core/member/src/main/kotlin/com/meogo/core/member/
├── MemberRanking.kt              # 신규 — 점수·등급·다음 등급·남은 점수·내역 (순수 값 객체)
└── RankingTier.kt                # 신규 — 7단계 등급 enum(안정 키 + 레벨 + 진입 점수)
core/member/src/test/kotlin/com/meogo/core/member/
└── MemberRankingTest.kt          # 신규 — 공식·경계값·최고 등급

core/member/src/main/kotlin/com/meogo/core/member/
└── MemberRankingRepository.kt    # 신규 — increaseScanCount / scanCountOf port

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/
├── MemberRankingJpaEntity.kt         # 신규 — member_ranking (member_id 유니크, scan_count)
├── MemberRankingJpaRepository.kt     # 신규 — 원자적 upsert(ON DUPLICATE KEY UPDATE) + 카운트 조회
└── MemberRankingRepositoryAdapter.kt # 신규 — port 구현
infra/persistence/src/test/kotlin/com/meogo/infra/persistence/member/
└── MemberRankingRepositoryAdapterTest.kt # 신규 — 카운트업·격리·미기록 회원 0

app/api/src/main/resources/db/migration/
└── V2026.07.12.23.14.05__create_member_ranking_table.sql # 신규

application/client/src/main/kotlin/com/meogo/application/client/scan/usecase/
└── ScanUseCase.kt                # 수정 — 스캔 1회당 increaseScanCount 호출

application/client/src/main/kotlin/com/meogo/application/client/member/
├── MemberRankingUseCase.kt       # 신규 — 회원 존재 확인 + 카운트 수집 + MemberRanking 산출
├── MemberProfileUseCase.kt       # 수정 — getMyProfile 이 랭킹 요약을 함께 반환
└── dto/MyProfileResult.kt        # 수정 — 랭킹 요약 필드 추가
application/client/src/test/kotlin/com/meogo/application/client/member/
├── MemberRankingUseCaseTest.kt   # 신규 — 페이크 리포지토리
└── MemberProfileUseCaseTest.kt   # 수정 — 프로필 결과의 랭킹 요약

app/api/src/main/kotlin/com/meogo/app/api/member/
├── MemberApi.kt                  # 수정 — GET /me/ranking swagger 문서
├── MemberController.kt           # 수정 — GET /me/ranking 핸들러(@AuthMemberId)
├── MyProfileResponse.kt          # 수정 — ranking 요약 필드
└── MemberRankingResponse.kt      # 신규 — 상세 응답(요약 + breakdown)
app/api/src/test/kotlin/com/meogo/app/api/member/
└── MemberControllerTest.kt       # 수정 — 프로필 랭킹 요약·상세·401·요약↔상세 일치
```

**Structure Decision**: 기존 회원 API 그룹(`/api/v1/members`)에 상세 조회를 추가하고, 랭킹 도메인은 member 컨텍스트에 둔다. 새 모듈은 만들지 않으며, 신규 영속 자산은 카운터 테이블 `member_ranking` 하나다.

## Design Decisions (핵심)

1. **랭킹 도메인 위치 = `:core:member`.** 랭킹은 "회원의 등급"이므로 member 가 소유한다. 입력을 평면 카운트로 제한해 scan·review 컨텍스트와 결합하지 않는다(원칙 II).

2. **스캔 횟수는 카운터 테이블로 센다(스캔 이력 집계 아님).** 메뉴판 1장 = 1회라는 정책 확정에 따라 `scan_history` 행 수는 쓸 수 없다(음식마다 행이 생긴다). 스캔 시 카운트업하는 `member_ranking` 카운터를 두고 `ScanUseCase` 가 호출한다. 리뷰 카운트도 나중에 같은 테이블에 컬럼으로 붙일 수 있다.

3. **리뷰 카운트는 포트를 만들지 않고 0을 넣는다.** 구현이 하나도 없는 인터페이스(+0을 반환하는 가짜 어댑터)는 지금 아무 일도 하지 않으면서 파일만 늘린다. 산정 함수가 카운트를 인자로 받으므로, 리뷰 도메인이 생기면 유스케이스에서 리포지토리 호출로 값을 채우기만 하면 되고 **공식·등급 표·응답 계약은 그대로다**(FR-011 충족). Jira DoD 의 "집계 포트 정의"보다 한 단계 더 미룬 선택이다.

4. **프로필 조회는 랭킹 유스케이스를 호출한다.** 회원 조회가 한 번 더 일어나는 대신(SELECT 1회 추가) 산정 경로가 하나로 유지돼 프로필 요약과 상세가 어긋날 수 없다(FR-008). 이 중복 조회가 문제되면 그때 카운트만 넘겨받는 형태로 좁힌다.

5. **인증 설정 변경 없음.** `JwtAuthenticationFilter` 가 이미 `/api/v1/members/*` 전체를 덮으므로 새 엔드포인트는 자동으로 인증이 강제된다(FR-009).

## Complexity Tracking

> Constitution Check 위반 없음 — 비어 있음.
