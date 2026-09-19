# Research: 회원 알림함 API

## 1. 목록 — 7일 창, 페이징 없음

- **Decision**: `GET /api/notifications` 파라미터 없음. 서비스가 `since = LocalDateTime.now().minusDays(7)` 를 계산해 파생 쿼리 `findByMemberIdAndCreatedAtAfterOrderByIdDesc(memberId, since)` 로 전부 읽는다. 응답은 `List<NotificationResponse>` 를 그대로 payload 에.
- **Rationale**: 사용자 결정(7일 이내만, 페이징 불필요). `created_at` 은 BaseEntity 가 저장 시 채우므로 "알림 시각" 으로 쓴다. `id desc` 는 생성 순 역순과 같다. 기존 keyset 메서드 `findPageByMemberId` 는 손대지 않고 남긴다(KB-464 산출).
- **Alternatives considered**: `Page<T>` 봉투 유지 — hasNext 가 항상 false 라 의미 없어 기각. 컨트롤러에서 7일 계산 — 시각 계산은 서비스 책임으로.

## 2. JWT 보호 경로 — 토큰 등록의 게스트 경로를 살리는 등록 방식

- **Decision**: `WebConfig.addUrlPatterns` 에 `${ApiPaths.API}/notifications`(정확 일치)와 `${ApiPaths.API}/notifications/*` 추가, 기존 `/notifications/settings` 줄 제거(`/*` 가 덮음). `guestExemptions` 에 `GuestExemption("PUT", Regex("^${ApiPaths.API}/notifications/tokens$"))` 추가.
- **Rationale**: 서블릿 패턴은 중간 와일드카드를 못 쓴다. `/*` 없이 `/{id}/read` 를 보호할 방법이 없다. **기획 변경(2026-09-07): 비회원 알림은 제거 예정**이라 게스트 토큰 등록은 이 기능의 설계 고려 대상이 아니다. 다만 아직 코드와 `NotificationTokenControllerTest` 게스트 시나리오가 남아 있으므로 빌드를 깨지 않는 최소 조치로 `GuestExemption` 한 줄만 둔다 — 게스트 토큰 등록을 걷어내는 후속 태스크에서 이 줄도 같이 삭제한다.
- **Alternatives considered**: 지금 게스트 토큰 등록을 함께 제거 — 별도 티켓 범위라 기각. 알림함 경로를 `/api/inbox` 로 분리 — Jira 계약과 어긋나 기각.

## 3. 타인·부재 알림 읽음 → 404 `NOTIFICATION-002`

- **Decision**: `ErrorCode.NOTIFICATION_NOT_FOUND("NOTIFICATION-002", 404, "해당 알림을 찾을 수 없습니다")`. 파생 쿼리 `findByIdAndMemberId(id, memberId): Notification?` → null 이면 throw. 남의 것·없음·소프트삭제(`@SQLRestriction`) 가 같은 응답. 7일 경계는 읽음 처리엔 적용하지 않는다(스펙 US2-5).
- **Rationale**: Jira "타인 알림 접근 시 404". `ORDER-002`·`BLOCK-002` 가 404 선례. 소유 조건을 쿼리에 넣어 분기 제거. `NOTIFICATION-001` 은 KB-466 사용 중.

## 4. 읽음 응답과 멱등

- **Decision**: `PATCH /api/notifications/{id}/read` → 200, payload = 갱신된 `NotificationResponse`(`read: true`). `Notification.markRead(now)` 가 readAt 있으면 유지하므로 서비스는 조회 → markRead → dirty checking. 읽음 취소 없음.
- **Rationale**: 앱이 항목 하나를 바로 갱신. 멱등은 엔티티가 보장.

## 5. 응답 필드 — 4개 + id

- **Decision**: `NotificationResponse(id, title, body, createdAt, read: Boolean)`. `read = readAt != null`. `type`·`data`·`readAt` 은 싣지 않는다.
- **Rationale**: 사용자 지시("제목·본문·알림 시각·읽음 여부면 된다"). id 는 읽음 처리 대상 지정에 필요. 저장돼 있으니 앱이 필요로 하면 필드 추가만으로 확장.

## 6. 회원 존재 검증

- **Decision**: 두 서비스 메서드 진입에서 `memberService.getMember(memberId)`. `NotificationSettingService` 와 동일.

## 7. 만들지 않는 것

- 모두 읽기·읽음 취소·미읽음 수·페이징·종류 필터. 기존 `markAllReadByMemberId`·`countByMemberIdAndReadAtIsNull`·`findPageByMemberId` 는 KB-464 산출로 남겨 둔다(발송 배치가 쓸 수 있음). Jira DoD 의 "결정 기록" 은 구현 후 KB-467 코멘트로.
