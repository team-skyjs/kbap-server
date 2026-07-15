# Data Model: 메뉴판 사진 스캔 (KB-138)

## 엔티티

### UploadedImage (신규 — `:domain:image`, 테이블 `uploaded_image`)

완료 검증을 통과한 업로드 이미지 기록. 스캔 요청의 유효성(존재·소유) 판단 근거이며, 향후 다른 이미지 용도(리뷰 사진 등)도 같은 엔티티를 쓴다.

| 필드 | 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|------|
| (BaseEntity) | id·status·created_at·updated_at | — | — | 공통(소프트삭제 `@SQLRestriction` 포함) |
| memberId | member_id | BIGINT | NOT NULL, FK→member(id), INDEX | 업로드(소유) 회원 |
| path | object_path | VARCHAR(512) | NOT NULL, UNIQUE | 오브젝트 경로 — 도메인 없는 path 만(FR-012). 유일 창구 키 |
| contentType | content_type | VARCHAR(100) | NOT NULL | HeadObject 로 확인한 실제 Content-Type |
| sizeBytes | size_bytes | BIGINT | NOT NULL | HeadObject 로 확인한 실제 크기 |

- 도메인 메서드: `isOwnedBy(memberId)`. 이미지 형식 판정(`image/*`)은 서비스 검증 단계 책임.
- 검증 규칙(FR-001~003): 실제 Content-Type 이 `image/` 접두가 아니면 거절, 신고값(contentType·size)과 실제값 불일치 시 거절 — 두 경우 모두 오브젝트 삭제 후 기록하지 않는다.
- 멱등(Edge Case): 같은 path 가 이미 기록돼 있으면 재검증 없이 기존 기록으로 성공 응답.

### ScanHistory (확장 — `:domain:scan`, 테이블 `scan_history`)

스캔 추출 항목 1건 = 1 row. 같은 스캔의 row 들은 image_path 를 공유한다. **가격이 존재하는 유일한 저장소**(FR-014).

| 필드 | 컬럼 | 타입 | 변경 | 설명 |
|------|------|------|------|------|
| (BaseEntity) | id·status·created_at·updated_at | — | 유지 | |
| memberId | member_id | BIGINT NOT NULL | 유지 | |
| foodId | food_id | BIGINT **NULL** | **NOT NULL → NULL 완화** | 미매칭 항목도 기록. FK 유지(MySQL FK 는 NULL 미검사) |
| imagePath | image_path | VARCHAR(512) NOT NULL | **추가** | 스캔한 이미지의 오브젝트 경로 |
| menuName | menu_name | VARCHAR(100) NOT NULL | **추가** | 사진 표기 그대로의 메뉴명 |
| koreanName | korean_name | VARCHAR(100) NOT NULL | **추가** | 표준 한국어 메뉴명 |
| price | price | INT NULL | **추가** | KRW 정수, 미표기 시 NULL |

- 기존 인덱스 `idx_scan_history_recent(member_id, created_at)` 유지 — 홈 "최근 스캔" 조회 그대로.
- `findRecentReadyFoodIds` 는 `food_id IS NOT NULL` 조건 추가(R4).
- 기존 데이터 이행: 기존 row 는 신규 컬럼 값이 없으므로 마이그레이션에서 컬럼 추가 시 기본값 전략 필요 — 로컬 데이터뿐이므로 `image_path`·`menu_name`·`korean_name` 은 `NOT NULL DEFAULT ''` 로 추가 후 DEFAULT 제거(또는 기존 row UPDATE). 프로덕션 이전 단계라 안전.

## 값 타입 (영속 없음)

- **ExtractedMenu** (`:core`, seam 반환값): `name: String`(표기 그대로) · `koreanName: String` · `priceKrw: Int?`. `MenuBoardVisionExtractor.extract(imagePath)` 가 순서 보존 목록으로 반환 — 응답 `idx` 는 이 순번(1부터).
- **ScanInput 대체**: `ScanService.scanMenuBoardImage(memberId: Long, imagePath: String)` — 기존 `ScanInput`(items/rawMenuName) 은 제거.
- **ScanResult**(기존 dto 확장): `ItemRiskResult` 에 `price: Int?` 추가, 미매칭 항목도 name/koreanName 을 vision 값으로 채움. `degraded` 유지(vision 경로 상수 false).

## Flyway 마이그레이션 (2건, 독립 적용 가능)

1. `Vyyyy.MM.dd.HH.mm.ss__create_uploaded_image_table.sql` — `uploaded_image` 생성(위 컬럼 + UNIQUE(object_path) + INDEX(member_id) + FK member).
2. `Vyyyy.MM.dd.HH.mm.ss__extend_scan_history_for_photo_scan.sql` — `scan_history` 에 image_path·menu_name·korean_name·price 추가, `food_id` NULL 허용으로 변경.

버전은 파일 생성 시점 timestamp(점 구분, zero-pad) 규칙을 따른다.

## 관계·경계

```
:domain:image  (리프 — :core 만 의존)
     ▲
:domain:scan ──▶ :domain:food ──▶ :domain:member ──▶ :domain:avoidance
```

- scan → image 단방향 의존 신설(엔티티 참조 아님 — `ImageUploadService` 창구 호출, 크로스 도메인 참조는 path/id 값).
- `UploadedImage` 와 `ScanHistory` 는 연관관계 없이 `image_path` 문자열 값으로만 연결(헌법 IV — 연관관계 금지).
