# Implementation Plan: 회원 랭킹 산정 및 조회

**Branch**: `kb-123-member-ranking` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-123-member-ranking/spec.md`

## Summary

회원의 활동량(리뷰 수·리뷰한 고유 음식 수·스캔 수)을 점수로 환산해 7단계 등급으로 매핑하고, 두 곳에서 노출한다 — 기존 프로필 조회 응답에 **랭킹 요약**을 얹어 프로필 탭을 한 번의 호출로 그리게 하고, **랭킹 상세 조회**(`GET /api/v1/members/me/ranking`)를 새로 열어 점수 내역(breakdown)을 준다.

산정 로직은 `:core:member` 의 순수 값 객체 **`Ranking`**(+ `RankingTier`)에 있다 — 카운트 3종(`scanCount`·`reviewCount`·`uniqueReviewedFoodCount`)을 담고 점수·등급·다음 등급·항목별 점수를 스스로 파생한다. 다른 컨텍스트 타입을 들이지 않아 원칙 II 를 지키고, 등급 경계·공식은 Spring 없이 단위 테스트로 고정된다.

**랭킹은 `Member` 애그리거트의 하위 도메인이다.** `Member` 가 `val ranking: Ranking` 을 들고, 정책의 카운트 3종은 별도 테이블이 아니라 `member` 행의 컬럼(`scan_count`·`review_count`·`unique_reviewed_food_count`)으로 저장된다. 가입 시 `Ranking.initial()`(모두 0) + `DEFAULT 0` 이라 초기화가 자동이고, 탈퇴 시 회원과 함께 사라진다. 이후 카운트업만 친다 — 지금은 스캔만(`Member.recordScan()` → `Ranking.recordScan()`, 둘 다 불변), 리뷰 카운트는 리뷰 기능이 붙을 때 같은 방식으로 올린다.

**스캔 횟수는 메뉴판 1장 = 1회다.** `scan_history` 는 매칭된 음식마다 행이 생기므로 횟수 집계에 쓸 수 없다. `ScanUseCase.assessMenuBoard` 가 스캔 1회마다 `MemberRepository.increaseScanCount(memberId)` 를 호출한다(매칭 0건이어도 1회). **카운트업은 DB 에서 원자적으로 한다** — `update member set scan_count = scan_count + 1 where id = ?`(JPQL 벌크 업데이트). 회원을 읽어 +1 해서 저장하는 방식이면 같은 회원의 동시 스캔에서 두 트랜잭션이 같은 값을 읽고 같은 값을 써 스캔이 유실된다(lost update). 카운트는 **DB 가 소유**하므로 프로필 수정 경로(`applyDomain`)는 카운트 컬럼을 건드리지 않는다 — 오래된 `Member` 로 프로필을 저장해도 그 사이 오른 카운트를 덮어쓰지 않는다.

리뷰 도메인(`:core:review`)은 아직 빈 placeholder 라 리뷰 수·고유 음식 수는 **0으로 고정**된다. 산정 함수의 입력이 평면 카운트이므로, 리뷰 기능이 생기면 유스케이스에서 값을 채워 넣는 것만으로 반영된다(공식·등급 표는 손대지 않는다).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web, data-jpa), springdoc-openapi. 신규 라이브러리 없음.

**Storage**: MySQL. **`member` 테이블에 카운트 컬럼 3개 추가** — `scan_count`·`review_count`·`unique_reviewed_food_count`(모두 `INT NOT NULL DEFAULT 0`, Flyway 1건). 신규 테이블 없음. 점수·등급은 저장하지 않고 조회 시점에 카운트로 계산한다.

**Testing**: Kotest `BehaviorSpec`(given/`when`/then 한국어). 도메인 단위 테스트(`:core:member`), 유스케이스 페이크 테스트(`:application:client`), MockMvc + MySQL Testcontainers 통합 테스트(`:app:api`), 영속 어댑터 테스트(`:infra:persistence`).

**Target Platform**: `:app:api` web bootJar (기존 회원 API 그룹에 추가).

**Project Type**: 멀티모듈 모놀리스 백엔드 (ADR-0008).

**Performance Goals**: 랭킹 조회는 회원 단건 SELECT 로 끝난다(추가 쿼리 0 — 카운트가 회원 행에 있다). 스캔 시 UPDATE 1회(원자적 증가)가 추가된다.

**Constraints**: 랭킹 값은 조회 시점 계산(SC-006 — 활동 직후 즉시 반영). 등급 안정 키·점수 상수는 FE 번역·정책이 의존하는 계약값이라 변경 금지.

**Scale/Scope**: MVP. 회원당 랭킹 카운터 1행.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ | 모든 task 를 Red(실패 테스트) → Green → Refactor 로 진행한다. 등급 경계값·검증 케이스(128점/explorer/52)를 도메인 단위 테스트로 먼저 고정한다. |
| II. Bounded Contexts | ✅ | 랭킹은 `Member` 애그리거트의 하위 도메인(`Ranking` 값 객체 — 카운트 + 파생 계산)이다. 산정 입력은 **평면 카운트**뿐이라 member 가 scan·review 타입을 import 하지 않는다. 스캔 시 회원 카운트업의 **조합은 `:application:client`(`ScanUseCase`)에서만** 한다. |
| III. Layered Dependency | ✅ | `app:api` → `application:client` → `core:member`/`core:scan` → `core:kernel`. 유스케이스는 `MemberRepository` **port** 로만 회원을 읽고 저장한다(구현체 미참조). |
| IV. Persistence Encapsulation | ✅ | `scan_count` 컬럼 매핑·왕복은 `MemberJpaEntity`(+`MemberRepositoryAdapter`)에 갇힌다. 신규 리포지토리·엔티티가 없다. |
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
├── Member.kt                     # 수정 — val ranking: Ranking(가입 시 initial, 읽기 전용)
├── MemberRepository.kt           # 수정 — increaseScanCount(memberId) port
├── Ranking.kt                    # 신규 — 카운트 3종 + 점수·등급·다음 등급·항목별 점수 (순수 값 객체)
└── RankingTier.kt                # 신규 — 7단계 등급 enum(안정 키 + 레벨 + 진입 점수)
core/member/src/test/kotlin/com/meogo/core/member/
├── RankingTest.kt                # 신규 — 공식·경계값·최고 등급
└── MemberTest.kt                 # 수정 — 가입 시 0·recordScan 불변·프로필 갱신 시 보존

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/
├── MemberJpaEntity.kt            # 수정 — 카운트 3종 컬럼(applyDomain 은 카운트를 쓰지 않는다)
├── MemberJpaRepository.kt        # 수정 — @Modifying 원자적 증가 쿼리
└── MemberRepositoryAdapter.kt    # 수정 — increaseScanCount 구현(0행이면 MEMBER_NOT_FOUND)
infra/persistence/src/test/kotlin/com/meogo/infra/persistence/member/
└── MemberRepositoryAdapterTest.kt # 수정 — 가입 시 0·카운트업 영속·프로필 갱신 시 보존

app/api/src/main/resources/db/migration/
└── V2026.07.13.00.19.27__add_member_ranking_counts.sql # 신규 — scan/review/unique_reviewed_food count DEFAULT 0

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

**Structure Decision**: 기존 회원 API 그룹(`/api/v1/members`)에 상세 조회를 추가하고, 랭킹은 `Member` 애그리거트 안에 둔다. 새 모듈·새 테이블 없이 `member` 카운트 컬럼 3개만 는다.

## Design Decisions (핵심)

1. **랭킹은 `Member` 애그리거트 안의 값 객체 `Ranking` 이다.** 카운트 3종과 점수·등급 파생이 한 클래스에 모여 있고 `Member` 가 `val ranking: Ranking` 으로 소유한다(별도 `MemberRanking` 클래스는 흡수해 없앴다). 별도 카운터 애그리거트·리포지토리를 만들지 않으므로 "애그리거트 루트가 아닌 것에 리포지토리를 붙이는" 문제가 사라진다. 가입 시 0 초기화가 자동이고(`Ranking.initial()` + `DEFAULT 0`), 탈퇴 시 회원과 함께 사라진다.

2. **카운트업은 DB 원자적 증가로 한다.** `MemberRepository.increaseScanCount` port + JPQL 벌크 업데이트(`scan_count = scan_count + 1`). 회원 로드 → +1 → 저장 방식은 동시 스캔에서 lost update 가 나고, 락·버전을 얹으면 재시도 처리가 따라붙는다. 증가문 하나가 가장 짧고 정확하다. 도메인(`Member`·`Ranking`)은 카운트를 **읽기 전용**으로 들고(점수·등급 파생), 증가는 영속 계층이 담당한다.

3. **스캔 횟수는 메뉴판 1장 = 1회.** `scan_history` 행 수는 쓸 수 없다(음식마다 행이 생긴다). `ScanUseCase.assessMenuBoard` 호출당 1회 올리며, 매칭 결과가 하나도 없어도 센다.

4. **정책의 카운트 3종을 모두 컬럼으로 둔다.** 리뷰 도메인이 아직 없어 `review_count`·`unique_reviewed_food_count` 는 당분간 0에 머물지만, 랭킹 공식이 요구하는 원천 값이므로 자리를 미리 잡아 둔다 — 리뷰 기능이 붙으면 카운트업 호출만 추가하면 되고 마이그레이션·도메인·응답 계약은 손대지 않는다(FR-011 충족).

5. **프로필 조회는 랭킹 유스케이스를 호출한다.** 회원 조회가 한 번 더 일어나는 대신 산정 경로가 하나로 유지돼 프로필 요약과 상세가 어긋날 수 없다(FR-008).

6. **인증 설정 변경 없음.** `JwtAuthenticationFilter` 가 이미 `/api/v1/members/*` 전체를 덮으므로 새 엔드포인트는 자동으로 인증이 강제된다(FR-009).

## Complexity Tracking

> Constitution Check 위반 없음 — 비어 있음.
