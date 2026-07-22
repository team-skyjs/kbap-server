# Implementation Plan: 음식 기피성분 매핑·맵기 스텝 — READY 전이 4작업 완성

**Branch**: `kb-209-avoidance-mapping-step` | **Date**: 2026-07-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-209-avoidance-mapping-step/spec.md`

## Summary

배치 콘텐츠 파이프라인의 빈 스텁 `mapAvoidance` 를 채운다: 음식 1건마다 기피성분 조사 client 를 호출해 카탈로그 81종 기준 성분(code + 포함확률)을 받아 저장한다. 전제인 **미조사/무성분 센티널**(spiciness -1·avoidance NULL — kb-182 소유였으나 미구현으로 확인, 본 기능이 흡수)을 함께 구현해 무성분 음식의 무한 재조사를 차단하고, READY 전이를 4작업 완성으로 완성한다. 상세 결정: [research.md](research.md).

> **구현 반영 노트 (2026-07-22 — #87 seam 인계 후 개정)**
> - **조사·합의는 client 구현 뒤로 이관**: #87(`com.kbap.core.food` seam)이 `FoodAvoidanceAssessmentClient.call(koreanName, candidateCodes): List<FoodAvoidanceAssessment>` 로 확정. 3모델 fan-out·2/3 다수결·미지코드 폐기는 이 계약의 **구현(`:infra:llm`) 책임**이며 별도 태스크다. 배치는 호출→매핑→저장만 한다. 우리가 쓴 `contracts/llm-avoidance-response.md`(합의 규칙)는 그 구현 태스크의 스펙으로 유효하다.
> - **맵기(spiciness)는 KB-183 설명 작업으로 이관**: #87 `FoodDescriptionClient` 가 설명·번역·맵기를 일괄 반환. 따라서 `assessAvoidance` 는 성분만 반영(spiciness 파라미터 제거)하고, -1 센티널 해소는 설명 작업 몫이다. Jira DoD 의 "같은 조사에서 맵기" 항목은 계약 차원에서 KB-183 로 이동.
> - **완료 범위(본 브랜치)**: ① 센티널·마이그레이션·upsert(PR #86 초기분) + ② `assessAvoidance` 성분-전용화, `mapAvoidance` 구현(카탈로그 findAll supplier → client 호출 → 매핑 → 저장, 빈 카탈로그 skip), `FoodContentBatchConfig` 조립, 배치 부팅 테스트에 페이크 client 빈. `./gradlew build` 그린.
> - **잔여(범위 밖)**:
>   1. `FoodAvoidanceAssessmentClient` 실구현(`:infra:llm` 3모델 합의) — 별도 태스크. 미구현 상태에서 배치 실부팅은 client 빈 부재로 실패(배치 미배포라 수용).
>   2. **R8 커넥션 점유 — 미해소, 실구현 태스크와 함께**: chunk 트랜잭션이 여전히 process()(=조사 호출)를 감싸므로 LLM 지연 동안 DB 커넥션이 점유된다. processor 의 `TransactionTemplate`(REQUIRES_NEW)는 **롤백 격리**만 해결하지 커넥션 점유는 그대로다. step `ResourcelessTransactionManager` 전환이 해법이나, chunk step 에서 이는 reader 상태·Batch 메타 영속과 얽혀 별도 검증 사이클이 필요하다 — client 실구현(실 지연 발생 시점)과 묶어 처리한다. 현재는 client 미구현·배치 미배포라 라이브 영향 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1, Spring Batch(chunk Step — 기존 골격), Spring AI 2.0(`:infra:llm` 의 `LlmFanoutClient`·`LlmModelCaller` seam), Jackson(kotlin module)

**Storage**: MySQL — `food` 행 JSON 컬럼(`avoidance_substances`)·`spiciness` 컬럼. Flyway 마이그레이션 1건(NULL 허용 + INCOMPLETE 백필). Redis 무관

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + JUnit5 플랫폼. 단위: 페이크 `LlmModelCaller` 로 `LlmFanoutClient` 조립. 통합: MySQL Testcontainers(`:core` testFixtures)

**Target Platform**: `:app:batch` bootJar (Linux 서버·로컬), `:app:api` 는 마이그레이션 owner 로만 관여

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 기존 모듈만 수정(`:domain:food`·`:domain:avoidance`·`:app:batch`·`:app:api` 마이그레이션/테스트), 신규 모듈 없음

**Performance Goals**: 처리량 목표 없음 — 정확성 우선(spec Assumptions). 호출당 음식 1건(ItemProcessor 구조 강제), 모델 호출 타임아웃 기존 `kbap.llm.call-timeout`(180s) 재사용

**Constraints**: LLM 호출은 DB 트랜잭션 밖 — chunk 트랜잭션이 process() 를 감싸는 문제를 `ResourcelessTransactionManager` 로 해소(R8, Codex Critical 반영 — KB-220 재편 후에도 유효). DB 작업은 processor 내 `TransactionTemplate` 작업별 즉시 커밋·writer save 로만. 부분 실패 격리(음식·모델 단위). 미지 코드 폐기+경고

**Scale/Scope**: 배치 주기당 INCOMPLETE 수십 건 수준, 카탈로그 81종, 3모델 × 1호출/음식

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* — **Phase 0/1 완료 시점 재평가: PASS**

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First (NON-NEGOTIABLE) | PASS | 모든 변경(Food 센티널·investigator 합의·파서·upsert NULL·READY 게이트)을 실패 테스트 선행으로 진행. 페이크 `LlmModelCaller` 로 성공/부분실패/미지코드/형식불일치 경로 단위 검증(DoD 명시) |
| II. Bounded Contexts | PASS | food 는 성분을 `code: String` 으로만 저장(기존 유지). 배치(부트앱, 조합 계층)가 `:domain:avoidance` 카탈로그와 `:domain:food` 창구를 조합 — 도메인 간 신규 의존 없음 |
| III. Layered Dependency Direction | PASS | `:app:batch` → `:domain:*`·`:infra:llm` 기존 선언 의존만 사용(avoidance 의존은 KB-209 자리로 이미 예약). LLM 은 `LlmModelCaller` seam 뒤 |
| IV. Persistence Encapsulation (ADR-0014 개정) | PASS | KB-220 으로 리포지토리 public — 단순 영속 접근은 리포지토리 직접, 도메인 로직(센티널·READY 게이트)은 `Food` 엔티티·`FoodService` 소유. `upsertIncomplete` 수정은 `:domain:food` 안. 신규 JPA 연관관계 없음 |
| V. Domain Content Language Policy | PASS | 카탈로그 콘텐츠(한국어 이름)는 DB 단일 출처에서 조회해 프롬프트 구성 — enum `label`(런타임 미사용 규정) 미사용(R2). 안전 직결 데이터는 3모델 합의로 신뢰도 확보 |
| 추가 제약 | PASS | LLM 호출은 트랜잭션 밖(기존 구조). 도메인 모델 API 노출 무관(웹 변경 없음) |

## Project Structure

### Documentation (this feature)

```text
specs/kb-209-avoidance-mapping-step/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 센티널 소유권·카탈로그 소스·합의 규칙 등 7건
├── data-model.md        # Phase 1 — Food 센티널·마이그레이션·상태전이
├── quickstart.md        # Phase 1 — 테스트/실행/확인 방법
├── contracts/
│   └── llm-avoidance-response.md   # LLM 응답 JSON·합의 계약
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
domain/food/src/main/kotlin/com/kbap/domain/food/
├── model/Food.kt                        # [수정] 센티널(-1/null)·nullable 컬럼·needsAvoidanceMapping·assessAvoidance
├── FoodJpaRepositoryCustomImpl.kt       # [수정] upsertIncomplete '[]'→NULL
├── dto/GetFoodDetailResult.kt           # [수정] nullable 파급 — orEmpty() 로 non-null 경계 유지
domain/food/src/test/kotlin/.../model/   # [수정] FoodTest·FoodReadyTransitionTest·FoodOverallRiskTest — null/[]/비어있지않음 3상태 고정
domain/food/src/test/kotlin/.../FoodServiceTest.kt                   # [수정] nullable 파급

domain/avoidance/src/main/kotlin/com/kbap/domain/avoidance/
├── AvoidanceSubstanceJpaRepository.kt   # [사용] findAll() 직접 조회 — KB-220 으로 서비스 창구 폐기, 추가 코드 없음
domain/avoidance/src/test/kotlin/...                                 # [수정] 목록 조회 스펙

app/batch/src/main/kotlin/com/kbap/app/batch/content/
├── FoodAvoidanceInvestigator.kt         # [신규] 프롬프트·fan-out·파싱·2/3 합의·검증
├── FoodContentItemProcessor.kt          # [수정] mapAvoidance 본문 — investigator 호출·결과 반영
├── FoodContentBatchConfig.kt            # [수정] investigator 빈 조립(AvoidanceSubstanceJpaRepository 직접 주입 — 엔티티/레포 스캔은 @AutoConfigurationPackage 커버)
│                                        #        + step transactionManager → ResourcelessTransactionManager (R8)
app/batch/src/test/kotlin/com/kbap/app/batch/content/
├── FoodAvoidanceInvestigatorTest.kt     # [신규] 페이크 LLM — 성공/부분실패(1개 성공→실패)/미지코드/형식·범위 위반/
│                                        #        빈배열 명시 합의 vs 교집합-빈 불일치/빈 카탈로그(호출 0회)
├── FoodContentItemProcessorTest.kt      # [수정] 매핑 성공·실패 경로 + 조사완료([]) 재실행 시 LLM 미호출

app/api/src/main/kotlin/com/kbap/app/api/food/FoodDetailResponse.kt   # [수정] nullable 파급
app/api/src/main/resources/db/migration/
├── V2026.07.22.HH.mm.ss__food_unassessed_sentinel.sql   # [신규] CHECK -1~10 재정의 + NULL 허용 + INCOMPLETE 백필
app/api/src/test/kotlin/com/kbap/app/api/admin/AdminControllerTest.kt # [수정] 센티널 assert 활성화
(scan 손스텁 CREATE TABLE·ScenarioFoodSeed·FoodTestSeed — NULL 허용·CHECK 동기화, 전체 build 로 검증)
```

**Structure Decision**: 신규 모듈 없음. LLM 오케스트레이션(프롬프트·합의)은 `:app:batch` 소유(모듈 배치 규칙 — 데이터 접근만 도메인 창구 경유), 도메인 불변(센티널·READY 게이트·조사 반영 규칙)은 `Food` 엔티티 소유. 스텝 인터페이스·플러그인 빈 금지 결정에 따라 협력자 1개(`FoodAvoidanceInvestigator`)만 추가한다.

**검증 범위 주의 (Codex 리뷰 반영)**: 사진·설명·번역 작업은 KB-183/184 소관으로 여전히 빈 스텁이다 — KB-209 의 READY 전이 종단 검증은 **3작업이 완성된 fixture** 위에서 기피성분 매핑 → READY 만 검증한다. 실제 4작업 전체 e2e 는 KB-183/184 완료 후 가능하다.

**선행 의존 — LLM seam 은 병행 세션 소유 (2026-07-22)**: 배치가 LLM 클라이언트에 기대하는 인터페이스·응답 DTO 는 병행 세션이 정의 중이며 완성 후 이 브랜치가 이어받는다(R5). 그 전까지 착수 순서: **① LLM 무관 작업 먼저**(센티널 — Food·Flyway CHECK/NULL·upsertIncomplete·파급 테스트, 카탈로그 findAll 조회 경로 검증, `assessAvoidance`) → **② seam 인계 후** investigator(합의·검증)·프로세서·조립. tasks 분해 시 이 순서로 정렬한다.

**배포·운영 전제 (R9)**: api 배포(Flyway 적용) 전에는 신규 배치를 실행하지 않는다(배치 기본 off 라 순서 보장 용이). 병행 잡 인스턴스 방지는 현 단일 실행 운영에서 수용 위험으로 기록 — 다중 인스턴스 시 잡 락 후속 도입.

## Complexity Tracking

> 위반 없음 — 해당 없음.
