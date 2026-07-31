# Data Model: 사용자 차단 (Member Block)

## 엔티티

### MemberBlock (`com.kbap.common.domain.block.model.MemberBlock`)

독립 block 컨텍스트 소속(research R0) — `ModuleBoundaryTest` 허용 맵에 `"block" to setOf("member")` 추가.

`BaseEntity` 상속 — `id`(IDENTITY)·`status`(ACTIVE/DELETED 소프트삭제, `@SQLRestriction` 상시 필터)·`createdAt`·`updatedAt` 공통 제공.

| 필드 | 타입 | 컬럼 | 제약 |
|------|------|------|------|
| blockerMemberId | Long | `blocker_member_id` bigint NOT NULL | 차단한 회원 id (FK → member.id) |
| blockedMemberId | Long | `blocked_member_id` bigint NOT NULL | 차단당한 회원 id (FK → member.id) |

- JPA 연관관계 없음 — 헌법 IV 대로 `Long` id 값 컬럼만 든다. FK 는 Flyway 스키마가 강제(ON DELETE 없음 — 회원은 소프트삭제).
- 회원 스냅샷(닉네임 등)을 저장하지 않는다 — 목록 응답은 조회 시점에 member 를 다시 읽어 최신 값을 합친다(FR-007).
- 도메인 메서드는 `BaseEntity` 의 `active()`/`delete()`/`isDeleted()` 로 충분 — 엔티티 자체 상태 전이 로직 추가 없음.

### 상태 전이 (회원 쌍당 최대 1행)

```text
(행 없음) --block--> ACTIVE --unblock--> DELETED --재차단(block)--> ACTIVE
```

- `UNIQUE (blocker_member_id, blocked_member_id)` 가 쌍당 1행을 강제한다. 해제는 행 삭제가 아니라 status=DELETED 이므로 재차단 시 INSERT 대신 기존 행을 ACTIVE 로 되살린다(research R1).
- 멱등: ACTIVE 에 block, 행 없음/DELETED 에 unblock 은 상태 변화 없이 성공.

## 테이블 (Flyway — `V<생성시각>__member_block_table.sql`)

```sql
CREATE TABLE `member_block` (
    `id`                bigint      NOT NULL AUTO_INCREMENT,
    `blocker_member_id` bigint      NOT NULL,
    `blocked_member_id` bigint      NOT NULL,
    `status`            enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`        datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`        datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_block_pair` (`blocker_member_id`, `blocked_member_id`),
    CONSTRAINT `fk_member_block_blocker` FOREIGN KEY (`blocker_member_id`) REFERENCES `member` (`id`),
    CONSTRAINT `fk_member_block_blocked` FOREIGN KEY (`blocked_member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- 테이블명은 기존 단수 관례(`food_review`·`member`)를 따라 `member_block`.
- 조회용 별도 인덱스 불필요 — 주 조회 2종(쌍 단건, blocker 기준 목록)이 모두 `uk_member_block_pair` 좌측 접두(`blocker_member_id`)로 커버된다.
- 마이그레이션 파일명 timestamp 는 파일 생성 시점 로컬 시각으로 채번(컨벤션 고정)하며, 다른 미적용 마이그레이션과 순서 독립.

## 리포지토리 (`MemberBlockJpaRepository`, public)

| 메서드 | 방식 | 용도 |
|--------|------|------|
| `findBlockedMemberIds(blockerMemberId): List<Long>` | JPQL projection (`@SQLRestriction` 적용 → ACTIVE 만) | 차단 목록·리뷰 제외 필터 |
| `findAnyByPair(blockerMemberId, blockedMemberId): MemberBlock?` | **native** (상태 무시, LIMIT 1) | 재차단 부활 — DELETED 행 탐지 |
| `findByBlockerMemberIdAndBlockedMemberId(...): MemberBlock?` | 파생 쿼리 (ACTIVE 만) | 해제 대상 조회 |

## 기존 모델 변경

- `ReviewJpaRepository.findFoodReviewPage` — `excludedMemberIds: List<Long>` 파라미터 추가(`and r.memberId not in :excludedMemberIds`, 빈 목록은 호출부가 `-1` 센티널 — research R2). `findMemberReviewPage`·`aggregateRating` 무변경(research R4·R5).
- `ErrorCode` — `SELF_BLOCK_FORBIDDEN("BLOCK-001", 400)`·`BLOCK_TARGET_NOT_FOUND("BLOCK-002", 404)` 추가 — BLOCK- 접두 신설(research R3).
- `ModuleBoundaryTest.allowedDomainDeps` — `"block" to setOf("member")` 추가(research R0).
- Member·Review 엔티티·테이블 무변경.
