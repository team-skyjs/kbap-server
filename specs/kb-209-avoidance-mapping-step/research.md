# Research: 음식 기피성분 매핑·맵기 스텝 (KB-209)

## R1. 미조사/무성분 센티널 — 소유권이 KB-209 로 넘어옴

- **Decision**: "미조사" 표시값(`spiciness = -1`, `avoidance_substances = NULL`)을 **본 기능이 직접 구현**한다. `Food.incomplete()` 가 센티널로 생성하고, `needsAvoidanceMapping() = (avoidanceSubstances == null)` 로 판정한다. "조사완료·무성분"은 빈 목록(`[]`), "조사완료 맵기"는 0~10 이다. Flyway 로 `avoidance_substances` 를 NULL 허용으로 바꾸고 INCOMPLETE 행을 백필(`spiciness 0→-1`, `'[]'→NULL`)하며, `upsertIncomplete` SQL 의 `'[]'` 하드코딩을 NULL 로 바꾼다. `AdminControllerTest` 의 비활성 센티널 assert(`enabled = false`, 130행)를 활성화한다.
- **Rationale**: 구분이 없으면 무성분 음식(FR-008)이 매 실행 재조사되고 READY 로 전이되지 못한다(무한루프 — 2026-07-21 결정의 원인 그대로). 원래 kb-182 후속 PR 소유로 합의됐으나, **조사 결과 그 변경은 어느 브랜치에도 존재하지 않는다** — `origin/kb-182-batch-pipeline-skeleton` 의 미머지 커밋은 #81 squash 머지 이전 히스토리이고 Food.kt 는 develop 과 동일(spiciness=0·emptyList), 열린 PR 도 없다. 센티널 없이는 본 기능의 핵심 FR 이 성립하지 않으므로 소유권을 KB-209 로 이전한다.
- **Alternatives considered**: (a) 별도 boolean/status 컬럼 — 컬럼 추가 대비 이득 없음, 2026-07-21 결정도 센티널 방식. (b) kb-182 후속 PR 대기 — 존재하지 않는 작업을 기다리는 것이므로 기각.

## R2. 카탈로그 소스 — DB 단일 출처 조회, enum 은 유효성 판정용

- **Decision (KB-220/ADR-0014 반영 개정)**: 프롬프트에 넣을 성분 목록(코드 + 한국어 이름)은 `AvoidanceSubstanceJpaRepository.findAll()` 을 배치가 **직접 조회**해 DB 에서 읽는다(`:app:batch` 는 이미 `:domain:avoidance` 의존, 리포지토리는 KB-220 으로 public — 위임 전용 창구 서비스 금지라 서비스 메서드를 추가하지 않는다). 응답 코드의 유효성 판정은 조회한 카탈로그 코드 집합으로 한다. 카탈로그가 비면 매핑 작업을 수행하지 않는다(spec edge case).
- **Rationale**: 헌법 원칙 V — 카탈로그 콘텐츠(한국어 이름 포함)는 DB 단일 출처이고, `AvoidanceSubstanceCode.label` 은 "런타임 미사용·비권위" 가독성 힌트라 프롬프트(런타임)에 쓰면 위반이다. 시드-동기화 테스트가 label=korean_name 을 보장하지만 권위는 DB 다.
- **Alternatives considered**: enum `label` 직접 사용 — DB 조회가 없어 단순하지만 원칙 V 의 label 용도 제한 위반. 코드만 프롬프트에 노출 — 한국어 이름 없이 LLM 매핑 정확도 저하.

## R3. 합의(consensus) 규칙 — 2/3 다수결 + 평균 (Codex 리뷰 반영 개정)

- **Decision**: 3개 모델(OPENAI·UPSTAGE·GEMINI)에 `LlmFanoutClient.generate` 로 fan-out 하고: 성공 응답(파싱 성공 포함)이 **2개 미만이면 합의 미성립 → 그 음식 실패 처리**(INCOMPLETE 유지, 다음 실행 재시도). 투표는 **모델 1개당 코드 1표**(응답 내 중복 코드는 1표로 dedup) — **성공 응답 중 2개 이상 모델이 지목한 코드만 채택**, `inclusion_percent` 는 지목 모델들의 평균(반올림). 맵기는 성공 응답들의 **중앙값**(2개면 평균 반올림 — 극단값에 강건, 추가 설정 없음). 미지 코드는 응답별 파싱 단계에서 폐기 + 경고 로그(음식 실패 아님). `inclusion_percent = 0` 항목은 "미포함 판단"으로 간주해 폐기(투표 아님). 범위 밖 값(percent 0~100 밖·spiciness 0~10 밖)·형식 불일치 응답은 그 모델 응답 전체를 실패로 취급한다.
- **False-safe 방지 (Critical 반영)**: 채택 코드가 비었을 때, **"무성분 조사완료(`[]`)" 로 확정하는 것은 성공 모델 2개 이상이 각각 명시적으로 빈 성분 목록을 반환한 경우뿐**이다. 그 외(모델들이 서로 다른 성분을 지목해 교집합이 빈 경우·유효 코드가 전량 폐기된 경우)는 **합의 미성립 → 실패 처리**한다 — 불일치가 "무성분"으로 둔갑해 READY 되는 경로를 차단.
- **Rationale**: 구 `ConsensusEnsembleAggregator`(#81 에서 제거)의 개념을 최소형으로 부활 — 단일 모델 환각을 다수결로 걸러 안전 직결 데이터의 신뢰도를 확보한다.
- **Alternatives considered**: 합집합(1표 채택) — 환각 유입 위험. 만장일치 — 재현율이 지나치게 떨어짐(성분 누락 = 위험 과소평가). 맵기 분산 임계치 — 설정값 추가 대비 이득 없어 중앙값으로 갈음.

## R4. 호출 단위 — ItemProcessor 구조가 "호출당 음식 1건"을 강제

- **Decision**: 별도 `foods-per-call` 설정값을 도입하지 않는다. Spring Batch ItemProcessor 가 음식 1건 단위로 동작하므로 "호출당 1건(기본값)"이 구조적으로 보장된다.
- **Rationale**: 다건 묶음은 품질이 떨어진다는 실측(번역 10건 묶음 실험)이 이미 있고, 묶음 프롬프트를 지원하려면 processor 를 chunk 단위 협력자로 재설계해야 한다 — 쓰지 않을 유연성이다. DoD 의 "호출당 음식 수 설정값" 취지(1건 보장 + 운영 조정)는 구조적 1건 고정이 더 강하게 충족하며, 묶음이 실제로 필요해지면 그때 도입한다.
- **Alternatives considered**: `kbap.batch.content.avoidance.foods-per-call` 프로퍼티 예약 — 읽지 않는 설정값은 오해만 만들므로 기각.

## R5. 배치 쪽 구현 형태 — 협력자 1개 + 프로세서 메서드 채움

- **선행 의존 (2026-07-22 추가)**: 배치가 LLM 클라이언트에 기대하는 **인터페이스·응답 DTO 는 병행 세션이 밑작업 중** — 완성되면 이 브랜치가 이어받는다. 그 확정 전까지 `LlmFanoutClient` 직접 호출·응답 파싱 형태(아래)는 **잠정 설계**이며, seam 이 넘어오면 investigator 는 프롬프트 구성·합의·검증만 남기고 호출·파싱은 인계받은 인터페이스에 위임한다. **이 브랜치는 그 seam 파일을 독자 정의하지 않는다**(세션 간 이중 정의 방지).
- **Decision (구현 확정)**: 별도 협력자(`FoodAvoidanceInvestigator`)를 두지 않는다 — #87 계약이 조사·합의·검증을 `FoodAvoidanceAssessmentClient` 구현 뒤로 숨겼다. `mapAvoidance` 가 직접 `candidateCodes()` supplier 평가 → 빈 카탈로그면 skip → `client.call(koreanName, codes)` → **포함률 0 폐기**(RiskLevel 1..100 방어) → `FoodAvoidanceItem` 매핑 → `food.assessAvoidance(substances)`. 맵기(spiciness)는 여기서 다루지 않는다(#87 로 설명 작업 소관). client 예외는 process 밖으로 전파돼 step skip 이 그 음식만 미완 처리한다(다음 음식 진행).
- **Rationale**: 스텝 인터페이스·플러그인 빈 금지(2026-07-21 사용자 지시 — 과한 추상화 지양). 페이크 `LlmModelCaller` 로 `LlmFanoutClient` 를 직접 조립해 단위 테스트 가능(기존 `LlmFanoutClient` 패턴 그대로)하므로 별도 seam 불필요.
- **Alternatives considered**: 프로세서 안에 전부 인라인 — 파싱·합의 로직까지 넣으면 프로세서가 4작업 공용이라 비대해지고 단위 테스트가 Spring Batch 에 묶임. 도메인 서비스로 이동 — LLM 호출 오케스트레이션은 배치 소유(모듈 배치 규칙), 도메인은 저장 창구만.

## R6. JSON 응답 강제·파싱 정책

- **Decision**: 프롬프트에서 JSON-only 응답을 지시하고, 파싱 전 코드펜스(```json)·전후 잡문 제거 후 `jackson`(kotlin module)으로 역직렬화한다. 실패한 모델 응답은 그 모델만 탈락(R3 의 성공 카운트에서 제외).
- **Rationale**: solar-mini 등 소형 모델의 malformed JSON 전례(2026-07-07 스모크)가 있어 관용 전처리 + 모델 단위 격리가 필요하다. 구조화 출력 API 는 3사 공통 지원이 아니라 프롬프트 강제가 최소 공통분모.
- **Alternatives considered**: Spring AI structured output converter — 모델별 지원 편차·현 `LlmModelCaller` seam(문자열 반환)과 불일치.

## R8. 청크 트랜잭션 제거 — ResourcelessTransactionManager (해소)

- **문제**: chunk-oriented Step 은 read-process-write 를 chunk 트랜잭션으로 감싼다 — processor 의 client 호출(실구현 시 모델당 최대 180s)이 그 트랜잭션 안에서 돌아 LLM 지연 동안 DB 커넥션을 점유한다. `TransactionTemplate`(REQUIRES_NEW)는 롤백 격리만 해결하고 점유는 그대로다.
- **Decision — 적용**: `foodContentStep` 의 `.transactionManager` 를 `ResourcelessTransactionManager`(no-op)로 교체해 청크 트랜잭션이 DB 커넥션을 잡지 않게 한다. DB 쓰기는 각자 자기 트랜잭션으로 커밋한다 — processor 의 `saveProgress`(REQUIRES_NEW), writer 의 `save`, reader 의 조회. 읽기 페이징(chunk-size 만큼)은 트랜잭션과 무관하게 유지돼 IO 효율은 그대로다.
- **정합성**: 재시도는 청크 커서/메타가 아니라 **도메인 상태(INCOMPLETE + skip-if-done)** 가 소유하므로(RunIdIncrementer 로 매 실행이 새 인스턴스라 커서를 이어받지도 않음), BATCH_* 메타가 비즈니스 쓰기와 원자적으로 안 묶여도 무해하다(메타는 관측용). fault-tolerant skip 도 REQUIRES_NEW 커밋이 이미 빠져나가 롤백할 게 없을 뿐 격리 동작은 유지된다.
- **검증**: `FoodContentJobTest` 가 실 잡을 resourceless 스텝으로 돌려 — 한 음식 조사 실패 시 잡 COMPLETED·실패 음식만 미조사(null)·나머지 성분 커밋 — 을 Testcontainers 로 고정한다.
- **후속(별개)**: 4작업 **비동기 팬아웃**(음식당 4 API 동시 호출 → 성공분만 1회 쓰기, 워커 스레드에선 DB 접근 금지)은 지연(latency) 최적화로 남는다 — 4 client(KB-224) 갖춰진 뒤 도입.

## R9. 배포 순서·병행 실행 (Codex 리뷰 반영 — 위험 수용 기록)

- **배포 순서**: 배치는 상시 기동이 아니라 `--spring.batch.job.enabled=true` 로 수동/스케줄 실행한다(기본 off). 마이그레이션 owner 인 api 배포(Flyway 적용) 후 배치를 실행하면 되므로 다단계 호환 릴리스는 불필요 — 운영 절차 한 줄로 갈음: **"api 배포(마이그레이션 적용) 전에는 신규 배치를 실행하지 않는다"** (quickstart 에 명시).
- **병행 실행**: `RunIdIncrementer` 로 동시 2 인스턴스 실행이 가능하지만 현 운영은 단일 수동/야간 실행이다. 낙관적 락·행 claim 은 현 규모에서 과설계로 판단해 **수용 위험**으로 기록한다 — 다중 인스턴스 운영이 실제로 생기면 잡 단일 실행 락(BATCH 메타 기반 또는 DB 락)을 후속 도입한다.

## R7. 기존 코드 확인 결과 (변경 지점 좌표)

| 지점 | 현재 상태 | 변경 |
|---|---|---|
| `Food.kt` (`:domain:food`) | `incomplete()` spiciness=0·emptyList, `needsAvoidanceMapping()=isEmpty()` | 센티널 -1/null 화, `avoidanceSubstances: List<FoodAvoidanceItem>?`, null-safe 파생 메서드(`orEmpty()`) |
| `FoodJpaRepositoryCustomImpl.upsertIncomplete` | `'[]'` 하드코딩·spiciness 바인딩 | NULL 삽입(센티널) |
| Flyway | `avoidance_substances` NOT NULL (V2026.07.21…) + **`ck_food_spiciness` CHECK(0~10)** (init_schema:32) | 신규 마이그레이션: NULL 허용 + **CHECK 를 -1~10 으로 재정의** + INCOMPLETE 백필 — CHECK 재정의 없이는 -1 백필이 즉시 실패(Codex Critical) |
| `FoodContentItemProcessor.mapAvoidance` | 빈 스텁 | client 호출→0%폐기→매핑→`assessAvoidance`(협력자 없음) |
| `FoodContentBatchConfig` | KB-220 재편 — 리포지토리 직접 주입 + `TransactionTemplate` | client + `AvoidanceSubstanceJpaRepository.findAll()` 코드 supplier 주입. step `.transactionManager` 는 R8 미해소(유보) |
| `AvoidanceSubstanceJpaRepository` | public(KB-220), findAll 상속 제공 | 추가 코드 없음 — findAll 직접 사용(`AvoidanceSubstanceCatalogQueryTest` 로 ACTIVE 필터 고정) |
| `AdminControllerTest:130` | 센티널 assert `enabled = false` | 활성화 |
| `avoidanceSubstances` 참조 파급 | `FoodServiceTest`·`FoodReadyTransitionTest`·`FoodOverallRiskTest`·`FoodTest`·`GetFoodDetailResult`·`FoodDetailResponse`·`ScenarioFoodSeed`·`FoodTestSeed` | nullable 화에 따른 컴파일·기대값 갱신 — API 경계(dto/response)는 `orEmpty()` 로 non-null 유지(READY 만 노출되므로 동작 불변) |
| 스캔 손스텁 CREATE TABLE·food INSERT 시드 | `avoidance_substances` NOT NULL 전제 | NULL 허용·CHECK 반영(food 컬럼 변경 시 3곳 규칙) |
