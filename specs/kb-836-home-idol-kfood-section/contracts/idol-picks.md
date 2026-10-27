# Contract — KB-836 (정본: _workspace/kb836-home-idol-kfood-section/01_cto_plan.md "API 계약 C-1")

| ID | 메서드 경로 | X-API-Version | 요청 | 응답 payload | 에러 |
|----|------------|---------------|------|--------------|------|
| C-1 | `GET /api/foods/idol-picks?lang={lang}` | 필수, 값 무관 | `lang` 필수, `Authorization` 선택 | `{ items: [ {foodId,name,koreanName,imageRef,spiciness,overallRiskStatus,publishedAt,bookmarked,review{averageRating,count}, video{url, platform:"YOUTUBE"\|"TIKTOK"}} ] }` D7 순서, 없으면 `[]` | COMMON-002·400 lang/헤더 누락 |

봉투: `{ success, payload, message, code }`. 금지 키(어떤 깊이에도): title, thumbnail, idol, artist, group, reason, hype, position, publishedMonth. 홈·상세 응답 무변.
