# Contracts: 도메인 Port

application은 아래 port로만 외부와 접한다(헌법 III). 구현은 `:infra:*`, 조립은 `:app:api` `runtimeOnly`.

## KoreanMenuNameNormalizer (`:core:kernel`)

```
fun matchKey(raw: String): String
```

- NFC 정규화 후 `[가-힣]`만 남긴다. 한글이 없으면 `""`.
- 순수·결정적(부수효과 없음).
- 계약 예: `"김치찌개 kimchi jjigae" → "김치찌개"`, `"· 된장찌개 " → "된장찌개"`, `"돼지 국밥" → "돼지국밥"`, `"원산지: 중국" → "원산지중국"`, `"6,500" → ""`.

## ScannedNameInterpreter (`:core:kernel`)

```
fun interpret(texts: List<String>): List<InterpretedName>
```

- **입력**: 정규화 키가 비지 않은(=메뉴 후보) 항목의 원문. 스캔당 **동기 1콜**.
- **출력**: 입력과 **같은 길이·같은 순서**. `InterpretedName = StandardName(korean) | NotFood`.
- **구현 규약**: 실패·타임아웃·파싱 실패는 예외로 던진다. 빈 입력 → 빈 결과(호출 안 함).
- **호출부 규약**: 결과 개수가 요청 개수와 다르면 **신뢰하지 않고** 폴백으로 전환한다(항목 정렬이 깨졌다는 신호).
- Upstage 미구성 시 빈이 없을 수 있음 → application은 **nullable 주입**으로 받아 부재 시 폴백(web 부팅 안전).

## FoodRepository (`:core:food`)

```
fun findById(id: Long): Food?                                  // 상세(사용자) — READY 만
fun findMenuPage(cursor: Long?, size: Int): List<Food>         // 목록(사용자) — READY 만
fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> // 스캔 매칭 — 완성 상태 무관
fun createIncomplete(koreanName: String): Food                 // 스캔 miss — get-or-create
```

- `findByKoreanMatchKeys`: 스캔당 **1쿼리**(성분 fetch join). 활성(소프트삭제 제외) 음식만. **미완성 음식도 포함**(재등록 방지). 키가 비면 조회하지 않고 빈 맵. 같은 키에 복수 음식이면 **최소 id + 경고 로깅**.
- `createIncomplete`: `content_status=INCOMPLETE`로 생성. 같은 `korean_name`이 이미 있으면 그 음식을 반환(경합 시 재조회). blank 거절.
- `findById`/`findMenuPage`: **미완성 음식은 반환하지 않는다**(serving gate).

## FoodRiskEvaluator (`:application:client`)

```
fun risksOf(foods: List<Food>): Map<Long, RiskLevel>
```

- 사용자 회피 코드(`AvoidedSubstanceProvider`) ∩ 카탈로그 활성 코드(`AvoidanceSubstanceRepository`)로 `Food.overallRisk()`를 호출.
- **미완성 음식은 도메인이 `UNKNOWN`을 강제**하므로 별도 분기 불요.
- Browse(목록)와 Scan이 공유한다. 빈 입력 → 빈 맵.
