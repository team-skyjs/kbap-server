# Data Model: 커뮤니티 댓글/대댓글

## 엔티티

### Comment (`com.kbap.common.domain.community.model.Comment`)

`BaseEntity` 상속(공통 `id`·`status` ACTIVE/DELETED·`createdAt`·`updatedAt`, `@SQLRestriction("status = 'ACTIVE'")` 상시 적용). 엔티티가 곧 도메인 모델 — 별도 변환 없음. JPA 연관관계 없음(전부 id 값 컬럼).

| 필드 | 타입 | 컬럼 | 제약 | 의미 |
|------|------|------|------|------|
| postId | `Long` | `post_id` | NOT NULL, FK→community_post(id) | 소속 글 |
| memberId | `Long` | `member_id` | NOT NULL, FK→member(id) | 작성자 |
| parentId | `Long?` | `parent_id` | NULL, FK→community_comment(id) | 최상위 댓글이면 null, 대댓글이면 **항상 최상위 댓글 id**(1depth 불변식 — 저장 전 서버 정규화, R3) |
| content | `String` (var) | `content` | NOT NULL, VARCHAR(2000) | 본문. @멘션 텍스트 포함 가능(구조화 저장 없음) |
| editedAt | `LocalDateTime?` | `edited_at` | NULL | 사용자 수정 시각 — `updatedAt` 과 분리(Posting 선례와 동일 사유) |

**도메인 메서드** (Posting 선례 미러링):

- `init` / `requireValid(content)` — 공백 금지·최대 2,000자(`MAX_CONTENT_LENGTH = 2000`).
- `update(content: String)` — 재검증 후 본문 교체 + `editedAt = now()`.
- `isOwnedBy(memberId: Long): Boolean`
- `isReply: Boolean` (= `parentId != null`) — 삭제 분기(통삭제 vs 단독)용.

**상태 전이**:

- 생성 → ACTIVE.
- 삭제(최상위): 본체 `delete()` + 하위 대댓글 bulk UPDATE `status = DELETED` (통삭제, R1).
- 삭제(대댓글): 본체 `delete()` 만.
- DELETED 는 종단 상태 — 복구 없음. DELETED 행은 `@SQLRestriction` 으로 모든 엔티티 조회에서 자동 제외.

## 리포지토리

### CommentJpaRepository (`com.kbap.common.domain.community.CommentJpaRepository`)

`JpaRepository<Comment, Long>` + 파생/`@Query`:

| 메서드 | 쿼리 개요 | 용도 |
|--------|-----------|------|
| `findTopLevelPage(postId, cursor, pageable)` | `parent_id is null and post_id = :postId and (:cursor is null or id > :cursor) order by id asc` | 목록 커서 페이지(등록순, size+1) |
| `findByParentIdInOrderByIdAsc(parentIds)` | 파생 쿼리 | 페이지 내 top-level 들의 대댓글 일괄 로드 |
| `countByPostIds(postIds)` | `select c.postId, count(c.id) … where c.postId in :postIds group by c.postId` | 피드·상세 commentCount 배선(R5) |
| `softDeleteReplies(parentId)` | `@Modifying` `update Comment c set c.status = DELETED where c.parentId = :parentId` | 통삭제(R1) — 멱등 |

주: 모든 SELECT 는 `@SQLRestriction` 이 ACTIVE 만 보므로 status 조건을 손으로 달지 않는다(컨벤션).

## Flyway 마이그레이션

`api/src/main/resources/db/migration/Vyyyy.MM.dd.HH.mm.ss__community_comment_table.sql` — 파일 생성 시점 로컬 시각으로 명명(예: `V2026.08.04.22.10.00__…`). 다른 미적용 마이그레이션과 순서 독립.

```sql
CREATE TABLE community_comment
(
    id         BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_id    BIGINT                    NOT NULL,
    member_id  BIGINT                    NOT NULL,
    parent_id  BIGINT                    NULL,
    content    VARCHAR(2000)             NOT NULL,
    edited_at  DATETIME(6)               NULL,
    status     ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)               NOT NULL,
    updated_at DATETIME(6)               NOT NULL,
    CONSTRAINT fk_community_comment_post FOREIGN KEY (post_id) REFERENCES community_post (id),
    CONSTRAINT fk_community_comment_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_community_comment_parent FOREIGN KEY (parent_id) REFERENCES community_comment (id),
    INDEX idx_community_comment_post_parent (post_id, parent_id, id),
    INDEX idx_community_comment_parent (parent_id)
);
```

- `idx_community_comment_post_parent`: 목록 커서(`post_id + parent_id is null + id` 범위 스캔)·post별 카운트 커버.
- `idx_community_comment_parent`: 대댓글 일괄 로드·통삭제 bulk UPDATE 용. (FK 인덱스 겸용)
- ON DELETE 없음 — 소프트 삭제 구조(컨벤션).

## 관계 요약

```text
member 1 ──── N community_comment (member_id, id 값 참조 — 탈퇴 시 행 유지·조립 단계 익명화)
community_post 1 ──── N community_comment (post_id)
community_comment(top-level) 1 ──── N community_comment(reply) (parent_id, 깊이 최대 1)
```

도메인 간 신규 의존 없음 — `ModuleBoundaryTest` 의 `"community" to emptySet()` 그대로.
