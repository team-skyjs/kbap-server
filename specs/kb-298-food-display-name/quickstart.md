# Quickstart: 음식 표시용 이름 분리 (KB-298)

## 한 줄 요약

`food.display_name` 컬럼(원본 표기)을 추가하고, 화면에 나가는 한국 메뉴명을 전부 이 값으로 교체한다. `korean_name` 은 중복 방지 match key 로 그대로 둔다.

## 변경 파일 지도

| 영역 | 파일 | 무엇을 |
|------|------|--------|
| 스키마 | `api/.../db/migration/V2026.08.05.*__add_food_display_name.sql` | 컬럼 추가 + `korean_name` 백필 |
| 엔티티 | `common/.../food/model/Food.kt` | `displayName` 프로퍼티, `incomplete(korean, display)`, `localizedName()` ko 베이스 교체, `koreanName()` 삭제 |
| 영속 | `common/.../food/FoodRepositoryCustomImpl.kt` | upsert 컬럼 추가(중복 시 기존 유지) |
| 도메인 서비스 | `common/.../food/FoodService.kt` | `createIncomplete` 원본 표기 수용, 상세 koreanName 매핑, KO 검색 키워드 정규화 |
| 요약 뷰 | `common/.../food/dto/FoodSummaryView.kt` | koreanName 매핑 교체 |
| 스캔 | `api/.../scan/ScanService.kt`·`ScanApi.kt` | miss 적재에 원본 표기 전달, matched 응답 교체, 문서 갱신 |
| 관리자 | `api/.../admin/AdminFoodService.kt`·`AdminFoodContentReviewResponse.kt` | 수정=표기 교정+재정규화, 시드 원본 보존, 응답 교체 |
| 이미지 | `api/.../food/FoodImageBatchCollectService.kt` | 프롬프트 이름 교체 |
| 배치 | `batch/.../content/FoodContentItemProcessor.kt` | LLM 호출 3곳 이름 교체 |

## 검증

```bash
./gradlew build                 # 전체 (엔티티↔스키마 정합은 Testcontainers+ddl-auto=validate 가 검증)
./gradlew :api:test --tests "*Scan*" --tests "*Food*" --tests "*Admin*"
```

### 검증 결과 (2026-08-05, `./gradlew build --rerun-tasks` 48 tasks 통과)

수동 앱 실행 대신 **통합 테스트로 검증**했다(Testcontainers MySQL + 실제 Flyway 마이그레이션):

| 확인 항목 | 근거 테스트 |
|-----------|------------|
| 미등록 "들깨 칼국수" 스캔 → 응답 원본 표기, DB `korean_name`="들깨칼국수"·`display_name`="들깨 칼국수" | `ScanControllerTest` — "띄어쓰기가 있는 미등록 메뉴를 스캔하면" |
| 표기만 다른 재스캔 시 신규 행 0건·기존 표시명 유지 | `ScanControllerTest` — "표기만 다른 같은 메뉴를 다시 스캔하면" |
| 매칭 음식 응답이 표시명 | `ScanControllerTest` — "매칭된 음식이 표시명을 갖고 있으면" |
| `keyword=들깨 칼국수`(공백 포함)·`들깨칼국수` 모두 히트 | `FoodSearchControllerTest` — "표시명 띄어쓰기와 무관한 매칭 (KB-298)" |
| 관리자 이름 교정 시 match key 재정규화·중복 판정 | `AdminFoodServiceTest` — "띄어쓰기를 넣어 이름을 교정하면" 외 1건 |
| 백필 후 빈 표시명 0건 | `FoodDisplayNameBackfillTest` |
| batch LLM 호출이 표시명 사용 | `FoodDescriptionProcessorTest` — "LLM 에 넘기는 이름" |

## 함정 (메모리·선례)

- raw `insert into food` 시드가 10+ 테스트 파일에 있다 — DEFAULT '' 로 흡수되지만, **표시명을 검증하는 신규 테스트**는 시드에 `display_name` 을 명시할 것. 전체 `./gradlew build` 로만 잡히는 유형.
- `korean_name` 정규화 이력(V2026.07.21 normalize) 때문에 기존 행은 전부 한글 음절만 — 백필값이 곧 정규화명이라 안전.
- 관리자 수정에서 match key 재정규화 누락 시 이후 스캔이 옛 key 로 매칭 드리프트 — R5 불변식 테스트 필수.
