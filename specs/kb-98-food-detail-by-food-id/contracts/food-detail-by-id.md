# Contract: 음식 상세 조회 (foodId)

## Endpoint

```
GET /api/v1/foods/{foodId}?lang={langCode}
```

`{foodId}` 를 제외한 기존 `GET /api/v1/foods/detail?menuName=` 은 **제거**된다.

### Path parameters
| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| foodId | Long(숫자) | Y | 조회할 음식의 안정적 식별자(목록/검색이 내려준 값). 비숫자면 400. |

### Query parameters
| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| lang | String | N | 응답 언어 코드. 미지정/빈/공백 → ko. 지원 목록 밖 코드 → 400(지원 목록 안내). 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. |

## Responses

모든 응답은 `BaseResponse<T>` 봉투(`success`·`payload`·`message`).

### 200 OK — 조회 성공 (payload 스키마는 기존 상세와 동일, SC-003)
```json
{
  "success": true,
  "payload": {
    "name": "Doenjang Stew",
    "imageRef": "doenjang.png",
    "description": "A hearty Korean soybean paste stew.",
    "spiciness": 3,
    "overallRiskStatus": "DANGER",
    "ingredients": [
      { "name": "Soybean", "iconRef": null, "inclusionPercent": 100, "riskStatus": "DANGER" },
      { "name": "Wheat",   "iconRef": null, "inclusionPercent": 80,  "riskStatus": "DANGER" },
      { "name": "Clam",    "iconRef": null, "inclusionPercent": 50,  "riskStatus": "CAUTION" }
    ]
  },
  "message": null
}
```

### 400 Bad Request — 실패
`{ "success": false, "payload": null, "message": "<사유>" }`

| 상황 | message |
|------|---------|
| 존재하지 않는 foodId | 해당 음식 정보 없음 |
| 소프트삭제된 음식 foodId | 해당 음식 정보 없음 |
| 비숫자/형식오류 foodId | (형식 오류 안내) |
| 지원 목록 밖 lang 코드 | (지원 언어 목록 안내) |

## 계약 테스트 대상
- 200: 시드된 foodId 로 조회 → payload 스키마·필드 값(요청 언어·성분·위험도) 검증.
- 400: 미존재 foodId / 소프트삭제 foodId / 비숫자 foodId / 미지원 lang.
