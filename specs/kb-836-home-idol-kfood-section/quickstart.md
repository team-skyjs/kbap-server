# Quickstart — KB-836

```bash
./gradlew :api:test --tests "com.kbap.api.food.IdolPickControllerTest"
curl -s 'http://localhost:8080/api/foods/idol-picks?lang=en' -H 'X-API-Version: 1.1' | jq '.payload.items[:2]'
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8080/api/foods/idol-picks?lang=en'   # 400
```

## 실측 (2026-10-21, 로컬 compose MySQL · bootRun :18080)

픽스처: READY 음식 101·102, PENDING_REVIEW 104. 매핑 101(hype 2), 102(position 1 hype 4 YOUTUBE · position 2 hype 5 TIKTOK), 104(hype 5). 시드 29종은 로컬 카탈로그에 없어 0행 삽입(이름 기준 시드 동작 확인).

| 요청 | 결과 |
|---|---|
| idol-picks 게스트, X-API-Version 1.1 | 200 · success true · payload 키 `[items]` · 순서 102, 101 (104 제외) · 102 video = position 1 YOUTUBE |
| 항목 예 | `{"foodId":102,"name":"Menu102","koreanName":"메뉴102","imageRef":"https://thumbnail.coupangcdn.com/menu-102.png","spiciness":0,"overallRiskStatus":"SAFE","publishedAt":"2026-10-15T07:54:24.491225Z","bookmarked":false,"review":{"averageRating":4.0,"count":3},"video":{"url":"https://example.com/v/2","platform":"YOUTUBE"}}` |
| idol-picks 헤더 누락 | 400 · COMMON-002 |
| idol-picks lang 누락 | 400 · COMMON-002 |
| 홈 1.3 | 200 · idolPickFoods 키 없음 |
| 상세 /api/foods/102 (1.1) | 200 · idolVideo 키 없음 |
