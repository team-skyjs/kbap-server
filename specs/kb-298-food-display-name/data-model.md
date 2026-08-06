# Data Model: 음식 표시용 이름 분리 (KB-298)

## Food (`food` 테이블 — 변경)

| 필드 | 컬럼 | 타입 | 변경 | 규칙 |
|------|------|------|------|------|
| `koreanName` | `korean_name` | VARCHAR(255) NOT NULL, UNIQUE(`uq_food_korean_name`) | 무변경 | **match key** — `KoreanMenuNameNormalizer.matchKey` 결과(한글 음절만). 중복 방지·스캔 매칭·검색 기준 |
| `displayName` | `display_name` | VARCHAR(255) NOT NULL DEFAULT '' | **신규** | **표시용 원본 표기**(띄어쓰기 포함). blank 금지·255자 제한(`Food.incomplete` 검증). 읽기 폴백 `ifBlank { koreanName }` |
| (기타 필드) | | | 무변경 | |

### 불변식

- `korean_name = matchKey(display_name)` — 적재(스캔 miss·관리자 시드)와 관리자 수정 모두 이 관계를 유지한다.
- 같은 `korean_name` 으로 매칭되는 후속 표기는 기존 `display_name` 을 덮어쓰지 않는다(first-write-wins — upsert `on duplicate key update id = id`).
- 백필 후 `display_name` 이 빈 행은 존재하지 않는다(FR-005). 이관 초기값 = `korean_name`.

### 도메인 메서드 변경

- `Food.incomplete(koreanName, displayName)` — 2인자로 확장. 두 값 모두 blank 금지·255자 검증.
- `localizedName()` — ko 베이스를 `koreanName` → `displayName.ifBlank { koreanName }` 으로 교체. `displayName(lang)` 소비처는 자동으로 표시명을 받는다.
- `koreanName()` 액세서 삭제 — 소비처는 `displayName`(프로퍼티) 또는 `displayName(lang)` 사용.

## Flyway 마이그레이션 (신규 1건)

`api/src/main/resources/db/migration/V2026.08.05.<HH.mm.ss>__add_food_display_name.sql`

```sql
ALTER TABLE food ADD COLUMN display_name VARCHAR(255) NOT NULL DEFAULT '' AFTER korean_name;
UPDATE food SET display_name = korean_name WHERE display_name = '';
```

- 독립 실행 가능(다른 미적용 마이그레이션과 순서 무관 — out-of-order 전제 충족).
- DEFAULT '' 유지 — raw INSERT(테스트 시드·수동 운영 쿼리)의 하위 호환. 프로덕션 값은 백필 + 애플리케이션 쓰기 경로가 보장.

## 상태 전이

변경 없음 — `FoodContentStatus` 파이프라인은 `display_name` 과 무관하게 동작한다.
