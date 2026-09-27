# Research: 주문 1시간 후 리뷰 리마인더 푸시 배치 (KB-469)

**Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

## 1. 대상 주문 조회 — 어디에, 어떤 형태로

**Decision**: `OrderJpaRepository` 에 JPQL 하나를 추가한다. 주문(`Order`)을 구동 테이블로 두고 알림(`Notification`)·주문 항목(`OrderItem`)·리뷰(`Review`) 는 서브쿼리로만 참조한다. 리더는 스캔 제안 리더와 같은 **id 커서 + `Limit`** 페이징이며 반환 타입은 `Order` 엔티티(id·memberId 만 쓴다).

```
select o from Order o
where o.createdAt between :from and :to
  and o.id > :afterId
  and not exists (select 1 from Notification n
                  where n.memberId = o.memberId
                    and n.type = com.kbap.common.domain.notification.model.NotificationType.REVIEW_REMINDER
                    and n.createdAt >= o.createdAt)
  and exists (select 1 from OrderItem oi
              where oi.orderId = o.id
                and not exists (select 1 from Review r
                                where r.memberId = o.memberId and r.foodId = oi.foodId))
order by o.id
```

**Rationale**:
- 소프트 삭제 필터: 모든 엔티티가 `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 를 상속하므로 JPQL 서브쿼리의 `Notification`·`Review`·`OrderItem` 참조에도 Hibernate 가 `status = 'ACTIVE'` 를 붙인다. 실패로 소프트 삭제된 알림 행이 자동으로 빠져 재시도(FR-009)가 성립한다. 네이티브 SQL 이면 이 조건을 손으로 써야 해 JPQL 을 택한다. 리더 통합 테스트(US2-5)로 확인한다.
- 도메인 경계: JPQL 문자열의 타 도메인 엔티티 참조는 Kotlin 컴파일 의존이 아니라 ArchUnit 허용 맵(`order → emptySet()`)에 걸리지 않는다. 알림 유형도 파라미터로 받으면 `NotificationType` 시그니처 의존이 생겨 ArchUnit 이 막는다(구현 중 실제로 걸림) — JPQL enum 리터럴(FQN)로 쿼리 안에 고정한다. 숨은 결합이므로 plan 의 Complexity Tracking 에 기록한다.
- 선례: `NotificationSettingJpaRepository.findNewsMemberIdsNotNotifiedSince` 가 같은 "not exists 알림" 패턴 + 커서 페이징이다.

**Alternatives considered**:
- 배치 모듈에 `JdbcTemplate` 네이티브 SQL 리더 — 배치 전용 조합 로직(컨벤션 규칙 8)에 부합하지만, 배치에 네이티브 SQL 선례가 없고 `status='ACTIVE'` 4곳을 수동 관리해야 한다. 기각.
- 배치 패키지에 Spring Data 리포지토리 인터페이스 — 리포지토리는 `common.domain.<ctx>` 소유라는 헌법 IV 위반. 기각.
- 주문 테이블 발송 마커 컬럼 — 스펙이 범위 밖으로 명시. 기각.

## 2. 발송 단위와 같은 회원 중복

**Decision**: 라이터는 청크의 주문을 **회원별로 첫 주문만** 남기고(`distinctBy { memberId }`), 주문 1건마다 `PushRequest(REVIEW_REMINDER, listOf(memberId), data = {orderId}, ttlSeconds)` 를 하나씩 보낸다. 리더 페이지 크기 = 청크 크기(100)로 고정한다.

**Rationale**: `PushRequest.data` 는 요청당 하나라 회원을 묶을 수 없다(스펙 결정표 "발송 단위"). 같은 회원의 주문 두 건이 **같은 페이지**에 있으면 DB not-exists 로는 못 거른다(둘 다 이미 읽힌 뒤 발송) — 라이터 dedupe 한 줄이 이를 막는다. **페이지가 다르면** 앞 청크 커밋 후 다음 페이지를 읽으므로 알림 행이 이미 있어 쿼리에서 빠진다. 이 보장은 페이지 크기 = 청크 크기여야 성립하므로 설정을 하나(`chunk-size`)로 둔다(스캔 제안과 같은 구조).

**Alternatives**: 리더에서 회원당 1주문만 뽑는 `group by`/상관 서브쿼리 — 엔티티 반환 불가·쿼리 복잡. 기각. `PushRequest` 를 회원별 data 로 확장 — 스펙 범위 밖(sealed 리팩터 별도 티켓). 기각.

## 3. 시간 창 계산

**Decision**: 리더 `open()` 에서 `now = clock.nowInJvmZone()`(배치 기존 확장 함수) 를 잡고 `from = now - 25h`, `to = now - 1h` 를 고정한다. `Clock` 빈은 `ScanSuggestionPushBatchConfig.clock()`(Asia/Seoul) 을 재사용하고 테스트는 `MutableClock` 으로 바꾼다.

**Rationale**: `createdAt` 은 JVM 기본 시간대의 `LocalDateTime`(`@CreationTimestamp`) 이므로 비교값도 같은 변환을 쓴다. 한 실행 안에서 창을 고정해야 페이지 간 경계가 흔들리지 않는다.

## 4. 메트릭 상수 공유

**Decision**: `ScanSuggestionPushWriter.METRIC`("kbap.push.dispatch") 을 `com.kbap.batch.notification.PushDispatchMetric.NAME` 으로 올리고 두 라이터가 참조한다.

**Rationale**: 형제 라이터의 상수를 끌어 쓰는 것보다 공용 위치가 명확하다. 태그 어휘(`type`·`result`)는 그대로.

## 5. 알림함 `orderId` 추출

**Decision**: `Notification.foodIdOrNull()` 의 정수 변환을 `private fun longFromData(key)` 로 뽑고 `orderIdOrNull()` 을 추가한다(둘 다 `REVIEW_REMINDER` 한정). `NotificationResponse` 에 `orderId: Long?` 필드 추가, `from()` 에서 매핑.

**Rationale**: 스펙이 "foodId 와 같은 규칙" 을 요구한다. 두 메서드가 한 변환을 공유하면 규칙 드리프트가 없다.

## 6. 문구 파일

**Decision**: 10개 `push_*.properties` 의 `push.review_reminder.{1,2,3}.body` 만 교체하고 `{food}` 를 제거한다. 제목 3변형은 유지. 한국어는 스펙 초안 3개, 나머지 9개 언어는 같은 뜻으로 번역한다(최종 문구 다듬기는 구현 후 별도).

**Rationale**: 렌더러는 인자 없는 `{...}` 를 빈 문자열로 채워 조용히 깨지므로(`PushMessageRenderer.fill`), 렌더러 테스트에 "모든 로케일의 REVIEW_REMINDER 본문에 `{` 가 없다" 를 추가해 강제한다(SC-006).

## 7. 스케줄·설정

**Decision**: `BatchJobScheduler` 에 `@Scheduled(cron = ReviewReminderPushBatchConfig.EVERY_5_MINUTES)` 한 줄. 설정은 `kbap.batch.review-reminder.{chunk-size:100, ttl:6h}`. 잡 이름 `reviewReminderPushJob`, 스텝 `reviewReminderSendStep`. HTTP 트리거는 기존 `POST /internal/batch/jobs?jobName=` 이 잡 목록에서 자동으로 찾는다.

**Rationale**: 스캔 제안과 동일 구조. 부팅 자동 실행은 `spring.batch.job.enabled=false` 로 이미 차단.

## 8. 인덱스

**Decision**: `orders(created_at)` 단일 컬럼 인덱스를 Flyway 로 추가한다(`idx_orders_created_at`). 엔티티 `@Table(indexes)` 도 동기화.

**Rationale**: 5분마다 `created_at between` 창을 훑는데 기존 인덱스는 `(member_id, id)` 뿐이라 주문 누적에 비례해 PK 순 스캔 비용이 커진다(Codex 리뷰 P2 수용, 2026-09-28). 처음엔 물량이 작아 보류했지만 마이그레이션 한 파일이라 지금 넣는 편이 싸다.
