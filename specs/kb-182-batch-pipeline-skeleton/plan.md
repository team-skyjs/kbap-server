# Implementation Plan: 배치 콘텐츠 파이프라인 골격 재구축 — 음식 단위 처리 + READY 전이 규칙

**Branch**: `kb-182-batch-pipeline-skeleton` | **Date**: 2026-07-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-182-batch-pipeline-skeleton/spec.md`

## Summary

구 회피성분 스코어링 배치(`app/batch` scoring 패키지)와 `:domain:research` 모듈을 통째로 걷어내고, 그 자리에 새 골격을 세운다: INCOMPLETE 음식을 청크로 공급하는 도메인 창구(`FoodScoringSource` 대체), 콘텐츠 4작업(사진·설명·이름 번역·기피성분 매핑) 완비 시에만 READY 로 전이하는 `Food` 도메인 메서드, 음식 1건 단위로 처리→전이하며 실패를 건 단위로 격리하는 **Spring Batch chunk-oriented Step**(commit-interval=1). processor 가 LLM 호출을 작업별 메서드 4개로 구분만 해 두고(본문 비움), 후속 태스크(KB-183·184·209)가 각 메서드를 채운다. Spring Batch 로 재시작·실행 이력·스킵 관측성을 확보한다(성분 조사 3-API 종합 등 다중 외부호출 잡 대비).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring Batch 6.0(chunk-oriented Step), Spring Data JPA

**Storage**: MySQL — 기존 `food`·`food_avoidance_substance` 테이블 그대로 사용. **신규 마이그레이션 1개** — Spring Batch 메타데이터(`BATCH_*` 6테이블)를 api Flyway 가 생성(배치는 flyway off, 스키마 owner=api). food 컬럼 변경은 없음(`content_status` ENUM 이미 존재).

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + JUnit 플랫폼, 통합은 MySQL Testcontainers(`:core` testFixtures `MySqlContainerConfig`)

**Target Platform**: `:app:batch` bootJar (JVM 서버, 수동 실행 — 스케줄링 범위 밖)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 이번 변경 범위는 `:domain:food`, `:app:batch`, 빌드 스크립트(모듈 제거)

**Performance Goals**: 해당 없음(골격) — 청크 크기는 설정값으로 외부화해 운영 중 조정

**Constraints**: LLM 등 외부 호출은 DB 트랜잭션 밖(헌법 Additional Constraints) — 골격 단계부터 "스텝 실행(트랜잭션 없음) → 저장+전이(짧은 트랜잭션)" 구조로 강제. 배치는 도메인 서비스 그래프를 컴포넌트 스캔에 올리지 않음(`@Import` 조립).

**Scale/Scope**: 삭제 ~23파일(+빌드 3파일), 신규/수정 ~8파일. 처리 대상은 food 테이블 INCOMPLETE 행(현재 수백 건 규모)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 모든 신규 로직(전이 규칙·러너 격리·창구 쿼리)은 실패 테스트 선행. 삭제 작업은 "빌드·기존 테스트 그린 유지"가 게이트. |
| II. Bounded Contexts | PASS | 변경은 `:domain:food` 내부와 `:app:batch` 뿐. 기피성분 매핑은 food 소유 테이블(`FoodAvoidanceSubstance`)로 이미 food 컨텍스트 안. `:domain:research` 제거로 결합이 오히려 감소. |
| III. Layered Dependency Direction | PASS | 부트앱(`:app:batch`) → `:domain:food` 직접 의존은 허용 방향. 새 역방향 의존 없음. `:infra:llm` 의존은 유지(후속 스텝이 사용). |
| IV. Persistence Encapsulation | PASS | 리포지토리는 `internal` 유지. 배치의 유일 창구는 `:domain:food` 의 소형 도메인 서비스(`@Service` + `internal constructor`, `@Import` 조립) — 기존 `FoodScoringSource` 패턴 계승. 엔티티=도메인 모델은 2026-07-14 대개편(ADR-0012·CLAUDE.md)이 지배하는 현행 규약으로, 헌법 IV 의 구 "모델·엔티티 분리" 문구보다 우선한다(기존 `Food` 구조 그대로). |
| V. Domain Content Language Policy | PASS | READY 완비 판정은 9개 대상 언어 번역 전부 존재를 요구 — 사전 번역 정책과 정합. 콘텐츠 생성 자체는 후속 태스크. |
| 외부 호출 트랜잭션 밖 | PASS | 러너 구조가 스텝(외부 호출 자리)과 저장+전이 트랜잭션을 분리. |

**Post-Phase-1 재평가**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-182-batch-pipeline-skeleton/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — plan 은 생성하지 않음)
```

(contracts/ 는 생략 — 외부 노출 인터페이스가 없는 내부 배치. research.md 결정 D6 참조.)

### Source Code (repository root)

```text
# 삭제
domain/research/                                  # 모듈 통째 (main 15 + test 4 파일)
app/batch/src/main/kotlin/com/kbap/app/batch/scoring/   # 4파일
app/batch/src/test/kotlin/com/kbap/app/batch/scoring/   # 4파일
domain/food/src/main/kotlin/com/kbap/domain/food/FoodScoringSource.kt  # 대체됨

# 빌드 스크립트 수정
settings.gradle.kts                               # :domain:research include 제거
build.gradle.kts                                  # jacocoAggregation(:domain:research) 제거
app/batch/build.gradle.kts                        # :domain:research 의존 제거
app/batch/src/main/resources/application.yml      # kbap.scoring.* → kbap.batch.content.* 교체

# 신규/수정 — :domain:food
domain/food/src/main/kotlin/com/kbap/domain/food/
├── model/Food.kt                                 # READY 전이 도메인 메서드 추가
├── FoodJpaRepository.kt                          # INCOMPLETE 키셋 조회 쿼리 추가
└── FoodContentBatchService.kt                    # 신규 — 배치 전용 창구(조회+저장·전이)
domain/food/src/test/kotlin/com/kbap/domain/food/
├── model/FoodReadyTransitionTest.kt              # 신규 — 전이 규칙 단위 테스트
└── FoodContentBatchServiceTest.kt                # 신규 — 창구 통합 테스트(Testcontainers)

# 신규 — Spring Batch 메타데이터 (api Flyway, 스키마 owner)
app/api/src/main/resources/db/migration/
└── V2026.07.21.15.03.58__spring_batch_metadata.sql   # BATCH_* 6테이블 (schema-mysql.sql)

# 신규 — :app:batch (Spring Batch chunk-oriented Step)
app/batch/build.gradle.kts                         # spring-boot-starter-batch 추가
app/batch/src/main/kotlin/com/kbap/app/batch/content/
├── IncompleteFoodItemReader.kt                   # ItemStreamReader — INCOMPLETE 키셋 페이지 리더
├── FoodContentItemProcessor.kt                   # ItemProcessor — 4작업 메서드(본문 후속) + ProcessedFood
└── FoodContentBatchConfig.kt                     # Job/Step(commit-interval=1, skip) + writer + RunIdIncrementer
app/batch/src/main/resources/application.yml       # spring.batch.job.enabled=false, chunk-size(리더 페이지)
app/batch/src/test/resources/application.yml       # initialize-schema=always(테스트 메타 테이블 자동 생성)
# 배치 잡 단위 테스트는 사용자 지시로 생략 — 부팅 테스트(KbapBatchApplicationTests)로 컨텍스트 검증
```

**Structure Decision**: 기존 모듈 구조를 그대로 따른다. 배치 창구는 `FoodScoringSource` 가 쓰던 "레포지토리만 무는 소형 `@Service` + `@Import` 조립" 패턴을 계승해 `FoodContentBatchService` 로 대체하고, 잡 코드는 `com.kbap.app.batch.content` 패키지에 새로 둔다(scoring 패키지는 삭제).

## Complexity Tracking

위반 없음 — 기재 사항 없음.
