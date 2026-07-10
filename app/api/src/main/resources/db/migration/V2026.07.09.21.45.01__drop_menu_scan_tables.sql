-- KB-90: 메뉴 스캔 내역을 기록하지 않기로 결정 — 스캔은 요청당 정제·매칭·위험도 판정만 하고 응답한다.
-- 바운딩 박스도 서버가 쓰지 않으므로 요청 스키마에서 제거됐다.
-- 자식 테이블(FK) 먼저 제거한다.
DROP TABLE IF EXISTS scanned_menu_item;
DROP TABLE IF EXISTS menu_scan;
