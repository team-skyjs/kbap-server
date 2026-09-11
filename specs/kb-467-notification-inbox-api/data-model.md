# Data Model: 회원 알림함

스키마 변경 없음. KB-464 의 `notification` 테이블과 엔티티를 읽기·읽음 갱신 용도로 쓴다.

## Notification (기존, `common.domain.notification.model`)

| 필드 | 타입 | 이 기능에서의 역할 |
|------|------|--------------------|
| `id` | Long (IDENTITY) | 항목 식별자. 정렬 키(생성 순 단조증가) |
| `memberId` | Long? | 소유자. 목록·읽음 모두 `memberId = 인증 회원` 조건 |
| `installationId` | String? | 게스트 수신자 — 이 기능은 보지 않음 |
| `type`·`data` | enum·JSON | 저장돼 있으나 응답에 싣지 않음 |
| `title`·`body` | String | 저장값 그대로 응답 |
| `readAt` | LocalDateTime? | null = 안 읽음(새 알림). `markRead(now)` 는 null 일 때만 채움 |
| `createdAt` | LocalDateTime (BaseEntity) | "알림 시각". 7일 창의 기준 |
| `status` | ACTIVE/DELETED (BaseEntity) | `@SQLRestriction` 으로 DELETED 는 모든 조회에서 부재 |

## 리포지토리 (추가 2)

| 메서드 | 상태 | 용도 |
|--------|------|------|
| `findByMemberIdAndCreatedAtAfterOrderByIdDesc(memberId, since): List<Notification>` | **추가** | 목록 |
| `findByIdAndMemberId(id, memberId): Notification?` | **추가** | 단건 읽음의 소유 검증 겸 조회 |
| `findPageByMemberId`·`markAllReadByMemberId`·`countByMemberIdAndReadAtIsNull` | 기존, 미사용 | KB-464 산출. 손대지 않음 |

## 응답 DTO (신규)

```
NotificationResponse
  id: Long
  title: String
  body: String
  receivedAt: Long             # 수신 시각, epoch 밀리초 — 리뷰 응답·Order.orderedAt() 과 같은 변환
                               #   createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  read: Boolean                # readAt != null. false = 새 알림
```

목록 응답 payload 는 `List<NotificationResponse>`.

## 규칙

- 목록 창: `createdAt > now - 7d`(168시간). 조회 시각 기준.
- 상태 전이: `readAt: null → now` 한 번뿐. 되돌리기 없음.
