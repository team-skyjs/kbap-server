# 계약: 콘텐츠 적재 API (kbap-langchain → kbap)

지식 위키 `langchain-food-ingest-contract.md`(2026-08-08 확정)의 **KB-302 개정판**. 아래 두 가지가 원안과 다르며, 랭체인 쪽 변경은 **①의 echo 한 줄뿐**이다.

1. **`foodId` 추가·매칭 키 변경** — 요청 메시지로 받은 `foodId` 를 응답에 그대로 실어 보낸다. 서버는 `displayName` 이 아니라 `foodId` 로 대상을 찾는다.
2. **"READY 스킵" 규칙 폐기** — 이미 서비스 중인 음식도 덮어쓴다(재수집 지원).

## 엔드포인트

`POST /api/v1/admin/foods/contents` — ADMIN JWT(머신 인증). 음식 단건.

## 요청 본문

```jsonc
// passed: true — 콘텐츠 완성
{
  "foodId": 1234,                      // 필수 — 큐 메시지에서 받은 값 그대로
  "displayName": "들깨 칼국수",          // 선택 — 로그·디버깅용, 매칭에 쓰지 않는다
  "passed": true,
  "description": "들깨를 곱게 갈아 넣어 고소한 칼국수",
  "spiciness": 2,
  "nameTranslations": { "en": "...", "ja": "...", "zh-Hans": "...", "zh-Hant": "...",
                        "vi": "...", "id": "...", "th": "...", "ru": "...", "es": "..." },
  "descriptionTranslations": { /* 동일 9키 */ },
  "ingredients": [ { "code": "PERILLA", "inclusion_percent": 100 } ]
}

// passed: false — 판정 실패
{
  "foodId": 1234,
  "passed": false,
  "failureKind": "JUDGE_REJECTED",
  "reason": "번역 점수 78점으로 임계값 미달이며 태국어 번역이 원문과 다른 음식을 가리킴"
}
```

| 필드 | passed=true | passed=false |
|---|---|---|
| `foodId` | 필수 | 필수 |
| `displayName` | 선택(무시) | 선택(무시) |
| `description` | 필수 1~255자 | 무시 |
| `spiciness` | 필수 0~10 | 무시 |
| `nameTranslations`·`descriptionTranslations` | 필수, 9키 전수·빈 값 불가 | 무시 |
| `ingredients` | 필수, 빈 배열 허용 | 무시 |
| `failureKind` | 무시 | 필수 — `NOT_FOOD`·`JUDGE_REJECTED`·`INGREDIENT_GUARD` |
| `reason` | 무시 | 필수, 표시용 자유 문구(10줄·1000자 초과분은 서버가 절단) |

9개 언어 고정: `zh-Hans` `en` `ja` `zh-Hant` `vi` `id` `th` `ru` `es` (`ko` 는 원문이라 제외 — 헌법 V).

## 서버가 소유하는 것 (요청에 넣지 않는다)

- **`content_status`** — 아래 규칙으로 서버가 결정한다.
- **`korean_name`·`display_name`** — 재수집은 이름을 바꾸지 않는다. 이름 수정은 관리자 화면의 권한이다.

## 상태 결정 규칙 (원안 대비 전면 개정)

| 결과 | 대상 현재 상태 | 결과 상태 | 사진 |
|---|---|---|---|
| passed | `READY` | `READY` 유지 | 불변 |
| passed | 그 외 + `imageRef` 있음 | `PENDING_REVIEW` | 불변(재활용) |
| passed | 그 외 + `imageRef` 없음 | `PENDING_IMAGE` | 이후 이미지 파이프라인이 생성 |
| failed | `READY` | `READY` 유지 (콘텐츠 보존) | 불변 |
| failed | 그 외 | `FAILED` | 불변 |

성공 적재는 `content_failure_kind`·`content_review_rejection_reason` 을 초기화한다.

## 응답

| 상황 | HTTP | code | 비고 |
|---|---|---|---|
| 성공(신규·중복 무관) | 200 | — | `BaseResponse<Unit>`, payload 없음 |
| `foodId` 로 음식을 찾을 수 없음(삭제 포함) | 400 | `FOOD-001` | 람다는 DLQ 로 보내 사람이 판단 |
| 계약 위반(번역 누락·9키 미충족·`ingredients` 누락·`spiciness` 범위 밖·`failureKind` 3값 밖) | 400 | `COMMON-002` | 저장 전에 거절 |

## 멱등성

같은 결과가 두 번 도착해도 갱신 후 200 이다. 재시도가 오류로 보이지 않아야 한다.

## 전제 — FAILED 에 인프라 장애는 섞이지 않는다

LLM 다운·타임아웃 같은 런타임 에러는 `passed=false` 로 오지 않는다(SQS 재시도 → DLQ 경로라 API 호출 자체가 없다). 따라서 `FAILED` 는 항상 "콘텐츠를 보고 내린 판정 실패"이며, **서버에 FAILED 자동 재시도 로직을 두지 않는다.**

## `ingredients` 의미 (안전 직결)

- `[]` = 조사 완료·카탈로그 해당 성분 없음 → 위험도 SAFE
- `null`(미조사) = 위험도 UNKNOWN(fail-closed)

랭체인은 매핑 실패가 없다는 전제라 항상 non-null 을 보낸다. 이 전제가 바뀌면 계약부터 고쳐야 한다.
