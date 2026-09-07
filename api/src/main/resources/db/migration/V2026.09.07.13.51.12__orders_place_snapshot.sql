-- KB-451: orders 에 주문 전용 식당 스냅샷 5컬럼 추가(nullable ADD COLUMN only, 스키마만 — 백필 없음).
-- 컬럼의 본질은 "주문 좌표를 기반으로 자동 추정한 식당 스냅샷(공급자 거리순 첫 결과, 사용자 확인값 아님)".
-- 리뷰 embed 7컬럼과 공유하지 않는다 — 불변식이 다르다(주문은 GOOGLE_PLACE 만·place_external_id 필수·공급자 좌표 저장 금지·언어 1종).
ALTER TABLE orders
  ADD COLUMN place_source      VARCHAR(20)  NULL COMMENT '추정 식당 공급자 — GOOGLE_PLACE (리뷰 PlaceSource와 같은 문자열)',
  ADD COLUMN place_external_id VARCHAR(255) NULL COMMENT '공급자 외부 식별자(Google place id). 내부 FK 아님. 스냅샷 있으면 NOT NULL(앱 불변식)',
  ADD COLUMN place_name        VARCHAR(100) NULL COMMENT '주문 좌표로 자동 추정한 식당명 스냅샷',
  ADD COLUMN place_address     VARCHAR(200) NULL COMMENT '자동 추정한 식당 주소(공급자 포맷). 도로명은 기존 road_address',
  ADD COLUMN place_language    VARCHAR(7)   NULL COMMENT '해석 요청 언어(LanguageCode.code, 예 en·zh-Hans)';
