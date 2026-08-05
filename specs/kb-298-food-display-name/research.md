# Research: 음식 표시용 이름 분리 (KB-298)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 설계 선택지 검토 기록이다.

## R1. 표기 보존 방식 — 컬럼 추가 vs match key 분리

- **Decision**: `display_name` 컬럼 추가, `korean_name` 은 정규화 match key 로 유지.
- **Rationale**: 유니크 제약(`uq_food_korean_name`)·upsert(`on duplicate key`)·스캔 매칭·관리자 검색이 전부 `korean_name` 을 기준으로 동작한다. 컬럼을 그대로 두면 중복 방지 경로가 무변경으로 유지되고(SC-002), 추가 컬럼은 표시 전용이라 실패 모드가 없다.
- **Alternatives considered**:
  - `korean_name` 리네임(`match_key`) + `display_name` 추가: 의미상 가장 명확하나 유니크 제약·인덱스·네이티브 SQL·파생 쿼리 전면 수정 — KB-298 범위(컬럼 추가+응답 교체) 초과. 후속 리팩터링 후보로 남긴다.
  - 과거 `food_korean_match_key` 컬럼 부활(V2026.07.21 에서 폐기된 구조의 역방향): 표시명이 유니크 키가 되어 "김치 찌개"/"김치찌개" 가 다른 행이 된다 — 중복 방지 목적과 충돌, 기각.

## R2. NOT NULL 전략 — 즉시 강제 vs DEFAULT '' + 읽기 폴백

- **Decision**: `VARCHAR(255) NOT NULL DEFAULT ''` + 백필 UPDATE, 읽기 시 `displayName.ifBlank { koreanName }` 폴백.
- **Rationale**: 테스트 다수(`AdminControllerTest`·`BookmarkControllerTest`·`FoodTestSeed` 등 10+ 파일)가 raw `insert into food (...)` 시드를 쓴다. DEFAULT 없이 NOT NULL 로 만들면 전부 수정해야 한다(메모리: food 컬럼 추가 시 전체 build 로만 잡히는 함정). DEFAULT '' 는 시드 무수정 흡수 + 프로덕션 백필로 FR-005(빈 표시명 0건) 충족, 폴백은 혹시 남을 빈 값의 화면 노출을 차단(edge case 방어).
- **Alternatives considered**: nullable 컬럼 — Kotlin 프로퍼티가 `String?` 이 되어 소비처 전부에 null 분기 전파. 기각.

## R3. 소비처 교체 지점 — 전수 조사 결과

`korean_name` 값이 사용자/운영자에게 노출되거나 외부(LLM)로 나가는 경로 전수:

| 경로 | 파일 | 처리 |
|------|------|------|
| 스캔 결과 name/koreanName | `api/scan/ScanService.kt` (`koreanName()`·miss 시 menu.koreanName) | matched → `displayName`, miss → 원본 표기 저장·응답 |
| 음식 상세 name/koreanName | `common/domain/food/FoodService.getDetail` | `displayName(lang)` ko 베이스 교체로 자동 + koreanName 필드 매핑 교체 |
| 목록·검색·북마크·홈 요약 | `common/domain/food/dto/FoodSummaryView.kt` | 동일(ko 베이스 + koreanName 매핑) |
| 커뮤니티 음식 태그 | `api/community/CommunityService.kt` (`displayName(lang)`) | ko 베이스 교체로 자동 |
| 관리자 목록·상세·검수 응답 | `api/admin/AdminFoodService.kt`·`AdminFoodContentReviewResponse.kt` | koreanName 필드 값 → displayName |
| 이미지 생성 프롬프트 | `api/food/FoodImageBatchCollectService.kt` | `displayName` 사용 |
| batch LLM 3종(설명·번역·회피) | `batch/content/FoodContentItemProcessor.kt` | `displayName` 사용 |

`ScanHistory.koreanName` 은 이미 원본 표기를 저장(스펙 Assumption — 변경 대상 아님).

## R4. KO 검색 회귀 방지

- **Decision**: `FoodService.getFoodsByKeyword` 의 KO 분기와 관리자 목록 검색에서 키워드를 `KoreanMenuNameNormalizer.matchKey` 로 정규화해 `korean_name` LIKE 에 태운다(정규화 결과 blank 면 원 키워드 그대로, 이스케이프 유지).
- **Rationale**: 화면이 "김치 찌개" 를 보여주기 시작하면 사용자가 그 표기대로 검색한다 — 정규화 없이는 매칭 실패이고, 이는 **이 PR 이 스스로 만드는 회귀**라 최소한으로 막아야 한다. 검색 대상 컬럼·인덱스·쿼리 형태는 그대로다.
- **알려진 한계(의도적 보류)**: `display_name` 은 검색 대상이 아니다. 표시명에만 있는 영문·숫자 조각으로는 못 찾는다(예: 표시명 "BHC 치킨", match key "치킨" → "BHC" 검색 시 0건). 한글·비한글이 섞인 검색어는 `korean_name` 분기에서 비한글 부분이 떨어져 결과가 넓어질 수 있다. Codex 리뷰가 두 컬럼 OR 매칭을 제안했고 한 번 구현했으나, **검색 자체를 별도 솔루션으로 교체할 예정**이라 임시 확장을 걷어내고 최소 정규화만 남겼다(2026-08-05 결정).
- **Alternatives considered**: `display_name` LIKE 단독 교체 — 공백 없이 검색하면 미매칭, 역방향 회귀. 기각. 두 컬럼 OR — 위 한계를 해소하지만 곧 교체될 구현에 쿼리·분기를 늘리는 비용이라 보류.

## R5. 관리자 음식명 수정 의미론

- **Decision**: 수정 입력을 `display_name` 에 저장하고 `korean_name = matchKey(입력)` 으로 재정규화. 중복 검사는 match key 기준.
- **Rationale**: 스펙 Assumption(표기 교정은 기존 관리자 수정 기능 담당)의 실현. match key 를 함께 갱신해야 이후 스캔이 교정된 음식으로 계속 매칭된다. `korean_name = matchKey(display_name)` 불변식이 유지된다.
- **Alternatives considered**: display 만 수정(match key 불변) — 이름을 실질 변경(오타 교정)하면 이후 스캔이 옛 key 로만 매칭돼 드리프트. 기각.

## R6. Kotlin 네이밍 — `displayName` 프로퍼티 vs 기존 `displayName(lang)` 메서드

- **Decision**: 프로퍼티 `displayName`(컬럼)과 메서드 `displayName(lang)`(다국어 해석) 공존. `koreanName()` 액세서는 삭제하고 소비처는 `displayName`/`displayName(lang)` 로 통일.
- **Rationale**: JVM 시그니처(`getDisplayName()` vs `displayName(LanguageCode)`)가 달라 충돌 없음. `displayName(KO) == displayName` 이 성립해 의미도 일관된다. `koreanName()` 은 match key 를 표시용으로 흘리던 통로라 제거가 곧 재발 방지다.
## R7. 빈 표시명 자가 치유 (Codex 리뷰 반영)

- **Decision**: 스캔 적재 upsert 의 충돌 절을 `on duplicate key update display_name = if(food.display_name = '', values(display_name), food.display_name)` 으로 바꾼다.
- **Rationale**: `DEFAULT ''` 는 영구 존치라, 롤링 배포 중 구버전 인스턴스가 쓰거나 raw INSERT 가 들어오면 백필 이후에도 빈 표시명 행이 생길 수 있다(운영 api 2대). 조건부 채움은 first-write-wins 를 깨지 않으면서(비어 있을 때만 채움) 그런 행을 다음 스캔에 자동 복구한다. 읽기 폴백과 합쳐 2중 방어.
- **Alternatives considered**: 2단계 롤아웃 후 `DEFAULT` 제거 — 마이그레이션 1건이 더 필요하고, 흩어진 raw INSERT 테스트 시드가 전부 깨진다. 이득 대비 비용이 커 기각(빈 값이 사용자에게 노출되지 않는다는 점이 이미 폴백으로 보장됨).

## R8. 이전 정규화에서 건너뛴 행 마무리 (Codex 리뷰 반영)

- **Decision**: 신규 마이그레이션에서 display_name 백필 **뒤에** `korean_name` 정규화를 한 번 더 돌린다(V2026.07.21 과 동일 조건 — 충돌·빈 결과 행은 제외).
- **Rationale**: 이전 마이그레이션은 정규화하면 이름이 뭉개져 보이는 문제 때문에 일부 행을 "수동 정리 대상" 으로 남겼고, 그 행들은 `korean_name != matchKey(korean_name)` 상태다. 이제 백필이 원본 표기를 `display_name` 에 보존하므로 **정규화가 무손실**이 되어 미룰 이유가 없다. 순서가 중요하다(백필 → 정규화).
- **남는 것**: unique 충돌 행은 병합 판단이 필요해 그대로 둔다. 앱이 match key 로 조회하므로 이 행들은 지금처럼 스캔 매칭에서 빠진 상태가 유지된다(수동 정리 대상).
