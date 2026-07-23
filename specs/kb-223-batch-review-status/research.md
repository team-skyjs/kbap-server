# Research: 배치 완성 콘텐츠 PENDING_REVIEW 전이

Technical Context 에 NEEDS CLARIFICATION 은 없다. 설계 결정만 기록한다.

## D1. 전이 메서드 — 목적지 변경 + 리네임

- **Decision**: `Food.transitionToReadyIfComplete()` → `transitionToPendingReviewIfComplete()` 로 리네임하고, 완비 시 `contentStatus = PENDING_REVIEW` 로 전이한다.
- **Rationale**: 이름이 곧 계약이다(self-documenting 규약). READY 로 가지 않는 메서드가 `ToReady` 이름을 유지하면 오독한다. 호출부는 `FoodContentBatchConfig` writer 한 곳뿐이라 리네임 비용이 없다.
- **Alternatives considered**: 이름 유지 + 목적지만 변경(호출부 diff 0) — 이름 거짓말이라 기각. 파라미터로 목적지 주입 — 호출자가 하나뿐인데 유연성만 추가라 기각(YAGNI).

## D2. 완비 판정 가드 — `contentStatus != INCOMPLETE` 이면 조기 반환

- **Decision**: 기존 가드 `if (isReady()) return true` 를 `if (contentStatus != FoodContentStatus.INCOMPLETE) return true` 로 바꾼다. 반환값 의미는 "배치가 더 할 일 없음" 으로 유지.
- **Rationale**: PENDING_REVIEW 는 이미 완비된 상태(검수 대기)이므로 재평가·재전이 대상이 아니다. READY 도 동일. 배치 reader 는 INCOMPLETE 만 읽지만(`findIncompleteAfter`), 엔티티 메서드는 호출 순서에 무관하게 안전해야 한다(spec Edge Case 1).
- **Alternatives considered**: PENDING_REVIEW 에서 재평가 허용 — 검수 대기 중 상태가 임의로 움직일 수 있어 기각.

## D3. MySQL ENUM 컬럼 확장 — 선언 순서대로 3값 ALTER

- **Decision**: Flyway 마이그레이션으로 `ALTER TABLE food MODIFY content_status enum('INCOMPLETE','PENDING_REVIEW','READY') NOT NULL DEFAULT 'READY'`. Kotlin enum 과 `Food.kt` 의 `columnDefinition` 도 같은 순서로 맞춘다.
- **Rationale**: `content_status` 는 MySQL ENUM 타입(init_schema)이라 값 추가에 ALTER 가 필수다. 값을 목록 중간에 넣으면 MySQL 이 테이블 COPY 재빌드를 하지만, food 테이블은 소규모(수천 행 이하)라 비용이 무시 가능하고 라벨 기준으로 변환돼 데이터는 안전하다. `@Enumerated(STRING)` 이므로 Kotlin enum 순서는 영속에 영향 없다 — 상태 수명주기 순서(INCOMPLETE→PENDING_REVIEW→READY)로 두어 가독성만 챙긴다.
- **Alternatives considered**: 끝에 append(`'INCOMPLETE','READY','PENDING_REVIEW'`) — INPLACE ALTER 가능하지만 순서가 수명주기를 배반, 소규모 테이블에서 이득 없음. VARCHAR 로 전환 — 스키마 관례 변경으로 범위 초과.

## D4. 컬럼 DEFAULT 'READY' 유지

- **Decision**: 컬럼 기본값은 건드리지 않는다.
- **Rationale**: 애플리케이션 경로는 전부 명시값을 쓴다 — 엔티티 기본값(READY)·`Food.incomplete()`(INCOMPLETE)·배치 bulk insert(`FoodJpaRepositoryCustomImpl` 이 `contentStatus.name` 명시). DEFAULT 는 수동/시드 INSERT 만 타며, 기존 시드는 READY 완성 데이터라는 전제가 유효하다. 기본값 변경은 행동 변화 없는 스키마 노이즈.

## D5. 사용자 노출 필터 — 무변경

- **Decision**: `FoodJpaRepository`(목록·검색·랜덤)·`ScanHistoryJpaRepository` 의 `content_status = 'READY'` 필터를 그대로 둔다.
- **Rationale**: 화이트리스트(READY만) 방식이라 새 상태는 자동 차단된다(fail-closed). FR-003 은 코드 변경 없이 충족 — 테스트로 PENDING_REVIEW 비노출만 고정한다.

## D6. 관리자 API·contracts 없음

- **Decision**: 이 브랜치는 API 표면 변경이 없다 — contracts/ 미생성, 어드민 승인/반려 엔드포인트는 후속.
- **Rationale**: 사용자 스코프 지시(배치 측 전이까지만). 승인 기능 배포 전까지 신규 완성 음식이 PENDING_REVIEW 에 머무는 공백은 스펙 Assumptions 에 명시된 의도된 동작.

## 리스크 노트

- 손스텁 CREATE TABLE 을 쓰는 테스트(scan `ScanHistoryRepositoryTest` 등)는 `content_status VARCHAR(20)` 이라 'PENDING_REVIEW'(14자) 수용 — 스텁 수정 불필요. api 통합 테스트는 Hibernate `schema-generation=create` 가 엔티티 `columnDefinition` 에서 3값 ENUM 을 생성하므로 엔티티 수정만으로 커버.
- `FoodServiceTest` 에 "content_status 미정의 값" 네이티브 INSERT 케이스가 있다 — enum 값 목록에 의존하면 갱신 필요(전체 build 로 검출).
