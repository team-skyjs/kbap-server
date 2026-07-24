# Data Model: Flyway 마이그레이션 스쿼시 (KB-163)

스키마 변경은 **없다** — 이 기능은 기존 22개 마이그레이션이 만든 최종 상태를 파일 구조만 재편한다.
권위 DDL 은 구현 시 R1(연구 문서) 절차로 덤프한 `init_schema` 파일 자체다. 아래는 최종 상태의 개요.

## 최종 테이블 7종

| 테이블 | 소유 도메인 | 개요 | 주요 관계(FK) |
|--------|------------|------|---------------|
| `food` | food | 음식 — 한국어 원문 + 번역 JSON(명칭·설명), spiciness, korean_match_key, content_status, scan 수 등 | — |
| `avoidance_substance` | avoidance | 기피물질 카탈로그 81종 — code(unique) + 한국어 원문 + 9개 언어 명칭 | — |
| `food_avoidance_substance` | food/avoidance | 음식별 기피물질 매핑(비율 포함) | food_id → food, substance → avoidance_substance |
| `member` | member | 회원 — 소셜 식별자, 프로필 JSON(countryCode·언어 등), 온보딩 완료 여부, 랭킹 카운트, member_status | — |
| `scan_history` | scan | 스캔 이력 — image_path·menu_name·price, food_id nullable | member_id → member, food_id → food |
| `bookmark` | bookmark | 음식 북마크 | member_id → member, food_id → food |
| `uploaded_image` | image | presigned 업로드 완료 기록(object key·purpose) | member_id → member |

- 전 테이블 공통: `BaseEntity` 컬럼(id IDENTITY, status ACTIVE/DELETED, created_at, updated_at). 소프트삭제 구조라 FK 에 ON DELETE 없음.
- 폐기된 테이블(menu_scan, scanned_menu_item, ingredient 계열, 번역 행 테이블 5종, avoidance_substance_category, member_social_identities)은 init 에 포함하지 않는다.

## 시드 데이터셋

| 데이터셋 | 위치 | 내용 | 적용 환경 |
|----------|------|------|----------|
| 마스터 — 기피물질 카탈로그 | `db/migration` | `avoidance_substance` 81행(다국어 명칭 포함). `AvoidanceSubstanceCode` enum 과 코드 집합·label 정합(시드-동기화 테스트가 강제) | 전 환경 |
| 데모 — 음식 데이터 | `db/seed` | `food` 10행(최종 상태: 번역 JSON·설명·spiciness 반영) + `food_avoidance_substance` 매핑 | local·dev 만 |

## 마이그레이션 이력 전환 (기존 DB)

- 신규(빈) DB: 이력 없음 → init → 마스터 (→ 프로필에 따라 데모) 순 적용.
- 기존 DB(홈서버 dev): 스키마·데이터 무접촉, `flyway_schema_history` 만 재기준선(baseline-version = 새 파일 최고 버전). 상세 절차는 quickstart.md.
