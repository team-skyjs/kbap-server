# Data Model — KB-836

**food_idol_video** (신규): id, food_id(FK food), platform ENUM(YOUTUBE,TIKTOK), video_url VARCHAR(500), published_month DATE(그 달 1일), hype TINYINT(1~5, CHECK), position INT(1 = 대표), status ENUM(ACTIVE,DELETED), created_at, updated_at. UNIQUE(food_id, position).

- 엔티티 `FoodIdolVideo`(BaseEntity) + enum `IdolVideoPlatform`.
- 대표 영상 = ACTIVE 행 중 position 최소. 목록 순서 = 대표 hype desc → publishedMonth desc → foodId asc.
- 시드 36행 / 29 음식(계획 시드 표). position 1 은 전부 YOUTUBE.
- 응답 모델: `IdolPicksResponse{items}`, `IdolPickResponse{FoodSummaryResponse 9키 평탄 + video}`, `IdolPickVideoResponse{url, platform}`.
