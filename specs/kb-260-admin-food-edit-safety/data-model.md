# Data Model: 관리자 음식 수정 안정성

**스키마 변경 없음** — Flyway 마이그레이션·엔티티 필드 추가 없다. 기존 모델의 상태 전이 의미론만 관리자 저장 경로에 연결한다.

## Food (`com.kbap.common.domain.food.model.Food` — 기존, 무변경)

| 필드 | 의미 | 이번 기능에서의 역할 |
|------|------|---------------------|
| `koreanName` | 한국어 이름(유일) | 폼 수정 대상. 빈 값·중복 거절(기존) |
| `description` | 설명 | 완성도 판정 입력 |
| `nameTranslations` / `descriptionTranslations` | 9개 대상 언어 번역 맵 | 완성도 판정 입력(전 언어 non-blank 필요) |
| `avoidanceSubstances` | 기피 성분 목록(null=미조사) | 완성도 판정 입력 |
| `spiciness` | 맵기(-1=미조사) | 완성도 판정 입력 |
| `imageRef` | 이미지 키(null=없음) | PENDING_IMAGE ↔ PENDING_REVIEW 분기 입력 |
| `contentStatus` | 콘텐츠 상태 | 저장 시 자동 보정 대상 |

## FoodContentStatus 상태 전이 (기존 `transitionByContentState()` 의미론)

```text
[검수 이전 — 기계 판정, 저장 시 재계산]
INCOMPLETE      : 텍스트 콘텐츠(설명·이름번역·설명번역·기피성분·맵기) 미완
PENDING_IMAGE   : 텍스트 완비 + 이미지 없음
        │
        ▼ (텍스트 완비 + 이미지 있음)
[검수 단계 — 사람 판단이 정본, 자동 전이 대상 아님(sticky)]
PENDING_REVIEW  : 검수 대기 (자동 도달 가능 최고 상태)
READY           : 검수 완료 노출 (수동 지정으로만 도달)
```

## 관리자 저장 시 상태 결정 규칙 (이번 기능의 유일한 행위 변경)

1. 폼 값 반영: `food.contentStatus = 관리자 선택값` 포함 전 필드 대입 (기존).
2. `food.transitionByContentState()` 호출 (신규 1줄):
   - 선택값이 `PENDING_REVIEW`·`READY` → 그대로 유지 (수동 지정 우선).
   - 선택값이 `INCOMPLETE`·`PENDING_IMAGE` → 저장 시점 완성도로 재계산 (완성도 우선).
3. 검증 실패(INVALID_NAME·DUPLICATE_NAME·INVALID_JSON·NOT_FOUND) 시 2 에 도달하지 않는다 — 보정 없음.

## 뷰 모델 (api admin 패키지 — 최소 변경)

- `AdminFoodDetailView` (기존, 무변경) — 읽기 전용/편집 양쪽 렌더 소스.
- 컨트롤러 모델에 `editMode: Boolean` 추가 (`edit` 쿼리 파라미터 유무) — 템플릿 분기 전용, 영속 무관.
