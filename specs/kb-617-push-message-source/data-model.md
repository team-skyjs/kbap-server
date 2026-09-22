# Data Model: 메시지 키 스키마

DB·엔티티 변경 없음. "데이터"는 classpath 메시지 파일이다.

## 파일

| 항목 | 값 |
|------|----|
| 위치 | `common/src/main/resources/messages/` |
| basename | `messages/push` |
| 파일명 | `push_<locale>.properties` — `ko`·`zh_Hans`·`en`·`ja`·`zh_Hant`·`vi`·`id`·`th`·`ru`·`es` |
| 기본 파일 | **없음**(`push.properties` 금지 — 있으면 누락 언어가 조용히 폴백된다) |
| 인코딩 | UTF-8, `\uXXXX` 이스케이프 쓰지 않음 |

`LanguageCode.code`(BCP 47) → `Locale.forLanguageTag` → 파일 접미(`-` → `_`).

## 키 (언어당 39개, 전 언어 동일 집합·동일 순서)

| 키 | 후보 수 |
|----|--------|
| `push.helpful.<n>.title` / `.body` | 3 |
| `push.scan_suggestion.<n>.title` / `.body` | 3 |
| `push.scan_suggestion.lunch.<n>.title` / `.body` | 3 |
| `push.scan_suggestion.dinner.<n>.title` / `.body` | 3 |
| `push.review_reminder.<n>.title` / `.body` | 3 |
| `push.news.<n>.title` / `.body` | 1 — 전 언어 `{title}` / `{body}` |
| `push.meal_time.<n>.title` / `.body` | 3 |
| `push.opt-out` | 1 (기존 문구 고정) |

`<n>` 은 1부터 연속. 후보를 추가하려면 **10개 언어 모두**에 같은 번호로 title·body 를 넣는다 — 한 언어만 늘리면 누락 검증 테스트가 실패한다.

## 규칙

- 문구 출처: Codex 제안(2026-09-22, 토스·Braze·Airship·OneSignal·Apple 리서치) → 한국어 윤문 검수(humanize-korean, 등급 A) → Codex 10개 언어 번역. 이모지 후보당 1개, 위치 언어 간 동일.
- 자리표시자는 `{이름}`(`\{(\w+)}`) — 현재 사용: `{food}`, `{title}`, `{body}`. 렌더러가 치환하며 없는 인자는 빈 문자열.
- properties 문법 주의: 값 앞 공백은 잘린다(현재 문구에 선행 공백 없음), 줄 끝 `\` 는 줄 잇기, 키-값 구분자는 첫 `=`. 값 안의 `:`·`=`·`#`·`!` 는 이스케이프 불필요(키가 아닌 값 위치).
- "(광고) " 접두, 길이 상한(200/1000), 수신거부 안내 앞 줄바꿈은 **파일이 아니라 렌더러 상수**다(번역 대상 아님).

## 조회 규칙

1. `slot != null` 이고 `push.<type>.<slot>.1.title` 이 있으면 슬롯 접두, 아니면 유형 접두.
2. 접두의 후보 수 = `<n>.title` 이 연속으로 존재하는 최대 n. 선택기가 `[0, count)` 인덱스를 고른다(기본 무작위).
3. 고른 번호의 title·body 를 엄격 조회 — 누락 시 예외.
4. 광고성 유형은 `push.opt-out` 을 엄격 조회.

번호가 건너뛰면(1·3만 있음) 3번은 보이지 않는다. 언어별 후보 수 차이·반쪽 정의(title만)는 누락 검증 테스트가 막는다.
