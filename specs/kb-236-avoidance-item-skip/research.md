# Research: 기피성분 조사 후보 밖 성분 항목 단위 스킵

**Feature**: kb-236-avoidance-item-skip | **Date**: 2026-07-24

Technical Context 에 NEEDS CLARIFICATION 없음 — 변경 지점이 단일 메서드로 특정되고 Jira 이슈가 정책 범위를 명시했다. 아래는 설계 결정 기록이다.

## Decision 1: 후보 밖 항목은 "존재하지 않는 항목" 취급 (스킵이 검증보다 먼저)

- **Decision**: `parseValidOrNull` 항목 루프에서 `item.code !in candidateCodes` 이면 percent 범위·중복 검사 없이 즉시 건너뛴다.
- **Rationale**: 후보 밖 코드는 카탈로그에 없어 어차피 저장할 수 없는 데이터다. 그 항목의 부수 속성(percent 150, OIL 중복 등)까지 응답 무효 사유로 삼으면 "항목 단위 스킵" 정책이 무의미해진다 — dev 실측(gpt-5-nano 의 OIL·KOREAN_SOYSAUCE·CABBAGE·PEPPER 혼입)에서 구제하려는 응답이 여전히 죽는 경로가 남는다.
- **Alternatives considered**:
  - *후보 밖 항목도 percent 범위는 검사*: 응답 신뢰성 신호를 더 보수적으로 본다는 장점이 있으나, 버릴 데이터의 품질을 응답 생사에 연결하는 비일관성이 생기고 이슈의 "항목 단위 스킵" 문구와 어긋난다. 기각.
  - *후보 밖 코드 개수 상한(예: 절반 이상이면 응답 무효)*: 휴리스틱 추가 비용 대비 근거 데이터 없음. 필요해지면 후속 이슈로. 기각(YAGNI).

## Decision 2: 후보 안 항목의 무효 규칙은 응답 단위 무효 유지

- **Decision**: 후보 안 코드의 percent `!in 0..100`, 후보 안 코드 중복, 맵기 `!in SPICINESS_RANGE`, JSON 파싱 실패는 기존대로 응답 전체를 무효(null) 처리한다.
- **Rationale**: 이 신호들은 "모델이 출력 계약 자체를 어겼다"는 응답 신뢰성 붕괴 신호다 — 후보 밖 코드(지식 부족·카탈로그 미인지)와 성격이 다르다. Jira DoD 가 명시적으로 유지를 요구한다. 안전 직결 데이터(헌법 V)이므로 완화 범위 최소화.
- **Alternatives considered**: *percent 이탈도 항목 스킵으로 완화* — 실측 근거 없고 안전 데이터 품질 저하 위험. 기각.

## Decision 3: 종합(aggregate) 로직 무변경 — 기존 테스트 기대값 갱신은 정책의 정의된 결과

- **Decision**: `aggregateSubstances`(유효 응답에 없는 코드 = 0 으로 평균) 와 spiciness 평균은 손대지 않는다. 다중 모델 기존 테스트 "한 모델이 후보 밖 코드를 섞어 응답" 의 기대값을 새 정책 기준(PORK 57 = (80+90+0)/3, spiciness 4 = (3+4+5)/3)으로 갱신한다.
- **Rationale**: 후보 밖 코드를 섞은 응답이 무효→유효로 승격되면 그 응답은 "PORK 미포함(0)" 의견으로 평균에 참여하는 것이 종합 규칙의 일관된 적용이다. 무효 응답을 분모에서 빼는 기존 동작과 유효 응답의 0 기여는 서로 다른 개념이며, 후자를 특례로 빼면 "유효 응답 = 종합 참여" 불변이 깨진다.
- **Alternatives considered**: *스킵 항목이 있던 응답은 성분 종합에서 제외하고 맵기만 참여* — 응답을 반쪽 유효로 만드는 복잡도 대비 이득 불명확, 이슈 요구("정상 성분과 맵기는 그대로 종합")와도 불일치. 기각.

## Decision 4: 프롬프트·seam·저장 경로 무변경

- **Decision**: 프롬프트(후보 밖 코드 금지 지시)는 그대로 둔다. 방어는 파싱 검증 계층이 담당한다. seam `FoodAvoidanceAssessmentClient` 시그니처, `FoodAvoidanceAssessmentResult`, 배치 프로세서·저장 경로 모두 불변.
- **Rationale**: 프롬프트 지시는 이미 최선("절대 금지" 명시)이고, 모델이 지시를 어기는 것이 이 이슈의 전제다. 변경 반경을 검증 루프 하나로 고정해 회귀 위험 최소화.
- **Alternatives considered**: *프롬프트 강화 병행* — 모델 비결정성상 재발 보장 못 함, 검증 완화 없이는 문제 미해결. 독립 개선으로 분리 가능하나 이번 범위 아님.
