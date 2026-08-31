-- KB-301: 기피성분을 음식의 재료(ingredients) 의미로 개명한다.
-- 회원 프로필의 기피 설정(member.avoidance_substance_codes)은 관계 의미가 달라 그대로 둔다.

ALTER TABLE food RENAME COLUMN avoidance_substances TO ingredients;

RENAME TABLE avoidance_substance TO ingredients;
