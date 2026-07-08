# Contracts: 도메인 Port (kernel / scan / food)

application 은 아래 port 인터페이스로만 외부와 접한다(헌법 III). 구현은 `:infra:*`, 조립은 `:app:api` `runtimeOnly`.

## KoreanMenuNameNormalizer (`:core:kernel`) — P1

순수 함수. 구현 자체가 계약(외부 의존 없음).

```
fun matchKey(raw: String): String
```

- NFC 정규화 후 `[가-힣]` 만 남긴 문자열 반환. 한글이 없으면 `""`.
- 계약 예: `"김치찌개 kimchi jjigae" -> "김치찌개"`, `"· 된장찌개 " -> "된장찌개"`, `"돼지 국밥" -> "돼지국밥"`, `"원산지 중국" -> "원산지중국"`, `"6,500" -> ""`.
- 순수·결정적(같은 입력 → 같은 출력, 부수효과 없음).

## FoodRepository (`:core:food`, 확장) — P1

```
fun findByKoreanMatchKey(key: String): List<Food>
```

- `korean_match_key` 컬럼 exact 매치, 활성(소프트삭제 제외) food 만.
- 0개 → miss(호출부가 PENDING+enqueue 결정). 1개 → MATCHED. 2개↑ → 결정적으로 최소 id 매칭 + 경고 로깅(research D2 열린 이슈).
- 빈 키(`""`) 입력은 조회하지 않는다(호출부가 정규화 빈 키를 NOT_FOOD pre-filter 로 먼저 걸러 여기 도달 안 함).

## ScannedNameInterpreter (`:core:kernel`) — P1

```
fun interpret(rawNames: List<String>): List<InterpretedName>
```

- 입력 순서와 1:1 대응하는 결과 리스트 반환(길이 동일).
- `InterpretedName = StandardName(korean) | NotFood`.
- **입력**: 정규화 빈 키가 아닌(=LLM 대상) 전 항목의 원문. 정규화 게이트를 통과한 항목만 넘어온다.
- **구현 규약**: 스캔당 1콜(배열 입출력). 실패·타임아웃·부분 파싱 실패는 예외로 던진다 → 호출부(유스케이스)가 **정규화 exact 매치 폴백**으로 전환(FR-006). 빈 입력 리스트 → 빈 결과(호출 안 함).
- Upstage 미구성 시 이 빈이 없을 수 있음 → application 은 **Optional/nullable 주입**으로 받아 부재 시 폴백 경로(P2, web 부팅 안전).

## PendingMenuRepository (`:core:scan`) — P1

```
fun enqueue(standardName: String)
```

- 표준명 dedup 등록. 이미 있으면 no-op(unique 제약). blank 거절.
- 폴백 경로에선 표준명 대신 **원문**을 넣을 수 있음(정제 실패분) — 동일 dedup 규칙.
