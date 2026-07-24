# Contract: `lang` 파라미터 (전 API 공통)

## 대상 엔드포인트

| 엔드포인트 | `lang` 변경 |
|---|---|
| `GET /api/v1/home` | **신규 추가**(필수) |
| `GET /api/v1/foods` | 선택 → **필수** |
| `GET /api/v1/foods/search` | 선택 → **필수** |
| `GET /api/v1/foods/{foodId}` | 선택 → **필수** |
| `GET /api/v1/bookmarks` | 선택 → **필수** |

대상 밖: `POST /api/v1/scans` 등 스캔 API — `lang` 을 받지 않고 회원 프로필 언어를 계속 쓴다.

## Request

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| query | `lang` | string (비어 있지 않음) | **예** | 표시 언어 코드. 지원: `ko`, `zh-Hans`, `en`, `ja`, `zh-Hant`, `vi`, `id`, `th`, `ru`, `es` |

기본값 없음. 인증 헤더 유무는 표시 언어에 관여하지 않는다.

## 결정 규칙

1. `lang` 이 **없거나 비어 있으면**(공백만인 경우 포함) → **400** `COMMON-002`
2. `lang` 값이 지원 코드와 **정확히 일치**하면 → **그 언어**
3. 그 외 → **`en`**

매칭은 정확 일치다. 대소문자 보정·지역 태그 제거·**앞뒤 공백 제거를 하지 않는다**.

**"비어 있음"과 "잘못됨"은 다르다**: 값을 안 채우면 400, 채웠는데 지원 목록에 없으면 200 + 영어.

## Responses

| 상태 | code | 조건 |
|---|---|---|
| 200 | — | 비어 있지 않은 `lang` 으로 조회 성공. 값이 무엇이든 이 경로 |
| 400 | `COMMON-002` | `lang` 누락 또는 빈 값·공백 |
| 401 | — | 인증 헤더가 있으나 토큰이 위조·만료 (해당 API 만, 기존과 동일) |

**`COMMON-001`(`UNSUPPORTED_LANGUAGE`) 는 삭제된다.** 언어 코드 값을 사유로 하는 400 은 더 이상 존재하지 않는다.

## 예시

```
GET /api/v1/foods?lang=ja                     # → 일본어
GET /api/v1/foods?lang=ko                      # → 한국어
GET /api/v1/foods?lang=fr                      # 미지원 → 200, 영어   (전: 400 COMMON-001)
GET /api/v1/foods?lang=JA                      # 대소문자 → 200, 영어  (전: 400 COMMON-001)
GET /api/v1/foods?lang=ko-KR                   # 지역 변형 → 200, 영어 (전: 400 COMMON-001)
GET /api/v1/foods?lang=%20ko%20                # 앞뒤 공백 → 200, 영어 (전: 200 한국어)
GET /api/v1/foods                              # 누락 → 400 COMMON-002 (전: 200 한국어)
GET /api/v1/foods?lang=                        # 빈 값 → 400 COMMON-002 (전: 200 한국어)

GET /api/v1/home?lang=ko  (Bearer, 프로필 ja)   # → 한국어 (프로필 무시)
GET /api/v1/home          (Bearer, 프로필 ja)   # 누락 → 400        (전: 200 일본어)
```

## ⚠️ 호환성

**5개 엔드포인트가 동시에 깨진다.** 현재 배포된 클라이언트는 홈을 `lang` 없이 호출하고, 음식·북마크도 생략하면 200(`ko`)을 받아왔다. 변경 후 그 호출은 전부 400 이다. **클라이언트 배포 선행 또는 강제 업데이트가 릴리스 조건**이다.

반대로 미지원 코드가 400 → 200(영어)로 완화되는 축은 클라이언트를 깨지 않는다.
