-- R-97(2026-10-20): 서울 큐레이션 세 번째 모음을 식당(restaurants)에서 부산 명소(busan)로 바꾼다. KB-825.
-- 게시본이 없는 restaurants 행만 키를 바꾸고 초안을 빈 문서로 되돌린다(식당 초안이 부산 초안으로 남지 않게, version +1).
-- 게시본(현재·보존)이 있으면 UPDATE 가 0행이고 아래 ADD CHECK 가 실패해 배포가 멈춘다 — 게시된 식당 모음을 조용히 바꾸지 않기 위한 의도된 실패.
-- 업로드 자산(curation_asset)은 collection_id 로 묶여 그대로 busan 행 소속이 된다(미참조 정리는 별도 작업).
ALTER TABLE curation_collection DROP CHECK ck_curation_collection_key;

UPDATE curation_collection
SET collection_key = 'busan',
    draft_document = JSON_OBJECT('title', JSON_OBJECT(), 'description', JSON_OBJECT(), 'cover', NULL, 'items', JSON_ARRAY()),
    version = version + 1
WHERE collection_key = 'restaurants'
  AND is_published = 0
  AND published_document IS NULL;

ALTER TABLE curation_collection
    ADD CONSTRAINT ck_curation_collection_key CHECK (collection_key IN ('sights', 'stations', 'busan'));
