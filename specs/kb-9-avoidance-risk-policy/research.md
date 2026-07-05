# Phase 0 Research: 기피성분 위험도 정책 (KB-9)

스펙의 열린 결정과 설계 선택을 확정한다. 형식: 결정 / 근거 / 대안.

## R1. 위험도 정책(임계값·심각도)의 소유 위치

- **결정**: `:core:kernel`의 `RiskLevel`이 임계값(10, 60)·심각도 순서·확률→위험도 매핑·집계를 단일 출처로 소유한다. 순수 함수/상수로 추가:
  - `fun fromInclusionProbability(probability: Int): RiskLevel` — `p<10`→SAFE, `10≤p<60`→CAUTION, `else`→DANGER.
  - `fun aggregate(levels: Collection<RiskLevel>): RiskLevel` — 아래 R3 규칙.
  - 임계값은 `const`(예: `SAFE_BELOW=10`, `DANGER_AT_LEAST=60`).
- **근거**: `RiskLevel`은 이미 kernel 에 있는 **여러 도메인 공용 vocabulary**다(CLAUDE.md). 확률→위험도 규칙과 심각도 순서는 성분별·종합 판정이 **공유해야 하는 단일 규칙**(FR-010)이므로 vocabulary 옆에 두는 것이 자연스럽다. kernel 은 완전 Spring-free 라 순수 함수 추가에 제약 없음.
- **대안**: (a) `:core:food`에 정책을 두기 — 확률은 food 소유지만 위험도 어휘·심각도는 food 소유가 아니며 향후 scan 등도 동일 매핑을 쓸 수 있어 중복 위험. (b) 별도 정책 서비스 빈 — Spring 빈이 필요 없고(순수 함수), `@AggregateRoot`/kernel Spring-free 원칙과 어긋남.

## R2. 음식 종합 위험도 판정의 소유 위치

- **결정**: `:core:food`의 `Food` 애그리거트가 `overallRisk(avoidedCodes: Set<String>): RiskLevel`로 소유한다. 회피 코드는 **String 집합**으로 받아 `avoidanceSubstances`의 `substanceCode.value`와 교집합한다.
- **근거**: 종합 판정은 음식이 가진 성분·확률(애그리거트 내부 상태)에 대한 도메인 규칙이다. 원칙 II("Aggregate 내부 상태는 Aggregate Root를 통해서만")에 맞고, 원칙 II("food 는 avoidance enum 을 import 하지 않고 코드로 참조")를 지키려 **String 코드**로 받는다(avoidance enum 미의존).
- **대안**: 유스케이스에서 절차적으로 계산 — 도메인 규칙이 애플리케이션으로 새어 테스트·재사용성이 떨어진다. `AvoidanceSubstanceCode`(avoidance enum)로 받기 — food→avoidance 컴파일 의존이 생겨 원칙 II 위반.

## R3. 종합 집계 규칙(§4)과 UNKNOWN 우선순위(§5·§8)

- **결정**: `aggregate(levels)`:
  1. `levels`가 비면 → **SAFE**(대상 없음 = 안전, §4·FR-004).
  2. `levels`에 **UNKNOWN**이 하나라도 있으면 → **UNKNOWN**(§8 "결측 1개라도 음식 UNKNOWN" 문자 그대로 — UNKNOWN 이 SAFE/CAUTION/DANGER 보다 우선).
  3. 그 외 → SAFE<CAUTION<DANGER 심각도 최댓값(§4 최악값).
- **근거**: 사용자가 §8 을 "결측 1개라도 음식 UNKNOWN"으로 확정. 판정 불가를 다른 성분의 SAFE/낮은 값으로 덮어 **SAFE 로 오도하지 않는다**(§5 원칙). `Food.overallRisk`는 목 회피 ∩ 음식 성분(모두 확률 보유)만 매핑하므로 실데이터에서 UNKNOWN 입력은 생기지 않고(R4), UNKNOWN 분기는 정책 단위 테스트로 검증한다.
- **주의(문서화된 미세 쟁점)**: 규칙 2가 규칙 3보다 우선하므로, 이론상 "확실한 DANGER 성분 + 결측 성분"이 함께면 결과가 UNKNOWN 이 된다(안전상 DANGER 가 더 행동가능하다는 관점도 가능). 현재 데이터 모델에서 도달 불가(R4)라 실질 영향 없음. 향후 결측이 실제로 발생하는 데이터 모델이 되면 "DANGER > UNKNOWN" 재정의를 재검토한다.

## R4. "확률 결측"(§8/FR-007)의 도달성

- **결정**: 현재 도메인 불변식상 `FoodAvoidanceSubstance.inclusionProbability`는 필수 `Int`(1..100)다. 따라서 **로드된 음식의 판정 대상 성분은 항상 확률을 가진다** — §8 "확률 결측"을 트리거하는 데이터 상태가 현 스키마엔 존재하지 않는다. FR-007 은 **정책 단위의 방어적 규칙**으로 인코딩하고(`aggregate`의 UNKNOWN 우선, R3-2), `RiskLevel.aggregate` 단위 테스트로 "UNKNOWN 입력 → UNKNOWN 출력"을 검증한다. `Food.overallRisk`는 실데이터에서 UNKNOWN 을 방출하지 않는다.
- **근거**: 존재하지 않는 데이터 상태를 억지로 만들어 KB-47(카탈로그 결측 skip)·도메인 불변식과 충돌시키지 않으면서, 사용자가 요구한 규칙을 **미래 대비 안전장치**로 정확히 남긴다. Test-First 정합을 위해 규칙 자체는 정책 함수 수준에서 반드시 테스트한다.
- **대안**: 카탈로그 결측(KB-47)을 결측으로 재해석 — 확률은 카탈로그가 아니라 food 행에 있어 카탈로그가 없어도 확률은 resolvable 하므로 부정확. `inclusionProbability`를 nullable 로 완화 — 도메인 불변식을 약화시키고 스키마/엔티티 변경을 유발해 KB-9 범위(무스키마변경)를 벗어남.

## R5. 성분별 riskStatus 의 판정 대상(사용자 무관) vs 종합(사용자별)

- **결정**: 응답의 **각 성분 `riskStatus`는 그 성분의 포함 확률로만** 산출한다(사용자 무관, 음식 내재). **종합 `overallRiskStatus`만** 사용자 회피 목록 교집합을 적용한다.
- **근거**: 스펙 Assumptions·사용자 클라리피케이션과 일치. 성분 목록은 "이 음식에 무엇이 얼마나 들었나"의 사실 표시라 사용자 프로필과 독립적이어야 하고, "나에게 위험한가"의 종합 판단만 개인화된다. 성분 목록을 회피 목록으로 필터링하지 않는다(Out of Scope).
- **대안**: 성분별 riskStatus 도 회피 교집합 기준 — 회피하지 않는 성분의 위험도가 사라져 정보 손실.

## R6. 사용자 회피 목록 이음새(목) 설계(FR-008)

- **결정**: `:application:client`에 port 인터페이스 `AvoidedSubstanceProvider`(회피 성분 코드 조달)와 목 구현 `MockAvoidedSubstanceProvider`(고정 집합 반환)를 둔다. 유스케이스는 **인터페이스로만** 의존한다. 반환 타입은 `Set<AvoidanceSubstanceCode>`(avoidance enum — 애플리케이션은 컨텍스트 조합 계층이라 참조 허용)이며, 유스케이스가 `.name`으로 String 집합화해 `Food.overallRisk`에 넘긴다(food 는 String 만 본다).
- **근거**: 원칙 III("application 은 외부 client 를 port 로만 사용")과 "조달원만 목, 로직은 실제"(사용자 지시)를 동시에 만족. member·인증이 준비되면 목을 실제 프로필/인증 기반 구현으로 교체(엔드포인트 식별 흐름 포함)하고 유스케이스·도메인은 불변.
- **목 집합(초기값)**: seed 음식과 맞물려 종합 결과가 결정적이도록 고정한다. 예: `{SOY, MILK, PEANUT, SHRIMP, EGG}`. 이 집합이면 seed `된장찌개`(SOY 100·WHEAT 80·CLAM 50) ∩ = `{SOY}` → SOY=DANGER → **overall=DANGER**. 정확한 집합은 tasks 에서 seed·테스트와 함께 확정(변경 시 결정성 유지).
- **대안**: port 를 `:core:kernel`에 두기 — 회피 프로필은 member 컨텍스트 소유이고 아직 없어 kernel 에 두면 미구현 컨텍스트 개념이 커널에 누수. infra 목 어댑터 — 외부 시스템/DB 가 없어 과설계.

## R7. 미등록 음식 응답 계약(§5, 사용자 확정)

- **결정**: 미등록 메뉴명(매칭 실패)은 **현행 400(`FoodErrorCode.NOT_FOUND`) 유지**. `overallRiskStatus=UNKNOWN`은 *조회에 성공한 음식*의 위험 판정에만 쓰인다(§5 를 "음식은 존재하나 판정 불가"로 스코프). 유스케이스는 음식 미발견 시 기존대로 `FoodException(NOT_FOUND)`를 던진다.
- **근거**: 사용자가 "현행 400 유지" 선택. 상세조회는 음식이 없으면 보여줄 상세 자체가 없어 200+빈 응답이 부자연스럽고, 기존 계약·컨트롤러 테스트 변경을 최소화한다. "판정 불가 ≠ SAFE" 원칙은 종합 판정 경로(R3)에서 UNKNOWN 으로 보존.
- **대안**: 200 + `overallRiskStatus=UNKNOWN`(음식 필드 빈) — 사용자가 미선택. 계약 확장·클라이언트 특수처리 유발.

## R8. 응답 필드 추가와 계약 영향

- **결정**: `payload`에 최상위 `overallRiskStatus: String`(SAFE/CAUTION/DANGER/UNKNOWN) 추가. 기존 필드(`name·imageRef·description·spiciness·ingredients[]`)·`ingredients[].riskStatus`(이제 실제값)는 유지. Swagger 문구에서 "mock" 제거하고 정책·overall 을 문서화.
- **근거**: 추가 필드는 하위호환(기존 클라이언트 무영향). 성분별 riskStatus 는 값 의미가 mock→실제로 바뀌지만 필드 구조는 동일.
- **대안**: overall 을 성분 목록 안/별도 객체로 — 최상위 단일 필드가 소비 단순.

## 미해결 항목

없음. 스펙의 열린 결정(미등록 계약 R7, 확률 결측 R4)이 모두 확정되어 Phase 1 진행 가능.
