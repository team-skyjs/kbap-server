# Data Model: KB-301

## Food (`food` 테이블 — 변경분만)

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| `content_status` | ENUM 6값 (INCOMPLETE·PENDING_IMAGE·PENDING_REVIEW·REVIEWED·REVIEW_REJECTED·READY) | ENUM 4값 **(FAILED·PENDING_IMAGE·PENDING_REVIEW·READY)** |
| `avoidance_substances` (JSON) | `[{code, inclusion_percent}]` | 컬럼명 **`ingredients`** 로 개명 — 값 형식·데이터 불변 |
| `content_review_attempts`·`content_review_rejection_reason` | 반려 재시도 로직이 사용 | 컬럼 유지, 관리자 참고 정보로만 사용(재시도 로직 폐기) |

엔티티 대응: `contentStatus: FoodContentStatus`(4값), `avoidanceSubstances: List<FoodAvoidanceItem>?` → `ingredients: List<FoodIngredient>?`(`@Column(name = "ingredients")`, nullable = 미조사 센티널 유지).

## 상태 머신 (신)

```
                    (KB-302: 랭체인 fail)
스캔 센티널 생성 ──▶ FAILED ──resubmit(관리자 수정 완료)──▶ PENDING_IMAGE
                      ▲                                        │ attachImage(이미지 부착)
                      │ reject(관리자 반려, 사유 기록)           ▼
                    READY ◀──approve(관리자 승인)── PENDING_REVIEW
```

- 정의된 전이 외에는 `require` 로 거부(FR-002).
- `FAILED` 는 이미지 생성 후보 수집(`PENDING_IMAGE` 조회)에 나타나지 않는다(FR-003) — 상태 분리로 자동 충족.
- 사용자 조회 노출은 `READY` 만(기존 `isReady()` 유지, FR-004).

## 기존 데이터 마이그레이션 매핑 (FR-005)

| 구 상태 | 신 상태 | 근거 |
|---------|---------|------|
| READY | READY | 동일 의미 |
| PENDING_IMAGE | PENDING_IMAGE | 동일 의미 |
| PENDING_REVIEW | PENDING_REVIEW | 이미지 완료·승인 대기로 의미 이동 |
| REVIEWED | PENDING_REVIEW | 구 "검수 통과·READY 승격 대기" ≒ 신 승인 대기 |
| REVIEW_REJECTED | FAILED | 관리자 확인 필요 |
| INCOMPLETE | FAILED | 배치 미채움 영구 미완성 → 관리자/재수집 대상 |

## ingredients (구 avoidance_substance 테이블 — 개명만, R7)

- `avoidance_substance`(81종 카탈로그: code PK·korean_name·translations) → **RENAME TABLE `ingredients`**. 데이터·스키마 구조 불변.
- 엔티티 `AvoidanceSubstance` 의 `@Table(name = "ingredients")` 만 갱신 — 클래스·enum(`AvoidanceSubstanceCode`)·avoidance 패키지 어휘는 유지(기피는 프로필 쪽 관계 의미).
- 적용 완료된 시드 마이그레이션은 수정 금지. 신규 마이그레이션에서 RENAME 만 수행.

## FoodIngredient (구 FoodAvoidanceItem — 개명만)

- `code: String`(성분 코드), `inclusionPercent: Int`(포함 확률). 필드·직렬화 형식 불변, 클래스명만 변경.
- 회원 기피 판정(`overallRisk`) 입력 역할 불변 — avoidance 어휘는 판정 로직·회원 설정 쪽에만 남는다.

## 비활성(주석 처리) 대상 (R2·R6 — 삭제는 KB-302)

- `batch/content/*` 5파일(reader·processor·writer·config·예외)과 그 테스트 — 파일 전체 주석 처리, 상단에 사유 1줄.
- `FoodJpaRepository`: INCOMPLETE 기반 벌크 전이 쿼리(`markPendingImage`·`markPendingReview` 계열)·INCOMPLETE 네이티브 카운트 쿼리 — 주석 처리.
- `Food`: `needsDescription`·`needsNameTranslations`·`needsDescriptionTranslations`·`needsAvoidanceMapping`·`needsAvoidanceAssessment`·`transitionByContentState`·`TERMINAL_CONTENT_STATUSES`·재시도 상수 — 주석 처리. `passContentReview`/`rejectContentReview` 는 `approve`/`reject`/`resubmit` 로 **대체**(구현 교체라 주석 보존 불필요). `updateNameTranslations`/`updateDescription`/`assessAvoidance` 는 관리자 수정 경로에서 쓰이면 유지.
