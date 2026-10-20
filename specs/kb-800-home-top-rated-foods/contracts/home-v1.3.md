# Contract — GET /api/home (KB-800)

정본: `_workspace/kb800-home-top-rated-foods/01_cto_plan.md` "API 계약". 그대로 옮긴다.

| ID | 메서드 경로 | X-API-Version | 응답 | 에러 |
|----|------------|---------------|------|------|
| C-01 | `GET /api/home?lang=en` | `1.3` | 기존 5키 + `topRatedFoods`(배열, null 아님, 0~20) | lang 누락 400 COMMON-002 · 토큰 위조/만료 401 AUTH-003/AUTH-004 · 미등록 버전 400 |
| C-02 | `GET /api/home?lang=en` | `1.2` 이하 | 기존 5키만, `topRatedFoods` 키 없음 | 기존 |

`topRatedFoods[]` 항목:

| 필드 | 타입 | null |
|------|------|------|
| foodId·name·koreanName·imageRef·spiciness·overallRiskStatus·publishedAt·bookmarked·review{averageRating,count} | FoodSummaryResponse 동일 | 동일 |
| taste | number(1자리) | 아니오 |
| servingSpeed | number(1자리) | 속도 입력(>0) 0건이면 null |
| staffKindness | number(1자리) | 친절도 입력(>0) 0건이면 null |
| overallRating | number(1자리) | 아니오 |

정렬: review.count desc → overallRating(반올림 전) desc → foodId desc. 최대 20.
