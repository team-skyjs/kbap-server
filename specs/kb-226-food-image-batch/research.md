# Research: OpenAI Batch API 기반 음식 이미지 비동기 생성

**Date**: 2026-07-24 | **Plan**: [plan.md](./plan.md)

사전 논의(2026-07-24, 아티팩트 기록)에서 주요 결정이 확정되어 NEEDS CLARIFICATION은 없다. 아래는 결정·근거·기각 대안의 기록이다.

## R1. 회수 트리거 + 중복 실행 방지

- **Decision**: api 서버 `@Scheduled`(1시간 주기) + ShedLock(JDBC 프로바이더), `lockAtMostFor = 30m`.
- **Rationale**: 운영 api 2대에서 shedlock 테이블 행의 원자적 UPDATE로 주기당 1대만 선점. 인스턴스가 작업 중 죽어도 리스 만료로 다음 틱에 자동 복구. 락 저장소가 MySQL = 상태의 원천과 동일 계층이라 Redis 재시작·failover에 영향받지 않음. 회수가 멱등이라 최악의 이중 실행도 데이터 훼손이 아닌 I/O 낭비에 그침.
- **Alternatives considered**:
  - 외부 틱(EventBridge → 내부 엔드포인트): 중복이 구조적으로 소멸하지만 비밀 헤더 인증 + IaC 관리 지점 추가. 기각.
  - `@Scheduled` + image_batch 상태 CAS 클레임: 무의존이지만 죽은 클레임 타임아웃 재선점을 직접 구현해야 함. 기각.
  - Redis 분산락(SETNX/Redisson): 저지연 고경쟁용 — 1시간 1회 경쟁에 장점이 없고, Redis 재시작 시 락 소실로 이중 실행 가능. 기각.
  - AWS 람다 폴링/저장 워커: 폴링 대상이 우리 메타 테이블에 있어 왕복 구조 + OpenAI 키 이중 관리. 부하 분석 결과(배치 10건 ≈ 15~40MB 스트리밍 I/O, 상주 메모리 ~4MB, 제출 직후 몇 틱만 실작업) api 단독으로 충분 — 관측되지 않은 부하에 대한 선지불 금지. 회수 서비스의 seam(상태 조회/바이트 이동/DB 전이)만 갈라두어 향후 저장 워커 분리에 대비. 기각.

## R2. Food 상태 모델 — TEXT_READY 신설 + 수렴 전이

- **Decision**: `FoodContentStatus`에 `TEXT_READY` 추가. 전이는 칼럼 상태(텍스트 4조건 × imageRef)로 목표 상태를 계산하는 **수렴 함수 하나**로 통일하고, 콘텐츠 배치와 이미지 회수가 같은 함수를 호출한다.
  - 텍스트 미완 → `INCOMPLETE` 유지 (이미지가 먼저 오면 imageRef만 세팅)
  - 텍스트 완료 + 이미지 없음 → `TEXT_READY` (이미지 대기실 — 나가는 트리거는 이미지 도착뿐)
  - 텍스트 완료 + 이미지 있음 → `PENDING_REVIEW` (TEXT_READY 건너뜀)
- **Rationale**: 검수자가 이미지를 포함해 검수해야 하므로 이미지 없는 음식은 PENDING_REVIEW 불가. INCOMPLETE에 두면 콘텐츠 배치가 최대 24시간 동안 무한 재선정(`IncompleteFoodItemReader`는 상태만 봄). 이미지 프롬프트는 `koreanName`만 쓰므로 이미지가 텍스트보다 먼저 끝날 수 있음 — 간선 하드코딩 대신 수렴 함수로 순서 무관 안전을 보장.
- **Alternatives considered**:
  - READY(현 PENDING_REVIEW) 조건에서 이미지 제외: 검수자가 이미지를 봐야 하므로 기각.
  - 상태 유지 + 선정 쿼리에 텍스트 조건 필터: 번역 JSON·placeholder 검사를 SQL로 옮겨 인덱스 스캔 한 줄이 복잡 쿼리化. enum 값 하나가 더 쌈. 기각.

## R3. 이미지 제출 후보 선정

- **Decision**: 상태값으로 필터링하지 않는다. 조건은 `imageRef IS NULL(빈 값 포함) AND food_id NOT IN (PENDING 상태 image_batch_item)` 두 개뿐.
- **Rationale**: "이미지가 필요한가"의 진실은 imageRef 하나(단일 축). 두 번째 조건이 중복 제출 가드를 겸한다(버튼 연타 무해). 상태 필터를 쓰면 향후 prompt_version 재생성(READY 음식 대상) 케이스를 잃는다. 현 상태 모델에서 이 집합은 결과적으로 INCOMPLETE ∪ TEXT_READY와 일치.

## R4. OpenAI Batch API 접근 방식

- **Decision**: Spring AI 2.0은 Batch/Files API를 지원하지 않음 → `:core`에 `FoodImageBatchClient` 포트를 두고 `:infra:llm`에 RestClient 기반 `OpenAiFoodImageBatchClient` 구현. API 키는 기존 `LlmModelProperties`의 OpenAI 키 재사용(키 단일 관리).
  - 제출: 10건 단위 JSONL 조립(`custom_id` = food PK, `POST /v1/images/generations` body: prompt·model·size 1024x1024·quality medium) → Files API 업로드(purpose=batch) → `POST /v1/batches`(completion_window 24h) → openai_batch_id 반환
  - 상태: `GET /v1/batches/{id}` → in_progress/completed/failed/expired + output_file_id/error_file_id
  - 결과: `GET /v1/files/{output_file_id}/content`를 **줄 단위 스트리밍**으로 읽어 항목별 콜백(customId, 디코딩된 bytes 또는 error, usage) — 전체 메모리 적재 금지, 상주는 이미지 1장
- **Rationale**: 헌법 III — 외부 시스템 클라이언트는 포트 seam으로만 사용. 기존 `StorageObjectStore`·`FoodImageGenerationClient` 선례와 동일 패턴. 스트리밍 파싱으로 SC-007(상주 메모리 이미지 1장) 충족.
- **Alternatives considered**: 동기 ImageModel(Spring AI) 사용 — 3장에 5분 이상, 파이프라인 불가 + 배치 대비 2배 비용. KB-224 구현체(`OpenAiFoodImageGenerationClient`)는 이 구조로 대체·삭제.

## R5. 저장 키·서빙

- **Decision**: 결정적 키 `images/food/{foodId}.png` — 음식 사진은 환경 공용이라 무접두(KB-171 관례, STORAGE_KEY_PREFIX 미적용). `StorageObjectStore.put`은 덮어쓰기라 재회수·재생성이 자연 멱등. `food.imageRef`에는 CDN 키(절대 URL 금지 — 기존 관례) 저장. 512px 축소본은 스코프 제외(확정) — 원본 그대로 서빙.
- **Rationale**: 결정적 키 = 중복 저장 0(SC-005), 기존 `ImageUrls.resolve` 서빙 경로 무변경.

## R6. 원가(usage) 기록

- **Decision**: 회수 시 이미지 1장당 기존 `LlmCallCostIncurred` 이벤트 → `LlmCallCostService.record` 1행. `image_batch_item`에는 usage 컬럼을 두지 않는다.
- **Rationale**: 장당 원가 추적은 llm_call_cost 건당 1행으로 이미 달성(KB-155 흐름 정합). 이중 장부 금지 — 같은 데이터를 두 테이블에 쓰지 않는다.

## R7. 프롬프트·운영 파라미터

- **Decision**: 프롬프트·모델(gpt-image-2)·품질(medium)·크기(1024x1024)·배치 크기(10)는 `FoodImageProperties`(설정)로 관리. `image_batch.prompt_version`에 제출 시점 버전을 기록만 한다 — prompt_version 불일치 기반 재생성 트리거는 후속 티켓(v1은 imageRef 부재만 후보).
- **Rationale**: 기록해 두면 재생성 도입 시 마이그레이션 불필요. 트리거 로직은 지금 필요 없음(YAGNI).

## R8. 실패·만료 처리

- **Decision**: 배치 failed/expired → 전 PENDING 항목 FAILED 마킹 + 배치 FAILED 마감. completed 내 항목별 error → 해당 항목만 FAILED(error_msg 저장), 성공 항목은 정상 처리. FAILED 항목의 음식은 imageRef가 여전히 없으므로 다음 관리자 제출에 자동 재포함(별도 재제출 로직 없음).
- **Rationale**: 멱등 원칙 — 복구 경로가 정상 경로와 동일.

## R9. 콘텐츠 배치 정리

- **Decision**: `FoodContentItemProcessor`의 `needsImage()` 분기·빈 스텁 `generateImage()` 제거, 주석 처리된 이미지 클라이언트 조립 삭제. 텍스트 4작업 완료 시 수렴 전이 함수 호출. `IncompleteFoodItemReader`는 무변경(INCOMPLETE만 선정 — 인덱스 스캔 유지).
- **주의(구현 시)**: `content_status`는 MySQL ENUM — TEXT_READY 추가는 Flyway `MODIFY COLUMN` + 테스트 손스텁 CREATE TABLE(scan·bookmark·admin 등 content_status를 정의하는 테스트 시드) 동기화 필요. 전체 `./gradlew build`로만 잡힌다.
