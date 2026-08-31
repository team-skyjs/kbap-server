# Implementation Plan: 스캔 v2 — 서버 OCR 파이프라인과 유사 음식 대체 응답

**Branch**: `kb-319-scan-ocr-vector-fallback` | **Date**: 2026-08-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-319-scan-ocr-vector-fallback/spec.md`

## Summary

기존 `POST /api/v1/scans` 에 **`X-API-Version >= 2026.08.07` 헤더 분기**(KB-300 과 동일 정책, `api.core.ApiVersion` 재사용)로 스캔 v2 를 공존시킨다. v2 요청은 `imagePath` 만으로 성립하고(클라이언트 OCR `items` 불필요), 서버가 **힌트 없는 비전 프롬프트 1회 호출**로 OCR·이름/가격 정제를 수행한다(기존 `MenuBoardVisionExtractor` seam 재사용 — Jira 의 2단 LLM 은 단일 비전 호출로 통합, v1 선례). 정제된 이름을 기존 매칭 키로 MySQL 대조 후, **miss 항목은 `com.kbap.api.scan` 의 `SimilarFoodSearcher`**(DocumentDB 를 영속 접근으로 취급 — `mongodb-driver-sync` 를 `:api` 에 직접 추가, 인터페이스는 테스트 대역용. 신규 모듈·port 없음)로 벡터 검색해 **최유사 foodId 를 얻고 MySQL 재조회로 이름·설명·사진을 응답**한다(단일 진실 = MySQL, 상세 조회 정합 자동 확보). 응답은 기존 `ScanResponse` 에 `similarFood` 필드를 추가(additive — v1 은 항상 null). 검색 실패·임계 미달·빈 저장소는 유사 대체 없이 기존 미등록 응답으로 폴백(부분 성공). DB 스키마·Flyway 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0(`:infra:llm` — 비전·Bedrock Titan V2 임베딩 `TextEmbeddingClient` #136 재사용), **신규: `mongodb-driver-sync`**(`:api` 에 직접 추가 — DocumentDB 는 MongoDB 호환, 영속 접근 취급)

**Storage**: MySQL(음식 마스터·scan_history — **스키마 변경 없음**) + **AWS DocumentDB**(벡터 검색 저장소, KB-318 구축 완료 — 읽기 전용 소비, 적재는 범위 밖)

**Testing**: Kotest `BehaviorSpec` + MySQL·Redis Testcontainers + MockMvc. 벡터 검색·임베딩은 **fake seam** 으로 단위/통합 검증(DocumentDB 는 Testcontainers 불가 — `$search` 문법이 DocumentDB 전용, 로컬 MongoDB 와 다름 → 어댑터는 얇게 유지하고 dev 클러스터 수동 검증)

**Target Platform**: Linux 서버 (`:api` bootJar)

**Project Type**: 모듈러 모놀리스 (`:common`·`:api` — **신규 모듈·인프라 변경 없음**, KB-244 다이어트 방향 유지)

**Performance Goals**: v2 스캔 지연 = 비전 1회(기존과 동일) + 임베딩 1회 + 벡터 검색 1회(miss 있을 때만) — 기존 스캔과 동등 수준 유지(SC-006)

**Constraints**: 기존 스캔 계약 완전 불변(FR-011). 외부 호출(비전·임베딩·벡터 검색)은 트랜잭션 밖(헌법 Additional Constraints). 벡터 저장소 미가용 시 스캔 자체는 성공해야 함(FR-009). 임베딩 차원 256 고정(KB-318)

**Scale/Scope**: `:api` scan 기능 패키지에 신규 3파일(검색 인터페이스+구현·조립·리졸버) + 기존 파일 수정(controller·request·service·response), yml 설정, api build 에 드라이버 의존 1줄. 신규 모듈·port·마이그레이션 0건

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | PASS | fake `SimilarFoodSearcher`·`TextEmbeddingClient` 로 v2 분기·hit/miss·폴백을 MockMvc 통합 테스트로 Red 확인 후 구현. 헤더 분기·요청 완화는 KB-300 테스트 선례 재사용 |
| **II. Bounded Contexts** | PASS | 신규 도메인 없음 — scan 유스케이스 조합은 `com.kbap.api.scan` 에 유지, 음식 재조회는 기존 food 도메인 서비스 경유. 벡터 문서는 도메인이 아니라 외부 시스템 응답(포트 반환 타입) |
| **III. Layered Dependency Direction** | PASS | DocumentDB 는 **영속(데이터스토어) 접근으로 분류**(2026-08-10 사용자 결정, research R3) — MySQL JPA 가 seam 없이 직접 접근하는 것과 동일 취급이라 외부 시스템 seam 조항의 대상이 아니다. 구현·조립은 `com.kbap.api.scan` 소유, 모듈 의존 방향(api→common) 무변경. 임베딩은 기존 seam(`common.port.llm`) 경유 유지 |
| **IV. Persistence Ownership** | PASS | 엔티티·리포지토리·스키마 무변경. scan_history 기록은 기존 경로 재사용. DocumentDB 는 JPA 영속이 아니라 외부 시스템(seam 소비) — Redis(`RefreshTokenStore`)와 같은 취급 |
| **V. Domain Content Language Policy** | PASS | 유사 음식 응답을 **foodId 로 MySQL 재조회**해 기존 `displayName(lang)`·설명 번역 체계를 그대로 태운다 — 벡터 문서 메타데이터를 사용자 응답에 직접 노출하지 않으므로 번역 정책 우회가 없다. `lang` 검증은 기존 요청 경계 유지 |

**추가 제약 점검**: 비전·임베딩·벡터 검색 전부 트랜잭션 밖(기존 ScanService 의 의도적 무트랜잭션 구조 유지). 도메인/영속 모델 응답 직접 노출 없음(기존 ScanResponse 변환 유지).

위반 없음. Complexity Tracking 불필요.

**Phase 1 설계 후 재점검 (PASS)**: 신규 도입은 `:api` scan 기능 패키지 내 3파일·응답 필드 1개·api 의존 1줄뿐 — 모듈 그래프·인프라 모듈·common.port 전부 무변경(2026-08-10 개정 2, research R3). 검색 인터페이스는 계약 추상화가 아니라 테스트 대역용(DocumentDB 로컬 재현 불가)임을 명시해 과추상화가 아니다. 벡터 문서 계약을 서버가 소유·문서화(contracts/vector-food-document.md)해 적재 측(랭체인)과의 드리프트를 계약 문서로 방어한다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-319-scan-ocr-vector-fallback/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── scan-v2.md                  # v2 요청/응답 계약 (헤더 분기)
│   └── vector-food-document.md     # DocumentDB 음식 도큐먼트·검색 계약
└── tasks.md             # /speckit-tasks 산출 — 이 명령이 만들지 않음
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/scan/
├── ScanController.kt                # 수정 — X-API-Version 헤더 수신 + >= 2026.08.07 분기
├── ScanApi.kt                       # 수정 — 헤더 @Parameter·v2 동작 swagger 문서
├── ScanRequest.kt                   # 수정 — items @NotEmpty 완화(분기 조건부 검증으로 이동)
├── ScanService.kt                   # 수정 — v2 경로(힌트 없는 추출 + miss 유사 폴백) 조합
├── SimilarFoodSearcher.kt           # 신규 — fun interface + SimilarFoodDocument (테스트 대역용 인터페이스)
├── DocumentDbSimilarFoodSearcher.kt # 신규 — $search vectorSearch 집계 + MongoClient 조립(@ConditionalOnProperty kbap.vector.enabled)
├── SimilarFoodResolver.kt           # 신규 — 임베딩→검색→임계 판정→MySQL 재조회 (빈 부재 시 no-op)
├── ScanResult.kt / ScanResponse.kt  # 수정 — similarFood 필드 추가(additive)
└── (기존 파일 유지)

infra/llm/src/main/kotlin/com/kbap/infra/llm/menu/
└── OpenAiMenuBoardVisionExtractor.kt  # 수정 — ocrItems 비어 있으면 힌트 없는 서버 OCR 프롬프트 분기

api/src/main/resources/application*.yml  # kbap.vector.* 연결 설정 + 프로필별 kbap.llm.embedding.enabled
api/build.gradle.kts                     # "implementation"(libs.mongodb.driver.sync) 추가
```

**Structure Decision**: DocumentDB 를 영속 접근으로 취급해 검색 구현·조립·유스케이스 조합을 전부 `com.kbap.api.scan` 기능 패키지에 응집한다(ADR-0017, research R3 개정 2 — 사용자 결정). `@ConditionalOnProperty("kbap.vector.enabled")` 게이트로 로컬(클러스터 없음) 부팅이 안전하다. 인프라 모듈·common.port 는 건드리지 않는다(임베딩은 기존 `:infra:llm` seam 재사용).

## Complexity Tracking

> Constitution Check 위반 없음 — 작성하지 않는다.
