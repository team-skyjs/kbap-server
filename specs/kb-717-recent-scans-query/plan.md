# Implementation Plan: 홈 최근 스캔 조회 쿼리 DB 레벨 최적화

**Branch**: `fix/kb717-recent-scans-query` | **Date**: 2026-10-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-717-recent-scans-query/spec.md`

## Summary

홈 최근 스캔 조회가 회원의 스캔 행 수에 비례해 느려진다(1년치 시드, 70 rps 부하 중 평균 2,803 ms). dev 의 실행 계획을 보면 세 가지가 겹친다. 음식이 드라이빙 테이블이 되고, 소프트 삭제 조건 때문에 인덱스가 커버링이 못 되고, 임시 테이블에 음식 행 전체가 실린다.

테이블은 건드리지 않고 두 가지만 바꾼다([research.md](research.md)).

1. **쿼리 형태**: 스캔 이력을 먼저 `(food_id, max(created_at))` 로 묶고 그 결과를 음식과 PK 로 결합한다. 리포지토리 시그니처는 그대로다.
2. **인덱스**: `(member_id, food_id, created_at)` 를 `(member_id, status, food_id, created_at)` 로 교체해 선집계를 인덱스만으로 끝낸다.

전후를 같은 시드·같은 부하로 측정해 PR 에 적는다. 목표에 못 미치면 한계 수치를 근거로 읽기 모델 후속 태스크를 만든다.

## Technical Context

**Language/Version**: Kotlin 2.3 / Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA (Hibernate 7.4), Flyway

**Storage**: MySQL 8.4 (dev RDS 8.4.9)

**Testing**: Kotest BehaviorSpec + `@IntegrationTest` (MySQL Testcontainers, Flyway on)

**Target Platform**: api bootJar (ECS, 블루/그린 배포)

**Project Type**: web-service (모듈러 모놀리스 `:common`·`:api`·`:batch`)

**Performance Goals**: 1년치 시드·70 rps 에서 이 쿼리 DB 평균 100 ms 미만, 무경합 10 ms 미만

**Constraints**: 테이블 추가·컬럼 변경 금지, 홈 응답 계약 불변, 홈 SQL 문 수 유지, 배포 중 구 코드와 공존

**Scale/Scope**: scan_history 17만 행, 측정 회원 4,477행·853종. 변경 파일 3개 + 마이그레이션 1개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | 통과 | 기존 `ScanHistoryRepositoryTest` 5개 시나리오가 계약을 고정한다. 빠진 두 경우(삭제된 스캔 행, 삭제된 음식)를 쿼리 변경 전에 먼저 추가한다 |
| II. Bounded Contexts | 통과 | scan → food 참조는 기존 방향 그대로다. 새 의존 없음 |
| III. Layered Dependency | 통과 | 리포지토리 쿼리와 엔티티 인덱스 선언만 바뀐다. 서비스·컨트롤러 무변경 |
| IV. Persistence Ownership | 통과 | 인덱스 변경은 api 의 Flyway 마이그레이션(timestamp 버전, 순서 비의존)으로 한다. 엔티티 `@Index` 를 같이 맞춘다 |
| V. Content Language | 해당 없음 | |

설계 후 재확인: 위반 없음. 격리수준·캐시·읽기 모델·힌트 등 추가 장치를 넣지 않는다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-717-recent-scans-query/
├── plan.md
├── research.md
├── quickstart.md
└── tasks.md          # /speckit-tasks 가 생성
```

`data-model.md` 와 `contracts/` 는 만들지 않는다. 엔티티·테이블·응답 계약이 바뀌지 않는다.

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/scan/
├── ScanHistoryJpaRepository.kt        # findRecentScannedFoods 쿼리 교체
└── model/ScanHistory.kt               # @Index 선언을 새 인덱스로

api/src/main/resources/db/migration/
└── V<생성 시각>__scan_history_member_status_food_recent_index.sql   # 인덱스 교체

api/src/test/kotlin/com/kbap/api/scan/
└── ScanHistoryRepositoryTest.kt       # 삭제된 스캔 행·삭제된 음식 시나리오 추가
```

**Structure Decision**: 기존 구조 그대로다. `FoodService.getRecentScannedFoods` 와 홈 서비스·컨트롤러는 고치지 않는다.

## 설계 결정

- **쿼리(D1)**: JPQL 파생 서브쿼리로 선집계 후 `Food` 를 PK 결합한다. 반환 프로젝션 `RecentScannedFood(food, scannedAt)` 와 `Pageable` 파라미터를 유지한다. Hibernate 가 낸 SQL 을 로그로 확인해 의도한 형태가 아니면 네이티브 쿼리(id·시각 반환) + 기존 `findByIdIn` 으로 바꾸고, SQL 문 수가 1 늘어난 사실을 PR 에 적는다.
- **인덱스(D2)**: 한 ALTER 에서 구 인덱스 삭제와 새 인덱스 생성을 같이 한다(선례: `V2026.09.30.14.56.23`). 구 코드의 모든 스캔 쿼리가 `member_id`·`status` 등치를 가지므로 새 인덱스 위에서도 동작한다.
- **범위 밖**: 내 스캔 목록 페이지·검색 쿼리는 고치지 않는다. 새 인덱스로 느려지지 않는지만 EXPLAIN 으로 확인한다(FR-006).
- **동률 순서**: 지금처럼 정하지 않는다.

## 검증

[quickstart.md](quickstart.md) 의 네 단계다.

1. dev 기준선 기록(변경 전 플랜·슬로우 로그 수치).
2. 로컬 시드로 후보 넷을 `EXPLAIN ANALYZE` 비교해 선택 근거 표를 만든다.
3. `./gradlew :api:test` 전체 통과.
4. dev 배포 후 같은 k6 부하로 슬로우 로그 재측정, 전후 수치를 PR 본문에 적는다. 미달이면 후속 태스크를 만든다.

## 추가 결정 (2026-10-05, 사용자 지시)

- **후보에 CTE 와 읽기 모델을 넣는다.** 선집계는 CTE 로 쓴다(MySQL 8 에서 한 번만 참조되는 CTE 는 파생 테이블과 같은 플랜이고, 읽기가 낫다). 읽기 모델 `member_scanned_food` 는 비교 대상으로 DDL·백필·조회 SQL 을 준비하되 이 작업에서 마이그레이션으로 만들지는 않는다. 쿼리 레벨이 목표에 못 미친다는 수치가 나오면 후속 태스크로 간다.
- **EXPLAIN 은 사용자가 직접 실행해 검증한다.** 이 작업은 비교용 SQL 묶음(`scripts/perf/recent-scans-candidates.sql`)을 준비하는 데까지 하고, 실행·판정·dev 통계 갱신(`ANALYZE TABLE`)·부하 재측정은 사용자가 한다.
