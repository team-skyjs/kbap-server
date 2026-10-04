# Tasks: 홈 최근 스캔 조회 쿼리 DB 레벨 최적화

**Input**: `/specs/kb-717-recent-scans-query/` · Jira [KB-717](https://simhani1.atlassian.net/browse/KB-717)

**Tests**: 새 테스트 파일 없음. `ScanHistoryRepositoryTest` 에 빠진 두 시나리오를 쿼리 변경 전에 추가한다(현재 쿼리에서도 통과하는 계약 고정 테스트). 홈 통합 테스트 무수정.

**검증 분담 (2026-10-05 사용자 지시)**: EXPLAIN / EXPLAIN ANALYZE 실행과 플랜 판정은 사용자가 직접 한다. 태스크는 비교용 SQL 을 준비하는 데까지다. 후보에 CTE 형태와 읽기 모델(`member_scanned_food`)을 포함한다.

## Phase 1: 비교용 SQL 준비 (US2)

- [X] T001 [US2] `scripts/perf/recent-scans-candidates.sql` 작성 — 회원 3 기준으로 바로 실행할 수 있는 EXPLAIN ANALYZE 문 묶음: (A) 현재 쿼리, (B) CTE 선집계 후 음식 PK 결합, (C) B + 인덱스 `(member_id, status, food_id, created_at)`, (D) 윈도 함수로 최신 행부터 음식 중복 제거 + 인덱스 `(member_id, status, created_at desc, food_id)`, (E) 읽기 모델 `member_scanned_food` DDL·백필·조회. 인덱스·테이블을 만드는 문은 로컬 전용이라고 주석으로 표시하고 원복 문을 같이 둔다. 범위 밖 쿼리(목록 페이지·검색·`findLastScannedAt`)가 새 인덱스에서 느려지지 않는지 볼 EXPLAIN 문도 넣는다(FR-006)

## Phase 2: 계약 고정 테스트 (US1)

- [X] T002 [US1] `api/src/test/kotlin/com/kbap/api/scan/ScanHistoryRepositoryTest.kt` "최근 스캔 음식 조회" given 에 시나리오 2개 추가 — 삭제된(status=DELETED) 스캔 행만 있는 음식은 나오지 않는다 / 삭제된 음식은 나오지 않는다. 마지막 스캔 시각 값(`scannedAt`)이 가장 최근 행의 시각인지도 기존 "여러 번 스캔" 시나리오에서 단언한다
- [X] T003 [US1] `./gradlew :api:test` 로 추가 시나리오가 현재 쿼리에서 통과하는지 확인

## Phase 3: 쿼리·인덱스 교체 (US1)

- [X] T004 [US1] `common/src/main/kotlin/com/kbap/common/domain/scan/ScanHistoryJpaRepository.kt` — `findRecentScannedFoods` 를 CTE 선집계(`with recent as (select sh.foodId, max(sh.createdAt) … group by sh.foodId)`) 후 `Food` PK 결합·READY 필터·`order by scannedAt desc` 로 교체. 시그니처·반환 프로젝션 유지
- [X] T005 [P] [US1] `api/src/main/resources/db/migration/V<생성 시각>__scan_history_member_status_food_recent_index.sql` — 한 ALTER 에서 `idx_scan_history_member_food_recent` 삭제 + `idx_scan_history_member_status_food_recent (member_id, status, food_id, created_at)` 생성. `common/src/main/kotlin/com/kbap/common/domain/scan/model/ScanHistory.kt` 의 `@Index` 를 같이 맞춘다
- [X] T006 [US1] `./gradlew :api:test :common:test` 그린. Hibernate 가 낸 SQL 이 선집계 형태(파생/CTE 가 `scan_history` 만 읽음)인지 테스트 로그로 확인. 의도와 다르면 네이티브(id·시각) + `findByIdIn` 으로 바꾸고 SQL 문 수 증가를 기록
- [X] T007 [US1] 홈 테스트(`HomeControllerTest`·`HomeGuestTest`) `git diff --stat develop` 비어 있음 확인

## Phase 4: 검증·기록 (US2)

- [ ] T008 [US2] (사용자) `scripts/perf/recent-scans-candidates.sql` 로 EXPLAIN ANALYZE 비교 → 결과를 PR 본문 표(플랜·검사 행 수·임시 테이블/정렬 유무)로 정리
- [X] T009 커밋 → `open-draft-pr-to-develop`. PR 본문에 기준선, 후보 비교표 자리, 선택 근거, 범위 밖(목록 페이지·검색) 명시
- [ ] T010 [US2] (사용자) dev 배포 후 같은 시드·k6 70 rps 로 슬로우 로그 재측정 → 전후 수치를 PR·KB-717 DoD 에 반영. 평균 100 ms 또는 무경합 10 ms 에 못 미치면 한계 수치와 근거로 읽기 모델 후속 Jira 태스크 생성

## Dependencies

T001 ∥ T002 → T003 → (T004 ∥ T005) → T006 → T007 → T008 → T009 → T010
