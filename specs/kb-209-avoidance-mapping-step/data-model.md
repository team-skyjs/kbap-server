# Data Model: 음식 기피성분 매핑·맵기 스텝 (KB-209)

## Food (`:domain:food` — 기존 엔티티 수정)

| 필드 | 타입 | 변경 | 의미 |
|---|---|---|---|
| `spiciness` | `Int` (NOT NULL) | `incomplete()` 기본값 0 → **-1** | **-1 = 미조사**(`SPICINESS_UNASSESSED` 상수), 0~10 = 조사완료 맵기 |
| `avoidanceSubstances` | `List<FoodAvoidanceItem>?` (JSON, **NULL 허용으로 변경**) | non-null → nullable | **NULL = 미조사**, `[]` = 조사완료·무성분, 비어있지 않음 = 조사완료 |

- `needsAvoidanceMapping(): Boolean = avoidanceSubstances == null` — 빈 목록은 "완료"로 판정(무한 재조사 차단).
- `transitionToReadyIfComplete()` — 변경 없음(needsAvoidanceMapping 의미 변경으로 자동 반영). 맵기는 READY 게이트에 포함하지 않는다(spec Assumptions).
- 파생 메서드 null-safe 화: `avoidanceSubstancesByProbability()`·`overallRisk()` 는 `orEmpty()` 기반 — READY 음식은 항상 non-null 이므로 동작 불변(INCOMPLETE 는 기존에도 `UNKNOWN` 조기 반환).
- 조사 결과 반영은 도메인 메서드로: `assessAvoidance(substances: List<FoodAvoidanceItem>, spiciness: Int)` — `require(spiciness in 0..10)`, 정렬·저장. **센티널 쌍(성분·맵기)은 이 메서드가 원자적으로 함께 채우는 것이 유일 경로**다(성분만·맵기만 채워진 어긋난 상태 방지 — 필드가 JPA 관례상 `var` 라도 조사 반영은 이 메서드로만 한다).

## FoodAvoidanceItem (변경 없음)

`code: String` + `inclusion_percent: Int(0~100)` — 합의 결과 항목. `riskLevel()` 파생 유지.

## AvoidanceSubstance 카탈로그 (`:domain:avoidance` — 조회만 추가)

- 활성(ACTIVE) 전체 목록은 `AvoidanceSubstanceJpaRepository.findAll()` 직접 조회(@SQLRestriction 이 ACTIVE 필터) — KB-220(ADR-0014)으로 리포지토리가 public 화·위임 전용 창구 서비스 금지라 별도 서비스 메서드를 두지 않는다. 스키마 변경 없음.
- 프롬프트에는 `code` + 한국어 이름(DB `korean_name`)만 사용. 응답 코드 유효성 판정 기준 = 이 목록의 code 집합.

## 배치 값 타입 (`:app:batch` — 신규, 영속 없음)

- `AvoidanceInvestigation(substances: List<FoodAvoidanceItem>, spiciness: Int)` — 합의 확정 결과. `FoodAvoidanceInvestigator` 반환값(실패 시 null).

## Flyway 마이그레이션 (신규 1건 — 파일 생성 시각으로 명명)

`V2026.07.22.HH.mm.ss__food_unassessed_sentinel.sql`

```sql
ALTER TABLE food DROP CONSTRAINT ck_food_spiciness;
ALTER TABLE food ADD CONSTRAINT ck_food_spiciness CHECK (spiciness BETWEEN -1 AND 10);

ALTER TABLE food MODIFY avoidance_substances JSON NULL;

UPDATE food
SET spiciness = -1, avoidance_substances = NULL
WHERE content_status = 'INCOMPLETE';
```

- **CHECK 재정의가 선행 필수** — 기존 `ck_food_spiciness` 는 0~10 만 허용하므로(init_schema:32) 재정의 없이 -1 백필은 즉시 실패한다(Codex Critical 반영). -1 은 미조사 센티널 전용이며 `assessAvoidance` 가 0~10 만 저장하므로 앱 레벨 불변은 유지된다.
- READY 행은 불변(이미 조사완료 데이터). INCOMPLETE 행은 전부 플레이스홀더(어떤 작업도 맵기·성분을 채운 적 없음 — 배치 스텁 상태였음)이므로 일괄 백필이 안전하다.
- 선행 조건: `avoidance_substances` 컬럼·`ck_food_spiciness` 제약이 이미 존재해야 한다 — 둘 다 **develop 에 머지·적용 완료된 마이그레이션**(V2026.07.16 init·V2026.07.21 JSON 컬럼)이 만들므로 "미적용 마이그레이션 간 순서 의존 금지" 규칙에 저촉되지 않는다.

## 상태 전이 (READY 게이트 — 변경 없음, 의미만 완성)

```
INCOMPLETE ──(4작업 모두 완료: 사진·설명·번역9언어·기피성분(null→값))──▶ READY
     ▲                                                                    │
     └── 기피성분 조사 실패(합의 미성립·전 모델 실패) 시 유지, 다음 실행 재시도 ──┘ (재전이 없음)
```

## 동기화 대상 (food 컬럼 변경 시 3곳 규칙 + upsert 경로)

1. Flyway 마이그레이션 (위)
2. scan 테스트 손스텁 CREATE TABLE — `avoidance_substances` NULL 허용 반영
3. food INSERT 시드 전수 — NOT NULL 전제 깨짐 확인 (전체 `./gradlew build` 로 검증)
4. **`upsertIncomplete` SQL** — `'[]'` 하드코딩 → NULL (미수정 시 upsert 적재 음식이 non-null 로 저장돼 기피성분 조사가 영구 스킵되고 미조사 READY 전이 가능 — 센티널 무력화, DB 리뷰 Major)

> **릴리스 결합 (DB 리뷰 Blocker)**: 엔티티 센티널(Unit A)과 마이그레이션·upsert 수정(Unit B)은 **반드시 같은 릴리스로 배포**한다 — Unit A 단독 머지 시 Flyway 적용 DB 에서 `ck_food_spiciness CHECK(0..10)` 위반으로 신규 음식 적재가 전건 실패한다(테스트는 자동생성 스키마라 CHECK 가 없어 못 잡음). 본 브랜치는 두 Unit 을 단일 PR 로 묶는다.
