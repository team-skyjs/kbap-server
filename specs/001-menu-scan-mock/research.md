# Phase 0 Research — 메뉴 스캔 mock 슬라이스

스펙·헌법·기존 모듈 구조를 바탕으로 미해결 사항을 해소한다. 각 항목: **Decision / Rationale / Alternatives**.

## R1. 포함 비율 모델 — 연속 % vs 0/1/2 스코어

- **Decision**: `FoodIngredient.inclusionPercent`를 **연속 정수 퍼센티지(0~100)**로 모델링한다. `food.md`의 `0/1/2` 스코어 표기는 **연속 %로 대체**하며, 도메인 문서(`docs/architecture/domains/food.md`) 갱신은 **비차단 follow-up**으로 남긴다.
- **Rationale**: 실제 제품 UI가 `~50%`·`~100%`·`~85%` 같은 연속값을 직접 노출한다(스크린샷 근거). UI가 진실의 원천이며, 0/1/2 버킷으로는 표현이 손실된다.
- **Alternatives**: ① 0/1/2 유지 + 표시용 매핑(50/100%) — 버킷이 거칠어 UI 불일치. ② enum + 별도 % — 이중 소스, 불일치 위험. 기각.

## R2. mock 위험도 순환 — 위치와 형태

- **Decision**: application 계층에 `MenuItemRiskAssessor` 인터페이스(seam)를 두고, `MockCyclingRiskAssessor`가 **요청 항목 배열의 0-based index `% 4`** 로 `SAFE/CAUTION/DANGER/UNKNOWN`을 부여한다. reason은 단계별 고정 mock 문구. 도메인(scan)·컨트롤러(api)는 이 결과를 받기만 한다.
- **Rationale**: FR-013(교체 용이성) 충족 — 후속에 `assessment` 컨텍스트 호출 구현으로 갈아끼울 때 유스케이스 한 줄(빈 교체)만 바뀐다. 판정 로직이 도메인·web에 새지 않는다.
- **Alternatives**: ① 컨트롤러에서 직접 순환 — web에 판정 로직 누수, 교체 어려움. ② 도메인 엔티티가 자기 판정 — scan이 위험도 정책을 갖게 돼 BC 위반. 기각.

## R3. 재료별 riskStatus(mock) 부여 규칙

- **Decision**: application의 `IngredientRiskMarker`(seam) + `MockIngredientRiskMarker`로 부여한다. 데모를 위해 **결정적 규칙**: 음식의 재료 목록 중 **첫 번째 재료를 `CAUTION`("문의 필요"), 나머지를 `SAFE`("안전")**로 표시한다. riskStatus는 food 데이터에 **저장하지 않는다**.
- **Rationale**: riskStatus는 본래 사용자 식이 제한 의존(assessment) 값이다. 저장하면 사용자별 판정과 혼동된다. seam 뒤 mock으로 두면 후속 assessment 교체가 국소화된다. 첫 재료 CAUTION이면 UI의 두 상태(문의 필요/안전)를 한 음식으로 시연 가능.
- **Alternatives**: ① seed에 riskStatus 컬럼 저장 — 사용자 의존 값 오저장, 정책 혼선. ② 전부 SAFE — "문의 필요" UI 미시연. 기각.

## R4. scanId 식별 전략

- **Decision**: `scanId`는 **DB auto-increment `BIGINT` PK**로 한다(애플리케이션 UUID 생성 안 함). 응답엔 이 PK를 그대로 노출. 항목 itemId는 클라이언트 제공 정수(스캔 내 유일)로 PK가 아니라 스캔 종속 값.
- **Rationale**: 이번 슬라이스엔 **스캔 재조회 API·민감정보·인증이 없어** 순차 ID 노출의 enumeration 위험이 발휘될 데가 없다. auto-increment가 더 단순(생성 코드 불필요)하고 인덱스·삽입 성능도 유리. **남용 추적은 scanId 불투명화가 아니라 스캔에 "요청자(회원)"를 연결**해서 수행한다(R11 참조).
- **Alternatives**: ① UUID(CHAR(36)/BINARY(16)) — 재조회·민감정보·인증이 없는 현 범위엔 과설계, 랜덤 UUID는 클러스터드 인덱스 삽입에 불리. 기각. ② 후속에 재조회 API가 생겨 순차 ID 노출이 신경 쓰이면 **소유권 기반 authz로 방어**(권장)하거나 필요 시 외부 노출용 opaque 공개 ID를 별도로 둔다 — 그때 재검토.

## R5. MenuScan 상태 표현 범위

- **Decision**: `ScanStatus`는 이번 범위에서 **`COMPLETED` 단일**(엔티티에 상태 필드 보유하되 동기 mock은 즉시 COMPLETED). `FAILED`/`PARTIAL`/`PROCESSING`은 **예약(미사용)**.
- **Rationale**: 외부 호출·비동기·부분 실패가 없는 mock 동기 흐름. 헌법의 "pending→completed" 분리는 외부 호출이 있을 때 규칙이라 N/A. 필드만 두어 후속 확장 여지 확보.
- **Alternatives**: 상태 필드 생략 — 후속 마이그레이션 비용. 전체 상태머신 구현 — 현 범위 과설계. 기각.

## R6. 음식 상세 매칭 대상 필드

- **Decision**: API 2의 `menuName`은 **`Food.koreanName`에 trim 후 exact match**한다. (englishName 매칭은 후속.) 매칭은 대소문자/공백 민감하되 양끝 공백만 trim.
- **Rationale**: 메뉴판은 한국어이고 스캔의 rawMenuName도 한국어다. 상세 조회도 동일 한국어 메뉴명으로 들어오는 게 자연스럽다. exact match는 spec Clarification 확정값.
- **Alternatives**: ko+en 동시 매칭 — 모호/중복 위험, 범위 확대. 정규화/별칭 — spec상 후속. 기각.

## R7. 대상 언어 결정 방식 (API 2 — 9개국어, B-2)

- **Decision (이번 슬라이스, B-2)**: API 2는 **쿼리 파라미터 `lang`** 으로 대상 언어를 받아 **한 언어**의 번역본을 반환한다. 미지정/미지원 → `ko` 폴백(400 아님). 음식·재료명은 `ko` 원문 + 9개 번역으로 저장(seed가 직접 보유). 헌법 V를 v2.0.0으로 개정해 이 정책을 합법화. (이전 "lang 파라미터 없이 ko/en 두 필드" 결정을 대체.)
- **Decision (향후 방향, 비차단)**: 응답 언어의 **권위 있는 출처는 인증된 회원 `MemberProfile.사용 언어`**(member.md). `lang` 쿼리/`Accept-Language`는 **익명/프로필 미설정 폴백**. api/application이 하나의 "target language"로 해석(`LanguageResolver` seam)해 유스케이스에 주입하고 food 도메인은 출처를 모른다 → 출처 교체(쿼리→회원)가 상위 한 곳에 국소화.
- **Rationale**: 응답 계약(언어 1개 반환)을 최종형과 동일하게 잡아, 회원/배치가 들어와도 **계약을 안 바꾸고 언어 출처만 교체**. 전체 언어 반환(B-1)은 최종형과 모양이 달라 나중에 재작업이 생긴다. `lang` 쿼리는 mock에서 테스트·시연이 쉽고 R7 폴백 경로와 정합.
- **선후 관계(여전히 후속)**: 실제 번역 생성(`meogo-batch` LLM)·회원 기반 언어 해석은 이번 범위 밖. 이번엔 **seed가 9개 번역을 직접 보유**하고 `LanguageResolver`는 단순 화이트리스트+폴백.
- **Alternatives**: ① B-1 전체 언어 반환 — 페이로드↑·최종형과 불일치(재작업). 기각. ② 헤더(Accept-Language)만 — 테스트/시연 불편, mock엔 쿼리가 단순. 향후 폴백으로 병행 가능.

## R8. 입력 검증 전략(400 처리)

- **Decision**: Bean Validation(`spring-boot-starter-validation`)을 1차로 쓰고, **리스트 교차 제약(itemId 중복)은 유스케이스/전용 검증에서 명시 검사** 후 예외 → `GlobalExceptionHandler`가 400 `ApiResponse.fail(...)`로 매핑.
  - `items`: `@field:NotEmpty` + `@field:Size(max=100)`
  - `itemId`: `@field:NotNull`(Int) + 중복은 수동 검사
  - `rawMenuName`: `@field:NotBlank`
  - `boundingBox`: `@field:NotNull` + `@field:Valid`; 내부 `width`/`height` `@field:Positive`, `x`/`y` `@field:PositiveOrZero`, **상한 1.0은 `@field:DecimalMax("1.0")`**. 교차 제약 **`x+width≤1`·`y+height≤1`은 단일 필드로 표현 불가** → BoundingBox DTO에 `@AssertTrue` 검증 메서드(또는 클래스 레벨 커스텀 validator)로 처리. 도메인 `BoundingBox` VO 생성자에서도 동일 불변식을 재검증(이중 방어).
  - `menuName`(API 2): `@field:NotBlank`(쿼리 파라미터) → 누락/blank 400
- **Rationale**: 선언적 검증으로 대부분 커버, Bean Validation이 못 하는 컬렉션 유일성만 코드로. 예외→핸들러 일원화로 400/404 응답 형식을 `ApiResponse`로 통일.
- **Alternatives**: 전부 수동 검사 — 보일러플레이트. 전부 어노테이션 — 중복 검사 불가. 혼합 채택.

## R9. 404 표현(음식 없음)

- **Decision**: `GetFoodDetailUseCase`가 미발견 시 `FoodNotFoundException`을 던지고, `GlobalExceptionHandler`가 **HTTP 404 + `ApiResponse.fail("해당 음식 정보 없음")`**으로 매핑. menuName 누락/blank는 검증 단계에서 **400 + `ApiResponse.fail("menuName은 필수입니다")`**.
- **Rationale**: spec #3 확정. "리소스 없음"의 표준 시맨틱(404)과 "잘못된 요청"(400)을 분리. 봉투는 항상 `ApiResponse`.
- **Alternatives**: 200 + `success=false`(빈 data) — HTTP 시맨틱 약화, 클라 분기 어려움. 기각.

## R10. 테스트 전략(헌법 I)

- **Decision**: 계층별 테스트를 **실패 먼저** 작성.
  - 도메인 단위: `BoundingBox` 검증, `MenuScan` 조립 불변식 — 순수 Kotlin/Kotest.
  - mock 판정 단위: `MockCyclingRiskAssessor`의 index%4 순환, `MockIngredientRiskMarker`.
  - 영속: `MenuScanRepositoryAdapter`·`FoodRepositoryAdapter` — H2(`@DataJpaTest` 또는 슬라이스), 저장/조회 검증(SC-006).
  - web 계약: `MenuScanController`·`FoodDetailController` — MockMvc로 200/400/404 + `ApiResponse` 형태·itemId 매칭·4단계 분포 검증.
  - seed 검증: V3 seed 음식이 조회되는지 통합 확인.
- **Rationale**: 헌법 I(NON-NEGOTIABLE) + spec의 Independent Test/Success Criteria를 테스트로 직접 사상.
- **Alternatives**: 통합 테스트만 — 빠른 피드백·경계 검증 약화. 단위만 — 계약/저장 미검증. 피라미드 혼합 채택.

## R11. 요청자(스캔한 사용자) 기록 — 남용 추적

- **Decision**: 이번 슬라이스는 **완전 익명**(menu_scan에 requester 컬럼 없음). 이상 사진 추적·이용 제한을 위한 요청자 식별은 **auth/member 기능으로 전면 이관**한다.
- **Rationale**: "어떤 사용자가 찍었는지" 추적해 제한하려면 **신뢰 가능한 신원**이 필요하고, 그건 인증(현재 비범위)이 전제다. 클라이언트 제공 식별자(deviceId)는 spoofable해 남용 제한 근거로 약하다. 지금 always-NULL 컬럼만 두는 것은 가치가 낮고(YAGNI), 후속에 nullable 컬럼 추가 마이그레이션은 trivial(비파괴적)이므로 이관이 합리적.
- **연계**: 후속 auth/member 기능에서 `menu_scan.requester_id`(member FK) 추가 + 유스케이스가 인증 주체로 채움. scanId(auto-increment, R4)는 그대로 두고, 재조회 API 추가 시 **소유권 authz**로 보호.
- **Alternatives**: ① 지금 nullable requester_id 예약 — always NULL, 가치 낮음. ② deviceId 등 interim 식별자 — spoofable·요청 스키마 확장. 모두 기각(이관 채택).

## R12. boundingBox 좌표계 — 정규화 비율

- **Decision**: boundingBox는 **정규화 비율 좌표**(픽셀 아님). 기준은 **클라이언트가 OCR을 수행한 이미지**, 좌상단 (0,0)·우하단 (1,1). 필드 범위 `x∈[0,1)`, `y∈[0,1)`, `width∈(0,1]`, `height∈(0,1]`, 검증 `x≥0, y≥0, width>0, height>0, x+width≤1, y+height≤1`. 저장은 `DOUBLE` 컬럼 그대로(값 의미만 비율로 확정).
- **Rationale**: 클라이언트 OCR 기준 이미지와 S3/Lambda 압축 후 표시 이미지의 **해상도가 달라도** 비율은 불변이라, 클라이언트가 표시 크기에 곱해 오버레이를 안정적으로 복원할 수 있다. 픽셀 좌표는 표시 해상도에 종속돼 위치가 어긋난다. (이전 "opaque Double, 단위 미규정" 결정을 대체.)
- **전제/제약**: 이미지 압축은 **aspect ratio 보존** 필수(Lambda). **crop/padding/rotation/orientation 보정처럼 좌표 기준을 바꾸는 처리는 이번 범위 밖** — 도입 시 이미지 변환 메타데이터 또는 좌표 재계산 정책을 별도 정의.
- **Alternatives**: ① 픽셀 + 원본 이미지 해상도 동봉 — 변환 부담·해상도 종속. ② opaque(단위 미규정) — 압축으로 해상도 달라지면 오버레이 복원 불안정(이 변경의 동기). 기각.

## 후속(이번 범위 밖) 메모

- `docs/architecture/domains/food.md`의 포함도 `0/1/2` → 연속 % 문서 갱신(ADR 또는 doc edit).
- 헌법 원칙 V(한·영만) ↔ ADR-0003(9개국어) 정합 — 9개국어 기능 착수 시 헌법 개정 선행.
- ArchUnit 경계 강제 테스트 일괄 도입(별도 기능).
