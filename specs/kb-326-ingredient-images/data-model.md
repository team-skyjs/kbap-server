# Data Model: 온보딩 재료 81종 이름·이미지 공개 조회 (KB-326)

## Ingredient (기존 엔티티 — 컬럼 1개 추가)

`com.kbap.common.domain.ingredient.model.Ingredient` — 테이블 `ingredients`. `BaseEntity` 상속(id·status·createdAt·updatedAt 공통, `@SQLRestriction("status = 'ACTIVE'")`).

| 필드 | 컬럼 | 타입 | 제약 | 비고 |
|------|------|------|------|------|
| code | code | VARCHAR(40) | NOT NULL | `IngredientCode` enum(81종) — 기존 |
| koreanName | korean_name | VARCHAR(100) | NOT NULL | ko 원문 — 기존 |
| translations | translations | JSON | NOT NULL | 9개 대상 언어 번역 — 기존 |
| **imagePath** | **image_path** | **VARCHAR(255)** | **NULL** | **신규 — S3 객체 키(예: `images/webp/egg.webp`). NULL = 이미지 미매칭** |

- 검증 규칙: 없음(운영 편집 없는 고정 카탈로그 — 값은 마이그레이션이 소유).
- 상태 전이: 없음.
- 관계: 없음(JPA 연관 금지 원칙 유지 — 이번 변경도 연관 없음).
- 헌법 V 고정 reference taxonomy 준수: `imagePath` 는 콘텐츠 데이터이므로 **DB 단일 출처** — `IngredientCode` enum 에 넣지 않는다.

## Flyway 마이그레이션 (신규 1건)

`api/src/main/resources/db/migration/V<생성시각 timestamp>__ingredient_image_path.sql`

```sql
ALTER TABLE ingredients ADD COLUMN image_path VARCHAR(255) NULL;

UPDATE ingredients SET image_path = CONCAT('images/webp/', LOWER(code), '.webp');
```

- 단일 파일에 컬럼 추가 + 시드 적재(다른 미적용 마이그레이션과 순서 독립 — out-of-order 안전).
- 81행 나열 대신 code 파생(R1) — S3 실물 파일명이 enum 코드 소문자와 1:1 이므로.
- 기존 시드 마이그레이션(`V2026.07.16.21.38.42__seed_avoidance_catalog.sql`)은 수정하지 않는다(프로덕션 적용분 — checksum 보호). `IngredientCatalogSeedSyncTest` 의 리소스 경로 결합도 영향 없음.

## API 응답 모델 (`com.kbap.api.ingredient`)

```
IngredientListResponse
└── ingredients: List<IngredientItemResponse>   // id 오름차순, 81건
    ├── code: String        // IngredientCode.name — 클라이언트 안정 식별자
    ├── name: String        // displayName(lang) — 요청 언어, 번역 부재 시 ko 폴백
    └── imageUrl: String?   // public-base-url + image_path 완성 URL, 미매칭 시 null
```
