# Phase 1 Data Model — 메뉴 스캔 mock 슬라이스

도메인 모델(컨텍스트별, **순수 — Spring/ORM-free**)과 영속 스키마(Flyway, MySQL)를 정의한다. **JPA 엔티티·Spring Data·RepositoryAdapter 는 중앙 `:meogo-api:persistence` 모듈**(`com.meogo.api.persistence.<context>`)에 두고(ADR-0006), 도메인 모듈은 model + DomainRepository **port 인터페이스**만 공개한다(헌법 IV 의도). 도메인↔JPA 변환은 **JPA 엔티티 안**(`toDomain()` / `companion object fun from(domain)`)에 둔다. 모든 엔티티는 `com.meogo.api.persistence.BaseEntity`(@MappedSuperclass: `id`·`status`(EntityStatus 소프트삭제)·`createdAt`·`updatedAt`)를 상속하며 `@SQLRestriction("status = 'ACTIVE'")`로 ACTIVE 만 조회된다.

> **재정합(2026-06-28)**: `ScannedMenuItem.receivedOrder` **제거**(itemId 는 응답 매핑용 상관 키, 순서 무의미) · 영속 위치 `infrastructure` → `:meogo-api:persistence` · 응답 봉투 `BaseResponse`/`payload` · application 입출력 `Command/Query` → `Input/Result` · 미수록 음식 **400** · 재료 `riskStatus` = 4단계 `RiskLevel` 재사용 · `inclusionPercent`(연속 %)와 `0/1/2`(후속 LLM 스코어링)는 별개.

## 공유 커널 (`:meogo-api:core`)

### RiskLevel (enum) — `com.meogo.api.core.risk.RiskLevel`
- 값: `SAFE`, `CAUTION`, `DANGER`, `UNKNOWN` (고정 4단계). Spring-free.
- 컨텍스트 공유: scan 항목 판정 결과 · **food 재료 `riskStatus`(재사용)** · 후속 assessment.

---

## scan 컨텍스트 (`:meogo-api:scan`) — 순수 도메인 *(US1, 구현 완료)*

### MenuScan (Aggregate Root)
| 필드 | 타입 | 규칙 |
|------|------|------|
| id (scanId) | Long? | DB auto-increment PK (도메인 생성 시 null) |
| status | ScanStatus | 이번 범위 `COMPLETED` |
| items | List\<ScannedMenuItem\> | 1..100, 비어 있을 수 없음 |

- 불변식: items 1..100; itemId 스캔 내 유일.
- 생성: `MenuScan.create(spec: CreationSpec)`, 복원: `MenuScan.reconstitute(id, status, items)`. (`CreateCommand` 아님 — `CreationSpec`.)
- Aggregate Root 통해서만 항목 구성(헌법 II). 도메인 객체 불변(상태 변경은 새 인스턴스).

### ScannedMenuItem (Entity, MenuScan 내부)
| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long? | 영속 PK(도메인 생성 시 null) |
| itemId | Int | 클라이언트 제공, **응답 매핑용 상관 키**(스캔 내 유일, 순서 무의미) |
| rawMenuName | String | blank 불가 |
| boundingBox | BoundingBox | 필수 |
| assessment | MenuItemAssessment | mock 판정 스냅샷 |

> `receivedOrder` 는 **두지 않는다**. mock 위험도 순환은 유스케이스의 `mapIndexed` 지역 index 로만 산출하며 저장하지 않는다. 응답 정렬/매칭은 `itemId` 기준(서버는 결과 순서를 보존할 필요 없음).

### BoundingBox (Value Object)
정규화 비율 좌표 — 클라이언트 OCR 기준 이미지 대비. 좌상단 (0,0)·우하단 (1,1).

| 필드 | 타입 | 규칙 |
|------|------|------|
| x | Double | ≥ 0, `x + width ≤ 1` |
| y | Double | ≥ 0, `y + height ≤ 1` |
| width | Double | > 0 |
| height | Double | > 0 |

- 불변식(생성 시 검증): `x≥0 ∧ y≥0 ∧ width>0 ∧ height>0 ∧ x+width≤1 ∧ y+height≤1`.
- 판정 미사용, UI 오버레이/재현용. 표시 이미지 크기에 비율을 곱해 복원(해상도 독립).
- 전제: 이미지 압축은 aspect ratio 보존. crop/pad/rotate/orientation 보정은 범위 밖.

### MenuItemAssessment (Value Object, mock 스냅샷)
| 필드 | 타입 | 규칙 |
|------|------|------|
| riskLevel | RiskLevel | 4단계 중 하나 |
| reason | String | mock 사유 문구 |

### ScanStatus (enum)
- `COMPLETED`(이번 범위). `PROCESSING`/`PARTIAL`/`FAILED` 예약(미사용).

### MenuScanRepository (도메인 port — 공개)
- `fun save(menuScan: MenuScan): MenuScan`
- `fun findById(scanId: Long): MenuScan?` (재열람 API 없으나 저장 검증/후속용)

### 영속 스키마 — `V1__create_scan_tables.sql` *(구현된 그대로)*
> 공통 컬럼(`status`=EntityStatus 소프트삭제, `created_at`, `updated_at`)은 BaseEntity 에서 온다. 도메인 고유 상태 `scan_status`(ScanStatus)는 `status`와 컬럼명이 겹치지 않게 분리.

```sql
CREATE TABLE menu_scan (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    scan_status  VARCHAR(20) NOT NULL,                  -- ScanStatus(도메인): COMPLETED 등
    status       VARCHAR(20) NOT NULL,                  -- EntityStatus: ACTIVE/DELETED
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE scanned_menu_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    scan_id         BIGINT       NOT NULL,
    item_id         INT          NOT NULL,              -- 클라이언트 제공(스캔 내 유일)
    raw_menu_name   VARCHAR(255) NOT NULL,
    bbox_x          DOUBLE       NOT NULL,
    bbox_y          DOUBLE       NOT NULL,
    bbox_width      DOUBLE       NOT NULL,
    bbox_height     DOUBLE       NOT NULL,
    risk_level      VARCHAR(10)  NOT NULL,              -- mock 판정 스냅샷
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL,              -- EntityStatus: ACTIVE/DELETED
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_scan FOREIGN KEY (scan_id) REFERENCES menu_scan(id),
    CONSTRAINT uq_scan_item UNIQUE (scan_id, item_id)
);
```
*(`received_order` 컬럼 없음 — 제거됨.)*

---

## food 컨텍스트 (`:meogo-api:food`) — 순수 도메인 *(US2, 신규)*

> **다국어**: 음식명·재료명은 `ko` 원문 + 9개 대상 언어 번역본 보유(헌법 V·ADR-0003). `ko` 원문은 본 테이블 컬럼(매칭 키), 9개 번역은 별도 translation 테이블(`ko` 는 번역 테이블에 **저장하지 않는다**). `LanguageCode`: `ko`+9개.
>
> **읽기 모델(2026-06-28 리팩터)**: 도메인은 **구조만**(음식·재료·관계) 복원하고 **번역 맵을 들지 않는다**. 조회 시 ① `Food`+`Ingredient` 구조를 fetch join 으로 복원하고, ② **요청 언어 번역만** 별도 쿼리로 읽은 뒤, ③ **application service 에서 `ko` 폴백·응답 조립**을 한다(전 언어 번역을 한 쿼리로 fetch join 하던 row 폭증 제거 — [[food-translation-read-model]]).

### Food (Aggregate Root) — 구조만
| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long? | PK |
| koreanName | String | blank 불가, `ko` 원문 = **조회 매칭 키** |
| imageRef | String? | 대표 이미지 참조(언어 무관) |
| ingredients | List\<FoodIngredient\> | 0..N(빈 배열 허용) |

- **`names` 맵·`nameFor()` 없음** — 언어별 이름 선택·폴백은 application service 가 요청 언어 번역 조회 결과로 수행.
- 조회: `koreanName` trim 후 exact match.

### Ingredient (Entity) — 공유 지식베이스
| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long? | PK |
| koreanName | String | blank 불가(`ko` 원문), **unique** |
| iconRef | String? | 표시 아이콘 참조 |
- **`names` 맵·`nameFor()` 없음**(구조만). 여러 음식이 `ingredient_id` 로 **공유 참조** — 음식 저장 시 새로 만들지 않고 기존 재료를 참조한다(복제 금지).

### FoodIngredient (관계 Entity)
| 필드 | 타입 | 규칙 |
|------|------|------|
| id | Long? | PK |
| ingredient | Ingredient | 연결된 **공유** 재료(영속: `@ManyToOne`, **cascade 없음**) |
| inclusionPercent | Int | **0~100 연속 비율** — 여러 레시피 기준 포함 확률(UI `~50%` 원천) |
| displayOrder | Int | 재료 표시 순서(안정 정렬용) |
- `(food_id, ingredient_id)` 는 **unique**(같은 음식에 같은 재료 중복 금지).
- `riskStatus`는 **저장하지 않음** — application `IngredientRiskMarker`(mock)가 4단계 `RiskLevel` 로 부여.
- `0/1/2` 스코어는 본 필드와 **별개**(후속 LLM per-recipe 스코어링 입력값, 이번 범위 밖).

### LanguageCode (enum) — `ko` + 9개(`zh-Hans`·`en`·`ja`·`zh-Hant`·`vi`·`id`·`th`·`ru`·`es`)
- `from(code)`: 미지원/미지정은 `ko` 폴백. **요청 lang 해석(`LanguageResolver`)에만** 사용 — DB 에 저장된 `lang_code` 를 enum 으로 되매핑하지 않는다(잘못된 코드가 조용히 `KO` 로 접히는 것 방지; 번역은 요청 lang 으로만 조회해 자연 배제).

### FoodRepository (도메인 port — 공개)
- `fun findByKoreanName(name: String): Food?` — **구조만**(음식+재료) fetch join 복원. 호출 전 trim.
- `fun findFoodNameTranslation(foodId: Long, lang: LanguageCode): String?` — 요청 언어 음식명 번역(없으면 null → app `ko` 폴백).
- `fun findIngredientNameTranslations(ingredientIds: List<Long>, lang: LanguageCode): Map<Long, String>` — 요청 언어 재료명 번역(`ingredient_id → name`).

### 영속 스키마 — `V2__create_food_tables.sql`
> 모든 테이블에 BaseEntity 공통 컬럼(`status`·`created_at`·`updated_at`) 포함.

```sql
CREATE TABLE food (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name   VARCHAR(255) NOT NULL,               -- ko 원문(매칭 키)
    image_ref     VARCHAR(500) NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_food_korean_name (korean_name)
);

CREATE TABLE ingredient (                               -- 공유 지식베이스(여러 음식이 참조)
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name   VARCHAR(255) NOT NULL,               -- ko 원문
    icon_ref      VARCHAR(500) NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ingredient_korean_name (korean_name)  -- 공유 재료 매칭 키(복제 금지)
);

CREATE TABLE food_name_translation (                    -- 9개 대상 언어(ko 미저장)
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    food_id   BIGINT      NOT NULL,
    lang_code VARCHAR(10) NOT NULL,                     -- zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es
    name      VARCHAR(255) NOT NULL,
    status    VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fnt_food FOREIGN KEY (food_id) REFERENCES food(id),
    UNIQUE KEY uq_fnt (food_id, lang_code),
    CONSTRAINT ck_fnt_lang CHECK (lang_code IN ('zh-Hans','en','ja','zh-Hant','vi','id','th','ru','es'))
);

CREATE TABLE ingredient_name_translation (              -- ko 미저장
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    ingredient_id BIGINT      NOT NULL,
    lang_code     VARCHAR(10) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_int_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id),
    UNIQUE KEY uq_int (ingredient_id, lang_code),
    CONSTRAINT ck_int_lang CHECK (lang_code IN ('zh-Hans','en','ja','zh-Hant','vi','id','th','ru','es'))
);

CREATE TABLE food_ingredient (
    id                BIGINT  NOT NULL AUTO_INCREMENT,
    food_id           BIGINT  NOT NULL,
    ingredient_id     BIGINT  NOT NULL,                 -- 기존 ingredient 참조(공유)
    inclusion_percent INT     NOT NULL,                 -- 0~100 (여러 레시피 기준 포함 확률)
    display_order     INT     NOT NULL,
    status            VARCHAR(20) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fi_food FOREIGN KEY (food_id) REFERENCES food(id),
    CONSTRAINT fk_fi_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id),
    UNIQUE KEY uq_food_ingredient (food_id, ingredient_id)
);
```

### Seed — `V3__seed_food_data.sql` (데모: 대표 메뉴 10종)
- food **10종**(된장찌개·김치찌개·비빔밥·불고기·삼겹살·떡볶이·김밥·잡채·순두부찌개·물냉면), 각 `ko` 원문 + 9개 언어 번역.
- ingredient **30종(공유 풀, dedup)** — 두부·마늘·대파·계란 등은 여러 음식이 같은 `ingredient_id` 로 참조. 각 재료도 9개 언어 번역 보유.
- food_ingredient: 음식별 3~6개 재료(inclusion_percent·display_order).
- **모든 seed 음식/재료는 9개 대상 언어 번역을 빠짐없이 포함**(헌법 V). seed 행은 `status='ACTIVE'`, 명시 id 로 적재. MySQL 8.4 에서 제약·무결성 검증 완료.

---

## 애플리케이션 입출력 타입 (`:meogo-api:application`)

> 도메인 엔티티를 presentation 으로 직접 노출하지 않기 위한 계층 타입. presentation 은 이 `Input/Result` 와 자기 DTO(`Request/Response`)만 본다. (CQRS 뉘앙스의 `Command/Query`는 쓰지 않음.)

### scan *(구현 완료)*
- `SubmitMenuScanInput`: `items: List<MenuScanItemInput>` — Item(itemId, rawMenuName, boundingBox(x,y,w,h))
- `SubmitMenuScanResult`: `scanId`, `items: List<ItemRiskResult>` — ItemRiskResult(id, itemId, riskLevel, reason)
- `MenuItemRiskAssessor`(seam): `assess(index, rawMenuName) -> MenuItemAssessment`; 구현 `MockCyclingRiskAssessor`(index%4)

### food *(신규)*
- `GetFoodDetailInput`: `menuName: String`, `lang: String?`(미지정/미지원 → ko 폴백)  ※ `Query` 아님
- `GetFoodDetailResult`: `name`(요청 언어), `imageRef`, `ingredients: List<IngredientView>` — IngredientView(`name`(요청 언어), iconRef, inclusionPercent, riskStatus: RiskLevel)
- `LanguageResolver`(seam): `lang` → 지원 `LanguageCode` 또는 `ko`(폴백). 향후 회원 언어 출처로 교체될 지점(R7).
- `IngredientRiskMarker`(seam): `mark(ingredients: List<Ingredient>) -> List<RiskLevel>`(평행 리스트); 구현 `MockIngredientRiskMarker`(첫 재료 CAUTION, 나머지 SAFE)
- **미수록 메뉴 처리**: `findByKoreanName` 가 null 이면 유스케이스가 예외를 던지고 `GlobalExceptionHandler`가 **400 + `BaseResponse.fail("해당 음식 정보 없음")`** 로 매핑(clarify 2026-06-28; 이전 404 대체). `IllegalArgumentException`(현 핸들러가 400 매핑) 또는 400 매핑 전용 예외 사용.

---

## 매핑 경계 요약

```
[presentation DTO]  ⇄  [application Input/Result]  ⇄  [domain]  ⇄  [persistence JPA Entity]
  Request/Response       유스케이스 조립·mock seam     model+port    toDomain()/from(domain) (도메인은 JPA 모름)
  + Bean Validation       (BaseResponse 봉투는 presentation)         RepositoryAdapter 가 port 구현(:meogo-api:persistence)
```
- presentation 은 domain/JPA 엔티티를 import 하지 않는다(헌법 IV). application 은 domain port 인터페이스에만 의존(헌법 III).
- 도메인↔JPA 변환은 JPA 엔티티 안(`toDomain`/`from`)에 둔다 — 별도 Mapper 없음.
