# API Contract Delta: 언어 무관 메뉴명 한국어 항상 포함

기존 두 엔드포인트에 `koreanName` 필드를 추가한다. 경로·요청·기존 필드 불변. 모든 응답은 기존대로 `BaseResponse<T>` 봉투로 감싼다.

## 1. 음식 상세 조회 — `GET /api/v1/foods/detail`

요청 파라미터 불변(`menuName`, `lang`). `payload` 에 `koreanName: string | null` 추가.

```jsonc
// GET /api/v1/foods/detail?menuName=된장찌개&lang=en
{
  "success": true,
  "payload": {
    "name": "Doenjang Stew",     // 지역화명 (기존)
    "koreanName": "된장찌개",       // 신규 — 언어 무관 한국어 원문
    "imageRef": "doenjang.png",
    "description": "...",
    "spiciness": 3,
    "overallRiskStatus": "SAFE",
    "ingredients": [ /* 기존 */ ]
  }
}
```

```jsonc
// GET /api/v1/foods/detail?menuName=된장찌개&lang=ko  (또는 lang 미지정)
{
  "success": true,
  "payload": {
    "name": "된장찌개",
    "koreanName": null,          // 지역화명이 곧 한국어 → 중복 미노출
    "...": "..."
  }
}
```

## 2. 메뉴 목록 페이징 — `GET /api/v1/foods` (커서 페이징)

요청 파라미터 불변(`cursor`, `lang`). `payload.items[]` 각 항목에 `koreanName: string | null` 추가.

```jsonc
// GET /api/v1/foods?lang=ja
{
  "success": true,
  "payload": {
    "items": [
      {
        "foodId": 1,
        "name": "テンジャンチゲ",   // 지역화명 (기존)
        "koreanName": "된장찌개",    // 신규
        "imageRef": null,
        "spiciness": 3,
        "overallRiskStatus": "CAUTION"
      },
      {
        "foodId": 2,
        "name": "비빔밥",           // ja 번역 부재 → ko 폴백
        "koreanName": null,        // 지역화명=한국어 → null
        "...": "..."
      }
    ],
    "nextCursor": 2,
    "hasNext": true
  }
}
```

## 규칙 요약

- `koreanName`: 지역화명과 다르면 한국어 원문, 같으면 `null`.
- 상세·목록이 동일 규약을 공유한다(FR-004).
- 스캔 API(`POST /api/v1/menu-scans`)는 변경 없음(범위 밖).
