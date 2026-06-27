# Implementation Plan: 메뉴 스캔 제출·판정 & 음식 상세 조회 (mock 슬라이스)

**Branch**: `001-menu-scan-mock` | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-menu-scan-mock/spec.md`

## Summary

두 개의 web API를 구현한다. **(1) `POST /menu-scans`** — 클라이언트가 메뉴 항목(itemId·rawMenuName·boundingBox) 배열을 보내면, 서버가 항목 배열 순서 기준으로 mock 4단계 위험도(`SAFE/CAUTION/DANGER/UNKNOWN`)를 부여하고 스캔·항목·결과를 MySQL에 최소 저장한 뒤 itemId로 매칭되는 결과를 반환한다. **(2) `GET /foods/detail?menuName=&lang=`** — 메뉴명(trim 후 ko 원문 exact match)으로 seed된 음식 상세(요청 `lang` 음식명·대표 이미지·재료 목록[재료명·아이콘·포함%·mock riskStatus])를 반환하고, `lang` 미지원/미지정은 `ko` 폴백, 없으면 404, menuName 누락/blank면 400. 음식·재료명은 ko 원문 + 9개 대상 언어로 저장(seed 보유).

기술 접근: 기존 멀티모듈 골격(`:meogo-api:{api,application,scan,food,core,infra}`)에 **첫 도메인 코드**를 채운다. 컨트롤러·DTO·`ApiResponse<T>`·예외 핸들러는 `:meogo-api:api`, 유스케이스는 `:meogo-api:application`, 도메인 엔티티·JPA·Repository는 `:meogo-api:{scan,food}`에 은닉한다. `RiskLevel`은 컨텍스트 공유 커널 타입이라 `:meogo-api:core`에 둔다. mock 판정은 application 계층의 교체 가능한 collaborator로 격리(FR-013)해, 후속에 실제 `assessment` 호출로 갈아끼운다. 스키마는 `:meogo-api:api`의 Flyway 마이그레이션이 소유한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web, validation, data-jpa), Flyway(+flyway-mysql), springdoc-openapi. 테스트: JUnit 5 platform + Kotest + spring-boot-starter-test

**Storage**: MySQL(prod/local) + H2(test, `create-drop`, flyway off). 이번 기능은 JPA/MySQL만 사용(MongoDB 미사용)

**Testing**: `./gradlew test` — Kotest/JUnit5. 단위(도메인·mock 판정), 영속(repository, H2), web(@SpringBootTest / MockMvc 또는 @WebMvcTest)

**Target Platform**: Linux server (web bootJar `:meogo-api:api`, 진입점 `com.meogo.MeogoApiApplication`)

**Project Type**: Web service (Gradle 멀티모듈, 단일 web 앱 + 도메인/계층 모듈)

**Performance Goals**: mock 동기 처리 — 사용자 체감 즉시(수 초 이내, SC-004). 외부 호출 없음

**Constraints**: 외부 LLM/네트워크 호출 없음(mock). DB 트랜잭션은 짧게(단일 유스케이스 트랜잭션). 도메인/영속 모델을 API 응답으로 직접 노출 금지(DTO 매핑)

**Scale/Scope**: 한 스캔당 항목 ≤ 100개. seed 음식 소수(데모용). 2개 엔드포인트, 2개 도메인 컨텍스트(scan, food)

### 미해결 → research.md에서 해소

- 포함 비율 모델: UI 연속 %(0~100) vs `food.md`의 `0/1/2` 스코어 → 채택 결정 + 문서 reconcile
- mock 재료 riskStatus 부여 규칙(사용자 프로필 없음)
- mock 위험도 collaborator 격리 위치/형태
- scanId 식별 전략, MenuScan 상태 표현 범위
- 음식 상세 매칭 키 = `ko` 원문 음식명(R6) · 응답 언어 = `lang` 파라미터(R7, B-2)

## Constitution Check

*GATE: Phase 0 전 통과 필수. Phase 1 설계 후 재점검.*

| 원칙 | 적용 | 상태 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | 모든 task는 실패 테스트 먼저(Red→Green→Refactor). 도메인·mock 판정·repository·web 계층 각각 테스트 선작성 | ✅ PASS (tasks에서 강제) |
| **II. Bounded Contexts — No Cross-Domain Coupling** | `scan`·`food` 도메인 모듈은 서로 의존 안 함. 두 유스케이스는 각자 한 컨텍스트만 사용(조합 거의 없음). 컨텍스트 간 조합이 생기면 `application`에서만 | ✅ PASS |
| **III. Layered Dependency Direction** | `api → application → 도메인`. 컨트롤러(api) → 유스케이스(application) → 도메인 Repository(scan/food). `infra`는 이번 기능 미사용(런타임 조립 유지) | ✅ PASS |
| **IV. Persistence Encapsulation** | JPA Entity·Spring Data Repository·DomainRepository 구현체는 `scan`/`food` 모듈 내부(`infrastructure` 패키지)에 은닉. application/api는 도메인 엔티티 + DomainRepository 인터페이스만 사용 | ✅ PASS (ArchUnit 강제는 후속 — 아래 Complexity 참조) |
| **V. Domain Content Language Policy (ko 원문 + 9개 대상 언어)** | 헌법 v2.0.0 개정 반영 — FoodDetail 음식명·재료명을 **`ko` 원문 + 9개 대상 언어로 저장**(seed가 번역 직접 보유), API는 `lang` 번역본 반환(미지원 → ko 폴백). 실제 번역 생성(배치)·회원 언어 해석은 후속 | ✅ PASS |

**Additional Constraints**: 스택/2-bootJar/외부호출-트랜잭션 분리/응답 DTO 노출 금지 — 모두 충족(이번 기능은 외부 호출 없음). **GATE 통과.**

> ✅ 정책 정합성: 헌법 원칙 V를 **v2.0.0으로 개정**(한·영만 → ko 원문 + 9개 대상 언어, ADR-0003)하여 이전 헌법↔ADR 상충(C1)을 해소했다. 본 기능은 9개국어를 seed 기반으로 구현하며 헌법과 정합한다.

## Project Structure

### Documentation (this feature)

```text
specs/001-menu-scan-mock/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 기록
├── data-model.md        # Phase 1 — 엔티티·스키마
├── quickstart.md        # Phase 1 — 실행·검증 가이드
├── contracts/           # Phase 1 — API 계약
│   ├── menu-scan-api.md
│   └── food-detail-api.md
├── checklists/
│   └── requirements.md  # (기존) spec 품질 체크리스트
└── tasks.md             # /speckit-tasks 산출(이 명령에선 미생성)
```

### Source Code (repository root)

기존 모듈 골격에 아래 파일을 채운다. 패키지 규약: 도메인=`com.meogo.domain.<context>`, 계층=`com.meogo.<layer>`, web=`com.meogo.api`, 커널=`com.meogo.core`.

```text
meogo-api/
├── core/src/main/kotlin/com/meogo/core/
│   └── risk/RiskLevel.kt                         # 공유 커널: SAFE/CAUTION/DANGER/UNKNOWN
│
├── scan/                                          # scan 바운디드 컨텍스트
│   ├── src/main/kotlin/com/meogo/domain/scan/
│   │   ├── MenuScan.kt                            # Aggregate Root (도메인)
│   │   ├── ScannedMenuItem.kt                     # 항목 (도메인)
│   │   ├── BoundingBox.kt                         # 값 객체(x/y/width/height: Double)
│   │   ├── MenuItemAssessment.kt                  # mock 판정 스냅샷(itemId·riskLevel·reason)
│   │   ├── ScanStatus.kt                          # COMPLETED(이번 범위 단순)
│   │   ├── MenuScanRepository.kt                  # DomainRepository 인터페이스(공개)
│   │   └── infrastructure/                        # 영속 은닉(상위 import 금지)
│   │       ├── MenuScanJpaEntity.kt
│   │       ├── ScannedMenuItemJpaEntity.kt
│   │       ├── MenuScanJpaRepository.kt           # Spring Data
│   │       └── MenuScanRepositoryAdapter.kt       # DomainRepository 구현
│   └── src/test/kotlin/com/meogo/domain/scan/...  # 도메인·repository 테스트
│
├── food/                                          # food 바운디드 컨텍스트
│   ├── src/main/kotlin/com/meogo/domain/food/
│   │   ├── Food.kt                                # Aggregate Root(koreanName=ko 매칭키·names[9개]·imageRef·재료, nameFor(lang))
│   │   ├── Ingredient.kt                          # 재료(koreanName·names[9개]·iconRef, nameFor(lang))
│   │   ├── LanguageCode.kt                        # ko + 9개 대상 언어 enum(폴백 ko)
│   │   ├── FoodIngredient.kt                      # 관계(inclusionPercent 0~100)
│   │   ├── FoodRepository.kt                      # DomainRepository 인터페이스(공개)
│   │   └── infrastructure/
│   │       ├── FoodJpaEntity.kt / FoodNameTranslationJpaEntity.kt
│   │       ├── IngredientJpaEntity.kt / IngredientNameTranslationJpaEntity.kt / FoodIngredientJpaEntity.kt
│   │       ├── FoodJpaRepository.kt
│   │       └── FoodRepositoryAdapter.kt           # 음식+재료+번역 로드 → 도메인 매핑
│   └── src/test/kotlin/com/meogo/domain/food/...
│
├── application/src/main/kotlin/com/meogo/application/
│   ├── scan/
│   │   ├── SubmitMenuScanUseCase.kt               # 유스케이스(@Transactional)
│   │   ├── SubmitMenuScanCommand.kt / MenuScanResult.kt   # application 레벨 입출력 타입
│   │   ├── MenuItemRiskAssessor.kt                # 판정 seam(인터페이스)
│   │   └── MockCyclingRiskAssessor.kt             # index%4 순환 mock 구현
│   └── food/
│       ├── GetFoodDetailUseCase.kt                # menuName+lang → 상세(요청 언어)
│       ├── GetFoodDetailQuery.kt / FoodDetailResult.kt
│       ├── LanguageResolver.kt                    # lang → 지원 LangCode/ko 폴백(향후 회원 출처)
│       ├── IngredientRiskMarker.kt                # 재료 riskStatus seam(인터페이스)
│       └── MockIngredientRiskMarker.kt            # 데모용 mock 부여
│
├── api/                                           # web 조립 모듈
│   ├── src/main/kotlin/com/meogo/api/
│   │   ├── common/ApiResponse.kt                  # 공통 응답 봉투(고정 규약)
│   │   ├── common/GlobalExceptionHandler.kt       # 400/404 → ApiResponse.fail 매핑
│   │   ├── scan/MenuScanController.kt             # POST /menu-scans
│   │   ├── scan/dto/...                           # Request/Response DTO + Bean Validation
│   │   ├── food/FoodDetailController.kt           # GET /foods/detail
│   │   └── food/dto/...
│   ├── src/main/resources/db/migration/           # Flyway(스키마 owner)
│   │   ├── V1__create_scan_tables.sql
│   │   ├── V2__create_food_tables.sql
│   │   └── V3__seed_food_data.sql
│   └── src/test/kotlin/com/meogo/api/...          # web 계약 테스트(MockMvc)
```

**Structure Decision**: 기존 멀티모듈(Web service) 구조를 그대로 사용한다. 신규 모듈은 만들지 않고 `:meogo-api:{api,application,scan,food,core}`에 파일을 채운다. `infra`·`member`·`assessment`·`review`·`meogo-batch`·`meogo-common`은 이번 기능에서 손대지 않는다. `RiskLevel`만 `core`에 추가해 컨텍스트 간 공유한다.

## Complexity Tracking

> 헌법 위반 없음. 아래는 **의도적 단순화/이연 항목**(위반 아님) 기록.

| 항목 | 결정 | 사유 |
|------|------|------|
| ArchUnit 경계 강제 테스트 | 이번 기능에선 미도입(후속 공통 작업) | CLAUDE.md가 "추후 ArchUnit 으로 강제"로 명시 이연. 이번엔 패키지 가시성 + 리뷰로 경계 유지. 별도 기능에서 ArchUnit 일괄 도입 권장 |
| MenuScan 상태 머신 | `COMPLETED` 단일(동기 mock) | 외부 호출/비동기 없음 → pending/partial 상태 불필요. 실제 LLM 도입 시 확장 |
| 재료 riskStatus 출처 | application의 mock marker(저장 안 함) | riskStatus는 사용자 의존(assessment) 값이라 food 데이터에 저장하지 않음. mock은 seam 뒤에 둬 후속 교체 |
| FoodIngredient 포함도 | 연속 %(0~100) 채택, `food.md` 0/1/2와 불일치는 문서 reconcile(후속) | 실제 제품 UI가 연속 %를 표시 → UI 기준 채택. 도메인 문서 갱신은 비차단 follow-up |
