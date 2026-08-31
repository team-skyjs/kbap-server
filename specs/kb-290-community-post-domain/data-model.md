# Data Model: 커뮤니티 게시글 도메인 (KB-290)

> 2026-08-04 개정: 인덱스 2종(`idx_community_post_member`·`idx_community_post_feed`)은 선제 추가하지 않는다 — 필요가 실측되면 후속에서 붙인다(`member_id` 는 FK 라 InnoDB 가 인덱스를 자동 생성). `community_post_food_tag` 테이블은 `food_ids` JSON 컬럼으로 대체(research.md R2).

## 엔티티

### Posting — `com.kbap.common.domain.community.model`

커뮤니티 글 한 건. 엔티티가 곧 도메인 모델(검증·수정·소유 판정 메서드 내장). 이 컨텍스트의 유일한 엔티티다. 테이블은 `community_post`(`Review`→`food_review` 선례처럼 엔티티명과 테이블명 분리 — 후속 `community_comment` 등과 접두 정합).

| 필드 | 컬럼 | 타입 | 제약 |
|---|---|---|---|
| (BaseEntity) id | `id` | BIGINT IDENTITY | PK |
| memberId | `member_id` | BIGINT | NOT NULL, FK→`member.id`(Flyway) |
| content | `content` | VARCHAR(2000) | NOT NULL, 1~2,000자(엔티티 require) |
| imageRefs | `image_refs` | JSON (`List<String>?`) | ≤4장(require), 리스트 순서 = 표시 순서, 첫 원소 = 피드 커버 |
| foodIds | `food_ids` | JSON (`List<Long>?`) | ≤3개·중복 불가(require), READY 음식 존재 검증은 서비스 |
| editedAt | `edited_at` | DATETIME NULL | 수정 성공 시각. null = 수정 이력 없음 |
| (BaseEntity) status | `status` | VARCHAR | ACTIVE/DELETED 소프트 삭제(`@SQLRestriction`) |
| (BaseEntity) createdAt/updatedAt | `created_at`/`updated_at` | DATETIME | 공통 |

도메인 메서드:
- `update(content, imageRefs, foodIds)` — 재검증 후 반영 + `editedAt` 갱신
- `isOwnedBy(memberId): Boolean`
- 검증 상수: `MAX_CONTENT_LENGTH = 2000`, `MAX_IMAGE_COUNT = 4`, `MAX_FOOD_TAG_COUNT = 3`

## 리포지토리 — `com.kbap.common.domain.community`

- `PostingJpaRepository : JpaRepository<Posting, Long>`

## Flyway 마이그레이션 (`:api` — `db/migration`, timestamp 버전)

```sql
CREATE TABLE community_post (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT        NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    image_refs JSON          NULL,
    food_ids   JSON          NULL,
    edited_at  DATETIME      NULL,
    status     VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME      NOT NULL,
    updated_at DATETIME      NOT NULL,
    CONSTRAINT fk_community_post_member FOREIGN KEY (member_id) REFERENCES member (id)
);
```

- 명시 인덱스 없음 — `member_id` 는 FK 로 InnoDB 자동 인덱스. 피드 커서용 인덱스는 KB-291 에서 실측 후 필요 시 추가.
- FK 에 ON DELETE 없음(소프트 삭제 구조 — 컨벤션).
- 컬럼명·타입은 엔티티와 일치(테스트 프로필 `ddl-auto=validate` 가 정합 검증).

## 상태 전이

```
ACTIVE ──delete()──▶ DELETED   (복구 없음, 조회 자동 제외)
```

- 글 DELETED → 하위 댓글(KB-292)은 활성 글 경유 조회로만 노출되므로 자동 비노출(통삭제 성립).
- `food_ids`·`image_refs` 는 글 row 에 내장돼 글이 DELETED 면 함께 조회 대상에서 사라진다.

## 도메인 경계

- 신규 컨텍스트 `community` — `ModuleBoundaryTest` 허용 맵에 `"community" to emptySet()` 추가(엔티티는 Long id 참조만).
- 교차 도메인 조합(음식 READY 검증·이미지 소유 검증·회원)은 `com.kbap.api.community` 서비스가 수행.
