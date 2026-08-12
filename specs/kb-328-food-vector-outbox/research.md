# Research: READY 전이 벡터 아웃박스 기반 음식 벡터 동기화 (KB-328)

조사 범위: 기존 아웃박스(food_content_outbox)·배치 구조·DocumentDB 읽기 경로(KB-318/319)·임베딩 seam(KB-299)·랭체인 적재(KB-302). 코드 근거는 전부 현 워크트리 기준.

## R1. 아웃박스는 신규 테이블 `food_vector_outbox` — 기존 `food_content_outbox` 재사용 안 함

- **Decision**: 콘텐츠 수집용 아웃박스와 별도로 벡터 동기화 전용 테이블을 만든다. 컬럼은 `food_id`·`operation(UPSERT|DELETE)`·`outbox_status(PENDING|COMPLETE|FAILED)`·`attempts`·`last_error` + BaseEntity 공통.
- **Rationale**: 두 아웃박스는 생명주기가 다르다 — 콘텐츠 아웃박스는 SQS 발행 후 외부(랭체인) 콜백으로 완료되는 3상태(PENDING/SENT/COMPLETE)·비동기 왕복이고, 벡터 아웃박스는 배치가 동기 처리해 즉시 완료/실패가 갈린다(SENT 불필요). 기존 테이블에 operation·용도 컬럼을 얹으면 두 소비자가 한 큐를 나눠 읽는 복잡도만 생긴다.
- **Alternatives considered**: (a) food_content_outbox 에 kind 컬럼 추가 — 소비자 분리·상태 의미 충돌로 기각. (b) 아웃박스 없이 Food 에 vector_synced_at 컬럼 — DELETE(행이 사라진 뒤 동기화 필요)를 표현 못 해 기각.
- 근거 코드: `common/src/main/kotlin/com/kbap/common/domain/food/model/FoodContentOutbox.kt`(구조 복제 원형), `FoodContentOutboxJpaRepository.kt`(커서 페이징·벌크 상태 전이 패턴 재사용).

## R2. 상태 모델에 FAILED·last_error 를 추가한다 (기존 아웃박스와 다른 점)

- **Decision**: `PENDING → COMPLETE`(성공) / `PENDING → PENDING`(실패, attempts+1, last_error 기록) / `PENDING → FAILED`(attempts ≥ MAX 초과) / `FAILED → PENDING`(관리자 재처리). MAX_ATTEMPTS = 5.
- **Rationale**: 콘텐츠 아웃박스는 실패해도 무한 재시도(PENDING 유지)로 충분했지만, 벡터 동기화는 데이터 문제(긴 설명 공백 등)로 영구 실패하는 건이 생긴다 — 무한 재시도는 배치마다 같은 실패를 반복하며 로그를 오염시킨다. FAILED 격리 + 관리자 재처리(US4)가 스펙 요구사항이다.
- **Alternatives considered**: 무한 재시도(기존 패턴 그대로) — US4(FR-007/008) 미충족으로 기각.

## R3. 아웃박스 생성 지점은 api 관리자 서비스 3곳 (도메인 서비스 아님)

- **Decision**: UPSERT/DELETE 아웃박스 생성은 전이가 일어나는 트랜잭션 소유자에 둔다:
  1. 승인 — `AdminFoodContentReviewService.applyContentReviewResult`(`@Transactional`): `Food.approve()` 가 PENDING_REVIEW → READY 로 실제 전이했을 때만 UPSERT 생성.
  2. 수정 — `AdminFoodService.updateFood`(`@Transactional`): 수정 결과가 READY 면 UPSERT, READY → 비READY 로 바뀌면 DELETE.
  3. 삭제 — `AdminFoodService.deleteFood`: DELETE 생성.
- **Rationale**: 헌법 IV — 트랜잭션 경계는 사용하는 쪽이 소유. 세 경로 모두 이미 api admin 서비스가 트랜잭션을 갖고 있어 같은 트랜잭션 원자성(FR-001)이 공짜로 성립한다. 관리자 로직은 api admin 패키지에 두고 공용 도메인 서비스를 오염시키지 않는다는 팀 원칙과도 일치.
- **주의(탐색에서 확인된 함정)**: `Food.approve()` 는 이미 READY 면 no-op 으로 조기 return 하고 전이 여부를 반환하지 않는다 → 전이 발생 여부를 알 수 있게 반환값(Boolean) 추가가 필요하다. `AdminFoodService.updateFood` 는 도메인 메서드를 우회해 필드 직접 대입하므로(contentStatus 폼 지정 포함) 훅은 서비스 메서드 끝에서 변경 전/후 상태를 비교해 건다.
- **Alternatives considered**: JPA EntityListener·도메인 이벤트 — 전이 의미(승인 vs 폼 수정)를 리스너가 구분할 수 없고 배치 기준(api 밖 소비 없음)에도 안 맞아 기각.

## R4. 배치 job 은 기존 `foodContentOutboxPublishJob` 패턴 복제 — Tasklet + 짧은 트랜잭션 분리

- **Decision**: `:batch` 에 `foodVectorSyncJob`(Tasklet, `RunIdIncrementer`, step 트랜잭션은 `ResourcelessTransactionManager`)을 추가한다. 처리 루프는 `TransactionTemplate` 로 (1) PENDING 페이지 조회+음식 로드 → (2) 트랜잭션 밖에서 임베딩·DocumentDB 호출 → (3) 결과 반영(COMPLETE/attempts/FAILED) 트랜잭션. 실행은 기존과 동일하게 run-to-completion(ECS 태스크, `--spring.batch.job.enabled=true`).
- **Rationale**: 외부 호출(임베딩·DocumentDB)을 DB 트랜잭션 안에서 잡지 않는다(헌법 Additional Constraints). `FoodContentOutboxPublisher` 가 정확히 같은 구조를 이미 검증했다.
- 근거 코드: `batch/src/main/kotlin/com/kbap/batch/outbox/FoodContentOutboxBatchConfig.kt`, `FoodContentOutboxPublisher.kt`.

## R5. 벡터 저장소 접근은 `:common` 의 food 컨텍스트 소유 — food 의 제2 영속으로 취급 (2026-08-12 개정)

- **Decision**: 벡터 저장소 seam·어댑터를 `com.kbap.common.domain.food.vector` 에 둔다 — `fun interface FoodVectorSearcher`(검색, 기존 api 것 이사)·`fun interface FoodVectorStore`(upsert/delete/findHash)와 mongodb-driver-sync 기반 thin adapter(`DocumentDbFoodVectorStore` 등, 스테레오타입 없는 plain class). mongodb-driver-sync 의존은 `:common` 에 추가한다. **빈 조립은 각 부트앱 config** 가 `kbap.vector.*` 프로퍼티 + `@ConditionalOnProperty` 로 소유한다 — api 는 searcher 만, batch 는 store 만 조립(배치는 컴포넌트 스캔을 좁혀 두므로 plain class 라야 api 에서도 오등록이 없다).
- **Rationale**: 초안(배치 내부 어댑터, KB-319 선례 승계)은 "소비자가 하나"라는 전제였는데 KB-328 로 DocumentDB 소비자가 api(읽기)·batch(쓰기) 둘이 된다. 연결 설정(uri·database·collection)과 문서 필드명(`embedding`·`foodId` 등)은 reader/writer 가 공유하는 암묵 계약이라 두 모듈이 각자 하드코딩하면 드리프트가 조용한 검색 파손으로 이어진다 — 단일 출처가 필요하다. 벡터 문서는 외부 SaaS 클라이언트가 아니라 **food 데이터의 또 다른 영속**이므로, "영속은 컨텍스트 불문 `:common` 소유"(헌법 IV)와 ":common = api 밖이 컴파일 의존하는 코드" 배치 기준에 따라 food 도메인 패키지에 둔다(MySQL 리포지토리·mysql-connector 가 `:common` 에 있는 것과 동형).
- **Alternatives considered**: (a) 배치 내부 어댑터(초안) — 소비자 2개가 된 시점에 계약 이원화 리스크로 기각. (b) `common.port.vector` + `:infra:vector` 모듈 신설 — 헌법 III 문언에는 가장 충실하나, 벡터 저장소는 seam 교체 가능성(외부 시스템)보다 영속 소유(도메인 데이터) 성격이 강하고, 소비자 둘을 위해 모듈을 늘리는 비용 대비 이득이 없어 기각. (c) api 빈 재사용 — api·batch 상호 미의존 위반.
- **이사 범위**: 기존 `api/.../scan/{SimilarFoodSearcher,DocumentDbSimilarFoodSearcher}.kt` 의 검색 구현·`kbap.vector.*` 프로퍼티 홀더를 `:common` 으로 옮기고 scan 코드는 새 위치를 참조한다(동작 무변경 리팩터링).

## R6. 임베딩 입력·embeddingHash 규약

- **Decision**: 임베딩 원문은 `koreanName + "\n" + longDescription`(KB-319 계약 "저장 = 이름 + 긴 설명 결합" 승계). `embeddingHash = SHA-256(model|dimension|원문)`. 배치는 기존 문서의 hash 와 비교해 동일하면 임베딩 호출 없이 메타데이터만 갱신 후 COMPLETE, 다르면 재임베딩. `longDescription` 이 null/blank 면 임베딩하지 않고 실패 기록(attempts+1, last_error) — 데이터 문제를 드러낸다(스펙 edge case).
- **Rationale**: hash 에 모델·차원을 포함해야 모델 교체 시 자동 재임베딩된다. 질의 측(스캔)이 정제된 표준 한국어명 단독으로 검색하므로 저장 이름은 `koreanName`(정규화 matchKey)이다.
- **Alternatives considered**: 임베딩 벡터 자체의 hash — 임베딩을 호출해야 hash 가 나와 "호출 생략" 목적에 모순, 기각.

## R7. 벡터 문서 스키마는 KB-319 계약의 상위호환 확장

- **Decision**: 기존 `kbap.foods` 컬렉션 문서(`foodId, name, description, imagePath, embedding`)를 `foodId(유일), name, longDescription, imageRef, embedding, embeddingHash, embeddingModel, embeddingDimension, indexedAt` 로 확장한다. upsert 는 foodId 기준 문서 전체 교체(replace). 읽기 경로는 `foodId`·`embedding`·`score` 만 사용하므로(`DocumentDbSimilarFoodSearcher` — 메타데이터는 스냅샷, MySQL 재조회가 단일 진실) 필드 추가·교체는 검색과 무호환 없음.
- **Rationale**: KB-319 계약 문서가 "소프트삭제·비READY 정리는 후속 과제" 로 명시한 그 후속이 KB-328 이다. 기존 문서(hash 없음)는 첫 처리에서 hash 불일치 → 재임베딩·교체로 자연 수렴한다.
- 근거: `specs/kb-319-scan-ocr-vector-fallback/contracts/vector-food-document.md`.

## R8. 배치 설정 — embedding·vector 프로퍼티를 batch yml 에 신설

- **Decision**: `batch/src/main/resources/application.yml` 에 `kbap.llm.embedding.{enabled,dimension: 256,...}`(env 주입)과 `kbap.vector.{enabled,uri,database: kbap,collection: foods}` 를 추가한다. 미설정(로컬 등)이면 관련 빈이 안 뜨고 job 은 구성 실패로 조기 종료 — 스캔 필수 기능인 api 와 달리 배치는 명시 opt-in.
- **Rationale**: 현재 배치엔 embedding·vector 설정이 전혀 없다(차원 256 은 api yml 에만 있음 — 탐색 확인). `:batch` 는 이미 `com.kbap.infra.llm` 을 스캔하므로 프로퍼티만 주면 `TextEmbeddingClient` 빈이 뜬다.

## R9. 백필은 Flyway 데이터 마이그레이션 1건

- **Decision**: `INSERT INTO food_vector_outbox (food_id, operation, outbox_status, ...) SELECT id, 'UPSERT', 'PENDING', ... FROM food WHERE content_status='READY' AND status='ACTIVE'` 를 테이블 생성 마이그레이션 직후 버전으로 넣는다.
- **Rationale**: 스키마 owner(api Flyway)가 배포 시 정확히 1회 실행을 보장한다 — 수동 SQL·일회성 관리자 버튼보다 재현 가능하고, 이후 증분은 R3 훅이 담당한다. 마이그레이션 독립성 규칙(다른 미적용 마이그레이션 순서 비의존)도 충족 — 테이블 생성과 같은 파일로 합치면 더 단순하므로 **한 파일**(생성 + 백필)로 간다.
- **Alternatives considered**: 관리자 백필 버튼 — 실행 여부가 사람 기억에 의존, 기각.

## R10. 관리자 실패 조회·재처리는 기존 관리자 화면(Thymeleaf) 확장

- **Decision**: 기존 음식 대시보드(`AdminFoodPageController`/`AdminFoodDashboardService` — 콘텐츠 아웃박스 카운트·최근 목록 선례)에 벡터 아웃박스 섹션(상태별 카운트·FAILED 목록: 음식·attempts·last_error)과 재처리 POST(FAILED → PENDING, attempts 리셋)를 추가한다. REST admin API 는 만들지 않는다.
- **Rationale**: 운영 콘솔은 이미 Thymeleaf 화면이 담당하고, 콘텐츠 아웃박스 대시보드와 나란히 있어야 운영자가 한 화면에서 파이프라인 전체를 본다.

## 미해결 NEEDS CLARIFICATION

없음 — Technical Context 의 모든 항목이 기존 스택·선례로 확정된다.
