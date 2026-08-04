# API Contracts: 음식 표시용 이름 분리 (KB-298)

**신규 엔드포인트 없음. 모든 응답 필드 구조(필드명·타입·널러블)는 무변경** — 아래 필드들의 **값의 출처만** `korean_name`(정규화) → `display_name`(원본 표기)으로 바뀐다.

## 값이 바뀌는 응답 필드

| API | 필드 | 변경 전 값 | 변경 후 값 |
|-----|------|-----------|-----------|
| `POST /api/v1/scans` (`ScanResponse.items[]`) | `name` (miss) | 비전 추출 원본명 | 동일(무변경) |
| | `name` (matched, lang 번역 부재 폴백) | `korean_name` | `display_name` |
| | `koreanName` | `korean_name` | `display_name` |
| `GET /api/v1/foods/detail` (`FoodDetailResponse`) | `name` (ko/폴백) | `korean_name` | `display_name` |
| | `koreanName` | `korean_name` (name 과 다를 때만) | `display_name` (name 과 다를 때만 — null 조건 동일) |
| 음식 목록·검색·북마크·홈 요약 (`FoodSummaryResponse` ← `FoodSummaryView`) | `name`·`koreanName` | 상동 | 상동 |
| 커뮤니티 피드/상세 음식 태그 (`PostingFoodTagResponse`) | `name` | `korean_name`(ko) | `display_name`(ko) |
| 관리자 음식 목록·상세·검수 응답 (`AdminFoodService` 응답·`AdminFoodContentReviewResponse`) | `koreanName` | `korean_name` | `display_name` |

## 요청 의미가 바뀌는 엔드포인트 (형식 무변경)

| API | 파라미터 | 변경 후 의미 |
|-----|---------|------------|
| 관리자 음식 수정 | `koreanName` | 입력 = 표기 교정값 → `display_name` 저장 + `korean_name = matchKey(입력)` 재정규화. 중복 검사는 match key 기준(409 조건 동일) |
| 관리자 시드 등록 (`POST /api/v1/admin/foods/seed`) | `koreanNames[]` | 입력 원본 표기 → `display_name`, `matchKey(입력)` → `korean_name`. 중복 판정은 match key 기준(기존과 동일 건수 응답) |
| 음식 검색 (`keyword`) | `keyword` | match key 컬럼에는 정규화 검색어, 표시명·번역에는 원문 검색어로 OR 매칭 — 공백 유무 불문("김치 찌개" = "김치찌개")이면서 표시명에만 있는 영문·숫자 조각("BHC")도 찾힌다 |
| 관리자 음식 목록 검색 (`query`) | `query` | 동일한 두 갈래 매칭 |

## 클라이언트 호환성

- 필드 추가·삭제·리네임 없음 → 기존 클라이언트 무수정 호환(SC-004).
- swagger 문구(`ScanApi` 의 `koreanName` 설명 "표준 한국어명")를 "표시용 한국어명(원본 표기)" 로 갱신한다 — 문서만, 스키마 불변.
