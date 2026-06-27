# Phase 1 Data Model — 메뉴 스캔 mock 슬라이스

도메인 모델(컨텍스트별)과 영속 스키마(Flyway, MySQL)를 정의한다. 영속 엔티티는 각 도메인 모듈의 `infrastructure` 패키지에 은닉하고, 외부에는 도메인 엔티티 + DomainRepository 인터페이스만 공개한다(헌법 IV).

## 공유 커널 (`:meogo-api:core`)

### RiskLevel (enum)
`com.meogo.core.risk.RiskLevel`
- 값: `SAFE`, `CAUTION`, `DANGER`, `UNKNOWN` (고정 4단계)
- 컨텍스트 공유(scan 결과·후속 assessment·food 재료 상태에서 사용). Spring-free.

---

## scan 컨텍스트 (`:meogo-api:scan`)

### MenuScan (Aggregate Root)
| 필드 | 타입 | 규칙 |
|------|------|------|
| id (scanId) | Long | DB auto-increment, PK |
| status | ScanStatus | 이번 범위 `COMPLETED` |
| items | List\<ScannedMenuItem\> | 1..100, 비어 있을 수 없음 |
| createdAt | Instant | 생성 시각 |

- 불변식: items 최소 1개·최대 100개; itemId는 스캔 내 유일.
- Aggregate Root를 통해서만 항목 추가/판정 부여(헌법 II).

### ScannedMenuItem (Entity, MenuScan 내부)
| 필드 | 타입 | 규칙 |
|------|------|------|
| itemId | Int | 클라이언트 제공, 스캔 내 유일 |
| rawMenuName | String | blank 불가 |
| boundingBox | BoundingBox | 필수 |
| receivedOrder | Int | 수신 배열 순서(0-based) — 판정 순서·재현용 |
| assessment | MenuItemAssessment | mock 판정 스냅샷 |

### BoundingBox (Value Object)
**정규화 비율 좌표** — 클라이언트 OCR 기준 이미지 대비. 좌상단 (0,0)·우하단 (1,1).

| 필드 | 타입 | 규칙 |
|------|------|------|
| x | Double | ≥ 0, `x + width ≤ 1` |
| y | Double | ≥ 0, `y + height ≤ 1` |
| width | Double | > 0 |
| height | Double | > 0 |

- 불변식(생성 시 검증): `x≥0 ∧ y≥0 ∧ width>0 ∧ height>0 ∧ x+width≤1 ∧ y+height≤1`.
- 판정 미사용, UI 오버레이/재현용 저장. 클라이언트가 표시 이미지 크기에 비율을 곱해 복원(해상도 독립).
- 전제: 이미지 압축은 aspect ratio 보존. crop/pad/rotate/orientation 보정은 범위 밖(좌표 기준 변경 금지).

### MenuItemAssessment (Value Object, mock 스냅샷)
| 필드 | 타입 | 규칙 |
|------|------|------|
| riskLevel | RiskLevel | 4단계 중 하나 |
| reason | String | mock 사유 문구 |

### ScanStatus (enum)
- `COMPLETED` (이번 범위). `PROCESSING`/`PARTIAL`/`FAILED` 예약(미사용).

### MenuScanRepository (DomainRepository 인터페이스 — 공개)
- `fun save(menuScan: MenuScan): MenuScan`
- `fun findById(scanId: Long): MenuScan?` (재열람 API는 없지만 저장 검증/후속용)

### 영속 스키마 — `V1__create_scan_tables.sql`
```sql
CREATE TABLE menu_scan (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    status      VARCHAR(20) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE scanned_menu_item (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    scan_id         BIGINT      NOT NULL,
    item_id         INT         NOT NULL,        -- 클라이언트 제공(스캔 내 유일)
    raw_menu_name   VARCHAR(255) NOT NULL,
    bbox_x          DOUBLE      NOT NULL,
    bbox_y          DOUBLE      NOT NULL,
    bbox_width      DOUBLE      NOT NULL,
    bbox_height     DOUBLE      NOT NULL,
    received_order  INT         NOT NULL,
    risk_level      VARCHAR(10) NOT NULL,         -- mock 판정 스냅샷
    reason          VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_scan FOREIGN KEY (scan_id) REFERENCES menu_scan(id),
    CONSTRAINT uq_scan_item UNIQUE (scan_id, item_id)
);
```

---

## food 컨텍스트 (`:meogo-api:food`)

> **다국어**: 음식명·재료명은 `ko` 원문 + 9개 대상 언어 번역본을 보유한다(헌법 V·ADR-0003). `ko` 원문은 본 테이블 컬럼(매칭 키), 9개 번역은 별도 translation 테이블. 조회 시 요청 언어 값을 선택하고 없으면 `ko` 폴백. `LanguageCode`(R12 인접): `ko`+9개.

### Food (Aggregate Root)
| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long | PK |
| koreanName | String | blank 불가, `ko` 원문 = **조회 매칭 키** |
| names | Map\<LangCode, String\> | 9개 대상 언어 번역(부분 가능) |
| imageRef | String? | 대표 이미지 참조(언어 무관) |
| ingredients | List\<FoodIngredient\> | 0..N(빈 배열 허용) |

- `nameFor(lang)`: `lang==ko` 또는 미지원/번역 없음 → `koreanName`, 아니면 `names[lang]`.
- 조회: `koreanName` trim 후 exact match.

### Ingredient (Entity)
| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long | PK |
| koreanName | String | blank 불가(`ko` 원문) |
| names | Map\<LangCode, String\> | 9개 대상 언어 번역 |
| iconRef | String? | 표시 아이콘 참조 |
- `nameFor(lang)`: Food와 동일 폴백. 여러 음식에서 공유.

### FoodIngredient (관계 Entity)
| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long | PK |
| foodId | Long | FK → food |
| ingredientId | Long | FK → ingredient |
| inclusionPercent | Int | 0~100 (연속 비율) |
| displayOrder | Int | 표시 순서 |
- riskStatus는 **저장하지 않음**(application mock marker가 부여).

### FoodRepository (DomainRepository 인터페이스 — 공개)
- `fun findByKoreanName(name: String): Food?` (호출 전 trim; 음식+재료+번역을 함께 로드)

### 영속 스키마 — `V2__create_food_tables.sql`
```sql
CREATE TABLE food (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name   VARCHAR(255) NOT NULL,         -- ko 원문(매칭 키)
    image_ref     VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_food_korean_name (korean_name)
);

CREATE TABLE food_name_translation (              -- 9개 대상 언어
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    food_id   BIGINT      NOT NULL,
    lang_code VARCHAR(10) NOT NULL,               -- zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es
    name      VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fnt_food FOREIGN KEY (food_id) REFERENCES food(id),
    UNIQUE KEY uq_fnt (food_id, lang_code)
);

CREATE TABLE ingredient (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name   VARCHAR(255) NOT NULL,         -- ko 원문
    icon_ref      VARCHAR(500) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE ingredient_name_translation (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    ingredient_id BIGINT      NOT NULL,
    lang_code     VARCHAR(10) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_int_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id),
    UNIQUE KEY uq_int (ingredient_id, lang_code)
);

CREATE TABLE food_ingredient (
    id                BIGINT  NOT NULL AUTO_INCREMENT,
    food_id           BIGINT  NOT NULL,
    ingredient_id     BIGINT  NOT NULL,
    inclusion_percent INT     NOT NULL,            -- 0~100
    display_order     INT     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fi_food FOREIGN KEY (food_id) REFERENCES food(id),
    CONSTRAINT fk_fi_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id)
);
```

### Seed — `V3__seed_food_data.sql` (데모: 된장찌개 스크린샷 재현)
- food: `된장찌개`(ko 원문) + **9개 언어 번역**(food_name_translation) — 예: en `Doenjang Stew`, ja `テンジャンチゲ`, zh-Hans `大酱汤` …(9개 행 모두)
- ingredient(ko 원문) + 9개 언어 번역(ingredient_name_translation): `바지락 조개`(en `Manila clam`…), `된장`, `두부`, `애호박`, `소고기`
- food_ingredient(inclusion_percent): 바지락 50, 된장 100, 두부 90, 애호박 85, 소고기 40 (display_order 순)
- **모든 seed 음식/재료는 9개 대상 언어 번역을 빠짐없이 포함**(헌법 V 충족). 번역 누락 시 그 항목만 `ko` 폴백되나, seed는 전 언어를 채운다.

---

## 애플리케이션 입출력 타입 (`:meogo-api:application`)

> 도메인 엔티티를 api로 직접 노출하지 않기 위한 계층 타입(Command/Result). api는 이 타입과 자기 DTO만 본다.

### scan
- `SubmitMenuScanCommand`: `items: List<Item>` — Item(itemId, rawMenuName, boundingBox(x,y,w,h))
- `MenuScanResult`: `scanId`, `results: List<ItemResult>` — ItemResult(itemId, riskLevel, reason)
- `MenuItemRiskAssessor` (seam): `assess(index, item) -> (RiskLevel, reason)`; 구현 `MockCyclingRiskAssessor`(index%4)

### food
- `GetFoodDetailQuery`: `menuName: String`, `lang: String?`(미지정/미지원 → ko 폴백)
- `FoodDetailResult`: `name`(요청 언어), `imageRef`, `ingredients: List<IngredientView>` — IngredientView(`name`(요청 언어), iconRef, inclusionPercent, riskStatus)
- `LanguageResolver` (seam): 입력 `lang` → 지원 `LangCode` 또는 `ko`(폴백). 향후 회원 언어 출처로 교체될 지점(R7).
- `IngredientRiskMarker` (seam): `mark(ingredients) -> List<riskStatus>`; 구현 `MockIngredientRiskMarker`(첫 재료 CAUTION, 나머지 SAFE)
- `FoodNotFoundException`: 미발견 → 404 매핑

---

## 매핑 경계 요약

```
[api DTO] ⇄ [application Command/Result] ⇄ [domain Entity] ⇄ [infra JPA Entity]
   └ Bean Validation        └ 유스케이스 조립·mock seam       └ DomainRepository 구현(은닉)
```
- api는 domain/JPA 엔티티를 import하지 않는다(헌법 IV).
- application은 infra 구현체가 아닌 DomainRepository 인터페이스에만 의존(헌법 III).
