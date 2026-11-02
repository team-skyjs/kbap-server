-- KB-930: 큐레이션 사진 표시용 변형(최대 폭 1080, WebP) 오브젝트 키. 원본(final_key)은 그대로 두고, 변형이 있으면 앱 응답이 이 키를 쓴다(없으면 원본 폴백).
-- 업로드 complete 때 변형을 만들어 채우고, 기존 자산은 어드민 1회성 백필로 채운다(NULL 이 정상 상태 — 변형 없음).
-- 형제 키 컬럼(staging_key·final_key)과 같은 utf8mb4_0900_bin 콜레이션.
-- 가산만: NULL 허용 컬럼 추가. 기존 값은 바꾸지 않는다. 구 코드는 이 컬럼을 모른다. 위치 지정 없이 INSTANT.
ALTER TABLE curation_asset
    ADD COLUMN display_key VARCHAR(512) COLLATE utf8mb4_0900_bin NULL,
    ALGORITHM = INSTANT;
